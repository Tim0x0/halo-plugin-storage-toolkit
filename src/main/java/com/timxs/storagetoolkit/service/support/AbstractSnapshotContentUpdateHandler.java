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
            .flatMap(entity -> replaceEntity(entity, task, result))
            .onErrorResume(error -> {
                log.error("{} {} 替换失败: {}", getSourceType(), sourceName, error.getMessage());
                ReplaceResult failed = ReplaceResult.builder().build();
                task.getUrlMapping().keySet().forEach(url -> failed.addError(url, error.getMessage()));
                return Mono.just(failed);
            })
            .defaultIfEmpty(ReplaceResult.empty());
    }

    private Mono<ReplaceResult> replaceEntity(T entity, ReferenceReplacementTask task,
                                              ReferenceReplacementResult result) {
        ReplaceResult replacedUrls = ReplaceResult.builder().build();
        String cover = getCover(entity);
        String newCover = cover;
        for (var entry : task.getUrlMapping().entrySet()) {
            if (UrlReplacer.containsUrl(newCover, entry.getKey())) {
                newCover = UrlReplacer.replaceUrl(newCover, entry.getKey(), entry.getValue());
                replacedUrls.addReplaced(entry.getKey());
            }
        }
        final String changedCover = java.util.Objects.equals(cover, newCover) ? null : newCover;
        String headName = getHeadSnapshot(entity);
        String releaseName = getReleaseSnapshot(entity);
        if (!StringUtils.hasText(headName) && !StringUtils.hasText(releaseName)) {
            return changedCover != null
                ? updateEntityWithRetry(entity, changedCover, null, null, result, replacedUrls)
                : Mono.just(ReplaceResult.empty());
        }
        String baseName = StringUtils.hasText(getBaseSnapshot(entity)) ? getBaseSnapshot(entity)
            : (StringUtils.hasText(headName) ? headName : releaseName);

        return client.fetch(Snapshot.class, baseName)
            .switchIfEmpty(Mono.error(new IllegalStateException("基础快照不存在: " + baseName)))
            .flatMap(base -> {
                Mono<ContentWrapper> head = fetchSnapshotContent(headName, base).cache();
                Mono<ContentWrapper> release = java.util.Objects.equals(headName, releaseName)
                    ? head : fetchSnapshotContent(releaseName, base);
                return Mono.zip(head, release).flatMap(contents -> {
                    ReplacedContent newHead = applyUrlMapping(contents.getT1().getRaw(),
                        contents.getT1().getContent(), task.getUrlMapping(), replacedUrls);
                    boolean sameSnapshot = StringUtils.hasText(releaseName) && releaseName.equals(headName);
                    ReplacedContent newRelease = sameSnapshot ? newHead
                        : applyUrlMapping(contents.getT2().getRaw(), contents.getT2().getContent(),
                            task.getUrlMapping(), replacedUrls);
                    if (!newHead.replaced() && !newRelease.replaced() && changedCover == null) {
                        return Mono.just(ReplaceResult.empty());
                    }

                    return createReplacementSnapshot(newHead, base, entity, headName)
                        .flatMap(newHeadName -> {
                            Mono<String> releaseSnapshot = sameSnapshot ? Mono.just(newHeadName)
                                : createReplacementSnapshot(newRelease, base, entity, releaseName);
                            return releaseSnapshot.flatMap(newReleaseName -> updateEntityWithRetry(entity,
                                changedCover, StringUtils.hasText(newHeadName) ? newHeadName : null,
                                StringUtils.hasText(newReleaseName) ? newReleaseName : null,
                                result, replacedUrls));
                        });
                });
            });
    }

    private Mono<String> createReplacementSnapshot(ReplacedContent content, Snapshot base,
                                                    T entity, String parentSnapshotName) {
        if (!content.replaced()) {
            // An empty name means the entity must retain its existing snapshot pointer.
            return Mono.just("");
        }
        return createNewSnapshot(content.raw(), content.content(), base.getSpec().getRawPatch(),
            base.getSpec().getContentPatch(), base.getSpec().getRawType(), entity, parentSnapshotName);
    }

    private Mono<ReplaceResult> updateEntityWithRetry(T original, String newCover,
                                                      String headSnapshotName, String releaseSnapshotName,
                                                      ReferenceReplacementResult result, ReplaceResult replacedUrls) {
        String name = original.getMetadata().getName();
        return Mono.defer(() -> client.fetch(getEntityClass(), name)
            .switchIfEmpty(Mono.error(new IllegalStateException("内容不存在: " + name)))
            .flatMap(latest -> {
                // A concurrent editor must not lose a newer draft or publication to our old snapshot.
                if (!java.util.Objects.equals(getHeadSnapshot(original), getHeadSnapshot(latest))
                    || !java.util.Objects.equals(getReleaseSnapshot(original), getReleaseSnapshot(latest))
                    || !java.util.Objects.equals(getBaseSnapshot(original), getBaseSnapshot(latest))
                    || (newCover != null && !java.util.Objects.equals(getCover(original), getCover(latest)))) {
                    return Mono.error(new IllegalStateException("内容已被修改，请重试引用替换: " + name));
                }
                if (newCover != null) setCover(latest, newCover);
                if (headSnapshotName != null) setHeadSnapshot(latest, headSnapshotName);
                if (releaseSnapshotName != null) setReleaseSnapshot(latest, releaseSnapshotName);
                return client.update(latest);
            }))
            .retryWhen(RetryUtils.optimisticLockRetry())
            .doOnSuccess(value -> result.incrementTypeCount(getSourceType()))
            .thenReturn(replacedUrls);
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

    private Mono<ContentWrapper> fetchSnapshotContent(String snapshotName, Snapshot base) {
        if (!StringUtils.hasText(snapshotName)) {
            return Mono.just(ContentWrapper.builder().raw("").content("").build());
        }
        if (snapshotName.equals(base.getMetadata().getName())) {
            return Mono.just(ContentWrapper.patchSnapshot(base, base));
        }
        return client.fetch(Snapshot.class, snapshotName)
            .switchIfEmpty(Mono.error(new IllegalStateException("内容快照不存在: " + snapshotName)))
            .map(snapshot -> ContentWrapper.patchSnapshot(snapshot, base));
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
        String rawPatch = PatchUtils.diffToJsonPatch(baseRaw != null ? baseRaw : "", newRaw != null ? newRaw : "");
        String contentPatch = PatchUtils.diffToJsonPatch(baseContent != null ? baseContent : "",
            newContent != null ? newContent : "");

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
