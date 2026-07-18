package com.timxs.storagetoolkit.service.support;

import com.timxs.storagetoolkit.model.ReferenceReplacementResult;
import com.timxs.storagetoolkit.model.ReferenceReplacementTask;
import com.timxs.storagetoolkit.model.ReplaceResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import run.halo.app.content.ContentWrapper;
import run.halo.app.content.PatchUtils;
import run.halo.app.core.extension.content.Snapshot;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Ref;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 基于 Snapshot 的内容更新处理器抽象基类
 * 适用于 Post 和 SinglePage 等使用 Snapshot 存储内容的实体
 * <p>
 * 处理流程：封面替换 → 快照获取 → URL 替换 → 新快照创建 → 带重试更新
 *
 * @param <T> 实体类型（Post 或 SinglePage）
 */
@Slf4j
public abstract class AbstractSnapshotContentUpdateHandler<T extends AbstractExtension>
    implements ContentUpdateHandler {

    protected final ReactiveExtensionClient client;

    protected AbstractSnapshotContentUpdateHandler(ReactiveExtensionClient client) {
        this.client = client;
    }

    // ========== 子类必须实现的抽象方法 ==========

    /** 获取实体 Class */
    protected abstract Class<T> getEntityClass();

    /** 获取封面 URL */
    protected abstract String getCover(T entity);

    /** 设置封面 URL */
    protected abstract void setCover(T entity, String cover);

    /** 获取 headSnapshot 名称 */
    protected abstract String getHeadSnapshot(T entity);

    /** 设置 headSnapshot 名称 */
    protected abstract void setHeadSnapshot(T entity, String snapshotName);

    /** 获取 baseSnapshot 名称 */
    protected abstract String getBaseSnapshot(T entity);

    /** 获取 releaseSnapshot 名称 */
    protected abstract String getReleaseSnapshot(T entity);

    /** 设置 releaseSnapshot 名称 */
    protected abstract void setReleaseSnapshot(T entity, String snapshotName);

    /** 获取 subjectRef 的 Kind（"Post" 或 "SinglePage"） */
    protected abstract String getSubjectRefKind();

    // ========== 公共实现 ==========

    @Override
    public Mono<ReplaceResult> replaceUrls(String sourceName, ReferenceReplacementTask task,
                                   ReferenceReplacementResult result) {
        return client.fetch(getEntityClass(), sourceName)
            .flatMap(entity -> {
                ReplaceResult replaceResult = ReplaceResult.builder().build();

                // 1. 计算封面替换
                String originalCover = getCover(entity);
                String newCover = originalCover;
                if (StringUtils.hasText(originalCover)) {
                    for (Map.Entry<String, String> entry : task.getUrlMapping().entrySet()) {
                        if (UrlReplacer.containsUrl(newCover, entry.getKey())) {
                            newCover = UrlReplacer.replaceUrl(newCover, entry.getKey(),
                                entry.getValue());
                            replaceResult.addReplaced(entry.getKey());
                        }
                    }
                }
                final String finalNewCover = newCover;
                final boolean coverReplaced = originalCover != null && !originalCover.equals(newCover);

                // 2. 处理内容
                String headSnapshotName = getHeadSnapshot(entity);
                String baseSnapshotName = getBaseSnapshot(entity);

                if (!StringUtils.hasText(headSnapshotName)) {
                    if (coverReplaced) {
                        return updateEntityWithRetry(sourceName, finalNewCover, null, null,
                            result, replaceResult);
                    }
                    return Mono.just(ReplaceResult.empty());  // 没有内容
                }

                // 确保 baseSnapshotName 存在，如果不存在则使用 headSnapshotName
                String finalBaseSnapshotName = StringUtils.hasText(baseSnapshotName)
                    ? baseSnapshotName : headSnapshotName;

                // 同时获取 baseSnapshot 内容和当前 headSnapshot 内容
                return Mono.zip(
                    fetchBaseSnapshotContent(finalBaseSnapshotName),
                    fetchSnapshotContent(headSnapshotName, finalBaseSnapshotName)
                ).flatMap(tuple -> {
                    ContentWrapper baseWrapper = tuple.getT1();
                    ContentWrapper headWrapper = tuple.getT2();

                    String baseRaw = baseWrapper.getRaw();
                    String baseContent = baseWrapper.getContent();
                    String rawType = baseWrapper.getRawType();
                    String currentRaw = headWrapper.getRaw();
                    String currentContent = headWrapper.getContent();

                    if (!StringUtils.hasText(currentRaw)) {
                        if (coverReplaced) {
                            return updateEntityWithRetry(sourceName, finalNewCover, null, null,
                                result, replaceResult);
                        }
                        return Mono.just(ReplaceResult.empty());  // 没有内容
                    }

                    // 分别替换 Markdown 和 HTML 中的 URL
                    ReplacedContent headReplaced = applyUrlMapping(currentRaw, currentContent,
                        task.getUrlMapping(), replaceResult);
                    String newRaw = headReplaced.raw();
                    String newContent = headReplaced.content();
                    boolean contentReplaced = headReplaced.replaced();

                    if (!contentReplaced && !coverReplaced) {
                        return Mono.just(ReplaceResult.empty());  // 未找到匹配内容
                    }

                    if (contentReplaced) {
                        String originalReleaseSnapshot = getReleaseSnapshot(entity);
                        boolean wasPublished = StringUtils.hasText(originalReleaseSnapshot)
                            && originalReleaseSnapshot.equals(headSnapshotName);

                        // 创建新的 head 快照（草稿/编辑版本）
                        return createNewSnapshot(newRaw, newContent, baseRaw, baseContent,
                            rawType, entity, headSnapshotName)
                            .flatMap(newHeadSnapshotName -> {
                                if (wasPublished) {
                                    // release == head：已发布且无未发布草稿，
                                    // head 和 release 同步指向新快照
                                    return updateEntityWithRetry(sourceName, finalNewCover,
                                        newHeadSnapshotName, newHeadSnapshotName,
                                        result, replaceResult);
                                }
                                if (StringUtils.hasText(originalReleaseSnapshot)) {
                                    // release != head：已发布且存在未发布草稿，
                                    // 需对 release 内容单独执行替换
                                    return replaceReleaseSnapshot(sourceName, entity, task,
                                        originalReleaseSnapshot, finalBaseSnapshotName,
                                        baseRaw, baseContent, rawType,
                                        finalNewCover, newHeadSnapshotName,
                                        result, replaceResult);
                                }
                                // 从未发布，只更新 head
                                return updateEntityWithRetry(sourceName, finalNewCover,
                                    newHeadSnapshotName, null, result, replaceResult);
                            });
                    } else {
                        return updateEntityWithRetry(sourceName, finalNewCover, null, null,
                            result, replaceResult);
                    }
                });
            })
            .onErrorResume(e -> {
                log.error("{} {} 替换失败: {}", getSourceType(), sourceName, e.getMessage());
                ReplaceResult errorResult = ReplaceResult.builder().build();
                for (Map.Entry<String, String> entry : task.getUrlMapping().entrySet()) {
                    errorResult.addError(entry.getKey(), e.getMessage());
                }
                return Mono.just(errorResult);
            })
            .defaultIfEmpty(ReplaceResult.empty());  // 实体不存在
    }

    /**
     * 带重试的实体更新操作
     * retry 只包裹 fetch + update，快照不会重复创建
     *
     * @param entityName 实体名称
     * @param newCover 新封面 URL
     * @param headSnapshotName head 快照名称（用于草稿/编辑版本）
     * @param releaseSnapshotName release 快照名称（用于已发布版本，null 则不更新）
     * @param result 引用替换结果（用于统计）
     * @param replaceResult 单次替换结果
     * @return 替换结果
     */
    private Mono<ReplaceResult> updateEntityWithRetry(String entityName, String newCover,
                                              String headSnapshotName, String releaseSnapshotName,
                                              ReferenceReplacementResult result,
                                              ReplaceResult replaceResult) {
        return Mono.defer(() -> client.fetch(getEntityClass(), entityName)
            .flatMap(latestEntity -> {
                if (newCover != null) {
                    setCover(latestEntity, newCover);
                }
                if (headSnapshotName != null) {
                    setHeadSnapshot(latestEntity, headSnapshotName);
                }
                if (releaseSnapshotName != null) {
                    setReleaseSnapshot(latestEntity, releaseSnapshotName);
                }
                return client.update(latestEntity);
            })
        )
        .retryWhen(RetryUtils.optimisticLockRetry())
        .doOnSuccess(v -> {
            log.debug("{} {} 引用替换成功，替换了 {} 个 URL", getSourceType(), entityName,
                replaceResult.getReplaced().size());
            result.incrementTypeCount(getSourceType());
        })
        .thenReturn(replaceResult)
        .onErrorResume(e -> {
            log.error("{} {} 更新失败（重试耗尽）: {}", getSourceType(), entityName, e.getMessage());
            // 将成功匹配的 URL 转为错误
            ReplaceResult errorResult = ReplaceResult.builder().build();
            for (String url : replaceResult.getReplaced()) {
                errorResult.addError(url, e.getMessage());
            }
            return Mono.just(errorResult);
        });
    }

    /**
     * 对 release 快照内容执行替换（release != head 场景，即已发布且存在未发布草稿）
     * release 内容有匹配时创建新快照并同时更新 head/release；
     * 无匹配或 release 快照处理失败时仅更新 head（避免把已发布内容错误回退到 base 版本）
     */
    private Mono<ReplaceResult> replaceReleaseSnapshot(String sourceName, T entity,
                                               ReferenceReplacementTask task,
                                               String originalReleaseSnapshot,
                                               String baseSnapshotName,
                                               String baseRaw, String baseContent, String rawType,
                                               String newCover, String newHeadSnapshotName,
                                               ReferenceReplacementResult result,
                                               ReplaceResult replaceResult) {
        return fetchSnapshotContentStrict(originalReleaseSnapshot, baseSnapshotName)
            .flatMap(releaseWrapper -> {
                ReplacedContent replaced = applyUrlMapping(releaseWrapper.getRaw(),
                    releaseWrapper.getContent(), task.getUrlMapping(), replaceResult);
                if (!replaced.replaced()) {
                    // release 内容无匹配，只更新 head
                    return updateEntityWithRetry(sourceName, newCover,
                        newHeadSnapshotName, null, result, replaceResult);
                }
                return createNewSnapshot(replaced.raw(), replaced.content(),
                    baseRaw, baseContent, rawType, entity, originalReleaseSnapshot)
                    .flatMap(newReleaseSnapshotName ->
                        updateEntityWithRetry(sourceName, newCover,
                            newHeadSnapshotName, newReleaseSnapshotName,
                            result, replaceResult));
            })
            .onErrorResume(e -> {
                log.warn("{} {} 的 release 快照 {} 替换失败，仅更新 head: {}", getSourceType(),
                    sourceName, originalReleaseSnapshot, e.getMessage());
                return updateEntityWithRetry(sourceName, newCover,
                    newHeadSnapshotName, null, result, replaceResult);
            });
    }

    /** URL 替换后的内容 */
    private record ReplacedContent(String raw, String content, boolean replaced) {}

    /**
     * 对 raw/content 执行 URL 映射替换，命中的 URL 记录到 replaceResult
     */
    private ReplacedContent applyUrlMapping(String raw, String content,
                                            Map<String, String> urlMapping,
                                            ReplaceResult replaceResult) {
        String newRaw = raw;
        String newContent = content;
        boolean replaced = false;
        for (Map.Entry<String, String> entry : urlMapping.entrySet()) {
            boolean urlReplaced = false;
            if (UrlReplacer.containsUrl(newRaw, entry.getKey())) {
                newRaw = UrlReplacer.replaceUrl(newRaw, entry.getKey(), entry.getValue());
                urlReplaced = true;
            }
            if (StringUtils.hasText(newContent)
                && UrlReplacer.containsUrl(newContent, entry.getKey())) {
                newContent = UrlReplacer.replaceUrl(newContent, entry.getKey(), entry.getValue());
                urlReplaced = true;
            }
            if (urlReplaced) {
                replaceResult.addReplaced(entry.getKey());
                replaced = true;
            }
        }
        return new ReplacedContent(newRaw, newContent, replaced);
    }

    /**
     * 获取快照内容（严格模式）
     * 与 {@link #fetchSnapshotContent} 不同：目标快照缺失或获取失败时直接报错，
     * 不回退返回 base 内容，供 release 替换使用
     */
    private Mono<ContentWrapper> fetchSnapshotContentStrict(String snapshotName,
                                                            String baseSnapshotName) {
        return client.fetch(Snapshot.class, baseSnapshotName)
            .flatMap(baseSnapshot -> {
                if (snapshotName.equals(baseSnapshotName)) {
                    return Mono.just(ContentWrapper.patchSnapshot(baseSnapshot, baseSnapshot));
                }
                return client.fetch(Snapshot.class, snapshotName)
                    .map(snapshot -> ContentWrapper.patchSnapshot(snapshot, baseSnapshot));
            })
            .switchIfEmpty(Mono.error(new IllegalStateException(
                "快照 " + snapshotName + " 或 base 快照 " + baseSnapshotName + " 不存在")));
    }

    /**
     * 获取快照内容
     * Halo 快照采用星形结构：所有快照的 patch 都是相对于 baseSnapshot 计算的
     */
    private Mono<ContentWrapper> fetchSnapshotContent(String headSnapshotName,
                                                       String baseSnapshotName) {
        return client.fetch(Snapshot.class, baseSnapshotName)
            .flatMap(baseSnapshot -> {
                if (headSnapshotName.equals(baseSnapshotName)) {
                    return Mono.just(ContentWrapper.patchSnapshot(baseSnapshot, baseSnapshot));
                }
                return client.fetch(Snapshot.class, headSnapshotName)
                    .map(headSnapshot -> ContentWrapper.patchSnapshot(headSnapshot, baseSnapshot))
                    .onErrorResume(e -> {
                        log.error("获取快照 {} 内容失败: {}", headSnapshotName, e.getMessage());
                        return Mono.just(ContentWrapper.patchSnapshot(baseSnapshot, baseSnapshot));
                    });
            });
    }

    /**
     * 获取 baseSnapshot 的完整内容（用于计算新快照的 patch）
     * Halo 星形结构：所有快照的 patch 都相对于 baseSnapshot 计算
     */
    private Mono<ContentWrapper> fetchBaseSnapshotContent(String baseSnapshotName) {
        return client.fetch(Snapshot.class, baseSnapshotName)
            .map(snapshot -> ContentWrapper.builder()
                .snapshotName(snapshot.getMetadata().getName())
                .raw(snapshot.getSpec().getRawPatch() != null
                    ? snapshot.getSpec().getRawPatch() : "")
                .content(snapshot.getSpec().getContentPatch() != null
                    ? snapshot.getSpec().getContentPatch() : "")
                .rawType(snapshot.getSpec().getRawType())
                .build());
    }

    /**
     * 创建新快照
     * 计算相对于 baseSnapshot 的 Patch 并创建新的 Snapshot
     *
     * @param parentSnapshotName 父快照名称（head 替换时为旧 head，release 替换时为旧 release）
     */
    private Mono<String> createNewSnapshot(String newRaw, String newContent,
                                            String baseRaw, String baseContent,
                                            String rawType, T entity,
                                            String parentSnapshotName) {
        String rawPatch = PatchUtils.diffToJsonPatch(baseRaw, newRaw);
        // 修复：HTML 为空时使用空 JSON Patch，而非 rawPatch
        String contentPatch = StringUtils.hasText(baseContent) && StringUtils.hasText(newContent)
            ? PatchUtils.diffToJsonPatch(baseContent, newContent)
            : "[]";

        Snapshot snapshot = new Snapshot();
        String newSnapshotName = entity.getMetadata().getName() + "-"
            + UUID.randomUUID().toString().substring(0, 8);

        snapshot.setMetadata(new run.halo.app.extension.Metadata());
        snapshot.getMetadata().setName(newSnapshotName);

        Snapshot.SnapShotSpec spec = new Snapshot.SnapShotSpec();
        // 使用插件名标识快照创建者
        spec.setOwner("storage-toolkit");
        spec.setParentSnapshotName(parentSnapshotName);
        spec.setRawPatch(rawPatch);
        spec.setContentPatch(contentPatch);
        spec.setLastModifyTime(Instant.now());
        spec.setRawType(StringUtils.hasText(rawType) ? rawType : "markdown");

        Ref subjectRef = new Ref();
        subjectRef.setGroup("content.halo.run");
        subjectRef.setKind(getSubjectRefKind());
        subjectRef.setName(entity.getMetadata().getName());
        spec.setSubjectRef(subjectRef);

        snapshot.setSpec(spec);
        snapshot.setKind("Snapshot");
        snapshot.setApiVersion("content.halo.run/v1alpha1");

        return client.create(snapshot)
            .map(s -> s.getMetadata().getName());
    }
}
