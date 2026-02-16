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
                        return updateEntityWithRetry(sourceName, finalNewCover, null, false,
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
                            return updateEntityWithRetry(sourceName, finalNewCover, null, false,
                                result, replaceResult);
                        }
                        return Mono.just(ReplaceResult.empty());  // 没有内容
                    }

                    // 分别替换 Markdown 和 HTML 中的 URL
                    String newRaw = currentRaw;
                    String newContent = currentContent;
                    boolean contentReplaced = false;

                    for (Map.Entry<String, String> entry : task.getUrlMapping().entrySet()) {
                        boolean urlReplaced = false;
                        if (UrlReplacer.containsUrl(newRaw, entry.getKey())) {
                            newRaw = UrlReplacer.replaceUrl(newRaw, entry.getKey(),
                                entry.getValue());
                            urlReplaced = true;
                        }
                        if (StringUtils.hasText(newContent)
                            && UrlReplacer.containsUrl(newContent, entry.getKey())) {
                            newContent = UrlReplacer.replaceUrl(newContent, entry.getKey(),
                                entry.getValue());
                            urlReplaced = true;
                        }
                        if (urlReplaced) {
                            replaceResult.addReplaced(entry.getKey());
                            contentReplaced = true;
                        }
                    }

                    if (!contentReplaced && !coverReplaced) {
                        return Mono.just(ReplaceResult.empty());  // 未找到匹配内容
                    }

                    if (contentReplaced) {
                        String originalReleaseSnapshot = getReleaseSnapshot(entity);
                        boolean wasPublished = StringUtils.hasText(originalReleaseSnapshot)
                            && originalReleaseSnapshot.equals(headSnapshotName);

                        return createNewSnapshot(newRaw, newContent, baseRaw, baseContent,
                            rawType, entity)
                            .flatMap(snapshotName ->
                                updateEntityWithRetry(sourceName, finalNewCover, snapshotName,
                                    wasPublished, result, replaceResult)
                            );
                    } else {
                        return updateEntityWithRetry(sourceName, finalNewCover, null, false,
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
     */
    private Mono<ReplaceResult> updateEntityWithRetry(String entityName, String newCover,
                                              String snapshotName, boolean wasPublished,
                                              ReferenceReplacementResult result,
                                              ReplaceResult replaceResult) {
        return Mono.defer(() -> client.fetch(getEntityClass(), entityName)
            .flatMap(latestEntity -> {
                if (newCover != null) {
                    setCover(latestEntity, newCover);
                }
                if (snapshotName != null) {
                    setHeadSnapshot(latestEntity, snapshotName);
                    if (wasPublished) {
                        setReleaseSnapshot(latestEntity, snapshotName);
                    }
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
     */
    private Mono<String> createNewSnapshot(String newRaw, String newContent,
                                            String baseRaw, String baseContent,
                                            String rawType, T entity) {
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
        spec.setParentSnapshotName(getHeadSnapshot(entity));
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
