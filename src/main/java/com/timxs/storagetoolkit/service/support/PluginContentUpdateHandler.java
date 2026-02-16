package com.timxs.storagetoolkit.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.timxs.storagetoolkit.model.ReferenceReplacementResult;
import com.timxs.storagetoolkit.model.ReferenceReplacementTask;
import com.timxs.storagetoolkit.model.ReplaceResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import run.halo.app.content.ContentWrapper;
import run.halo.app.core.extension.content.Snapshot;
import run.halo.app.extension.Extension;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.infra.utils.JsonUtils;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 插件类型内容更新处理器
 * 处理 Moment、Photo、Doc 等插件类型的 URL 替换
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PluginContentUpdateHandler implements ContentUpdateHandler {

    private final ReactiveExtensionClient client;
    private final SchemeManager schemeManager;

    private static final ObjectMapper objectMapper = JsonUtils.mapper();

    // 插件 GVK 定义
    private static final GroupVersionKind MOMENT_GVK =
        new GroupVersionKind("moment.halo.run", "v1alpha1", "Moment");
    private static final GroupVersionKind PHOTO_GVK =
        new GroupVersionKind("core.halo.run", "v1alpha1", "Photo");
    private static final GroupVersionKind DOC_GVK =
        new GroupVersionKind("doc.halo.run", "v1alpha1", "Doc");
    private static final GroupVersionKind PROJECT_GVK =
        new GroupVersionKind("doc.halo.run", "v1alpha1", "Project");

    @Override
    public String getSourceType() {
        return "Plugin";
    }

    @Override
    public boolean supports(String sourceType) {
        return "Moment".equals(sourceType) ||
               "Photo".equals(sourceType) ||
               "Doc".equals(sourceType);
    }

    @Override
    public Mono<ReplaceResult> replaceUrls(String sourceName, ReferenceReplacementTask task, ReferenceReplacementResult result) {
        // 根据 sourceType 确定具体的插件类型
        // 这里 sourceName 格式应该是 "type:name"，但我们通过调用方传入的 sourceType 来识别
        return Mono.just(ReplaceResult.empty());
    }

    /**
     * 替换指定类型的插件内容
     *
     * @param sourceType 源类型（Moment/Photo/Doc）
     * @param sourceName 源名称
     * @param task 替换任务
     * @param result 结果
     * @return Mono<ReplaceResult> 替换结果
     */
    public Mono<ReplaceResult> replacePluginUrls(String sourceType, String sourceName,
                                         ReferenceReplacementTask task, ReferenceReplacementResult result) {
        return switch (sourceType) {
            case "Moment" -> replaceMomentUrls(sourceName, task, result);
            case "Photo" -> replacePhotoUrls(sourceName, task, result);
            case "Doc" -> replaceDocUrls(sourceName, task, result);
            default -> Mono.just(ReplaceResult.empty());
        };
    }

    /**
     * 替换 Moment 内容中的 URL
     */
    private Mono<ReplaceResult> replaceMomentUrls(String sourceName, ReferenceReplacementTask task, ReferenceReplacementResult result) {
        var schemeOpt = schemeManager.fetch(MOMENT_GVK);
        if (schemeOpt.isEmpty()) {
            log.debug("瞬间插件未安装，跳过处理");
            return Mono.just(ReplaceResult.empty());
        }

        return fetchAndUpdateExtension(schemeOpt.get(), sourceName, "Moment", task, result,
            (jsonNode, urlMapping) -> {
                Set<String> replacedUrls = new HashSet<>();
                JsonNode specNode = jsonNode.get("spec");
                if (specNode == null) return replacedUrls;

                JsonNode contentNode = specNode.get("content");
                if (contentNode != null) {
                    // 处理 HTML 内容
                    JsonNode htmlNode = contentNode.get("html");
                    if (htmlNode != null && htmlNode.isTextual()) {
                        String html = htmlNode.asText();
                        String newHtml = html;
                        for (Map.Entry<String, String> entry : urlMapping.entrySet()) {
                            if (UrlReplacer.containsUrl(html, entry.getKey())) {
                                newHtml = UrlReplacer.replaceUrl(newHtml, entry.getKey(), entry.getValue());
                                replacedUrls.add(entry.getKey());
                            }
                        }
                        if (!newHtml.equals(html)) {
                            ((ObjectNode) contentNode).put("html", newHtml);
                        }
                    }

                    // 处理媒体文件
                    JsonNode mediumNode = contentNode.get("medium");
                    if (mediumNode != null && mediumNode.isArray()) {
                        ArrayNode newMedium = objectMapper.createArrayNode();
                        for (JsonNode mediaItem : mediumNode) {
                            if (mediaItem.isObject()) {
                                ObjectNode newItem = (ObjectNode) mediaItem;
                                JsonNode urlNode = mediaItem.get("url");
                                if (urlNode != null && urlNode.isTextual()) {
                                    String url = urlNode.asText();
                                    String newUrl = url;
                                    for (Map.Entry<String, String> entry : urlMapping.entrySet()) {
                                        if (UrlReplacer.containsUrl(url, entry.getKey())) {
                                            newUrl = UrlReplacer.replaceUrl(newUrl, entry.getKey(), entry.getValue());
                                            replacedUrls.add(entry.getKey());
                                        }
                                    }
                                    newItem.put("url", newUrl);
                                }
                                newMedium.add(newItem);
                            } else {
                                newMedium.add(mediaItem);
                            }
                        }
                        ((ObjectNode) contentNode).set("medium", newMedium);
                    }
                }
                return replacedUrls;
            });
    }

    /**
     * 替换 Photo 内容中的 URL
     */
    private Mono<ReplaceResult> replacePhotoUrls(String sourceName, ReferenceReplacementTask task, ReferenceReplacementResult result) {
        var schemeOpt = schemeManager.fetch(PHOTO_GVK);
        if (schemeOpt.isEmpty()) {
            log.debug("图库插件未安装，跳过处理");
            return Mono.just(ReplaceResult.empty());
        }

        return fetchAndUpdateExtension(schemeOpt.get(), sourceName, "Photo", task, result,
            (jsonNode, urlMapping) -> {
                Set<String> replacedUrls = new HashSet<>();
                JsonNode specNode = jsonNode.get("spec");
                if (specNode == null) return replacedUrls;

                // 处理 url 字段
                JsonNode urlNode = specNode.get("url");
                if (urlNode != null && urlNode.isTextual()) {
                    String url = urlNode.asText();
                    for (Map.Entry<String, String> entry : urlMapping.entrySet()) {
                        if (UrlReplacer.containsUrl(url, entry.getKey())) {
                            String newUrl = UrlReplacer.replaceUrl(url, entry.getKey(), entry.getValue());
                            ((ObjectNode) specNode).put("url", newUrl);
                            replacedUrls.add(entry.getKey());
                            break;
                        }
                    }
                }

                // 处理 cover 字段
                JsonNode coverNode = specNode.get("cover");
                if (coverNode != null && coverNode.isTextual()) {
                    String cover = coverNode.asText();
                    for (Map.Entry<String, String> entry : urlMapping.entrySet()) {
                        if (UrlReplacer.containsUrl(cover, entry.getKey())) {
                            String newCover = UrlReplacer.replaceUrl(cover, entry.getKey(), entry.getValue());
                            ((ObjectNode) specNode).put("cover", newCover);
                            replacedUrls.add(entry.getKey());
                            break;
                        }
                    }
                }

                return replacedUrls;
            });
    }

    /**
     * 替换 Doc 内容中的 URL
     * Doc 使用 Snapshot 但结构与 Post/SinglePage 不同，没有 baseSnapshot 字段
     */
    private Mono<ReplaceResult> replaceDocUrls(String sourceName, ReferenceReplacementTask task, ReferenceReplacementResult result) {
        var docSchemeOpt = schemeManager.fetch(DOC_GVK);
        var projectSchemeOpt = schemeManager.fetch(PROJECT_GVK);

        if (docSchemeOpt.isEmpty() && projectSchemeOpt.isEmpty()) {
            log.debug("文档插件未安装，跳过处理");
            return Mono.just(ReplaceResult.empty());
        }

        // 尝试作为 Doc 处理（使用 Snapshot 机制）
        Mono<ReplaceResult> updateDoc = Mono.just(ReplaceResult.empty());
        if (docSchemeOpt.isPresent()) {
            updateDoc = replaceDocContentUrls(docSchemeOpt.get(), sourceName, task, result);
        }

        // 尝试作为 Project 处理（图标替换）
        Mono<ReplaceResult> updateProject = Mono.just(ReplaceResult.empty());
        if (projectSchemeOpt.isPresent()) {
            updateProject = fetchAndUpdateExtension(projectSchemeOpt.get(), sourceName, "Doc", task, result,
                (jsonNode, urlMapping) -> {
                    Set<String> replacedUrls = new HashSet<>();
                    JsonNode specNode = jsonNode.get("spec");
                    if (specNode == null) return replacedUrls;

                    JsonNode iconNode = specNode.get("icon");
                    if (iconNode != null && iconNode.isTextual()) {
                        String icon = iconNode.asText();
                        for (Map.Entry<String, String> entry : urlMapping.entrySet()) {
                            if (UrlReplacer.containsUrl(icon, entry.getKey())) {
                                String newIcon = UrlReplacer.replaceUrl(icon, entry.getKey(), entry.getValue());
                                ((ObjectNode) specNode).put("icon", newIcon);
                                replacedUrls.add(entry.getKey());
                                break;
                            }
                        }
                    }
                    return replacedUrls;
                });
        }

        // 合并两个结果
        return updateDoc.zipWith(updateProject, (docResult, projectResult) -> {
            ReplaceResult merged = ReplaceResult.builder().build();
            merged.getReplaced().addAll(docResult.getReplaced());
            merged.getReplaced().addAll(projectResult.getReplaced());
            merged.getErrors().putAll(docResult.getErrors());
            merged.getErrors().putAll(projectResult.getErrors());
            return merged;
        });
    }

    /**
     * 替换 Doc 内容
     * Doc 插件使用 Snapshot 但结构与 Post/SinglePage 不同：
     * - Doc 没有 baseSnapshot 字段
     * - Doc 使用 releaseSnapshot 或 headSnapshot 作为基准
     * - Doc 的 Snapshot 存储完整内容，不使用差异 patch
     *
     * 参考 ReferenceServiceImpl.scanDocs() 的扫描逻辑
     */
    private Mono<ReplaceResult> replaceDocContentUrls(Scheme docScheme, String sourceName,
                                              ReferenceReplacementTask task, ReferenceReplacementResult result) {
        return client.fetch(docScheme.type(), sourceName)
            .flatMap(ext -> {
                try {
                    String json = objectMapper.writeValueAsString(ext);
                    JsonNode rootNode = objectMapper.readTree(json);
                    JsonNode specNode = rootNode.get("spec");

                    if (specNode == null) {
                        return Mono.just(ReplaceResult.empty());
                    }

                    String headSnapshotName = specNode.has("headSnapshot")
                        ? specNode.get("headSnapshot").asText() : null;
                    String releaseSnapshotName = specNode.has("releaseSnapshot")
                        ? specNode.get("releaseSnapshot").asText() : null;

                    log.debug("Doc {} 快照信息: headSnapshot={}, releaseSnapshot={}",
                        sourceName, headSnapshotName, releaseSnapshotName);

                    if (!StringUtils.hasText(headSnapshotName)) {
                        return Mono.just(ReplaceResult.empty());
                    }

                    // Doc 使用 releaseSnapshot 或 headSnapshot 作为基准（与扫描逻辑一致）
                    // Doc 没有 baseSnapshot 字段
                    String baseSnapshotName = StringUtils.hasText(releaseSnapshotName)
                        ? releaseSnapshotName : headSnapshotName;

                    log.debug("Doc {} 使用 {} 作为基准快照", sourceName, baseSnapshotName);

                    // 获取 headSnapshot 的完整内容
                    return fetchDocContent(headSnapshotName, baseSnapshotName)
                        .flatMap(contentWrapper -> {
                            String currentRaw = contentWrapper.getRaw();
                            String currentContent = contentWrapper.getContent();

                            if (!StringUtils.hasText(currentRaw) && !StringUtils.hasText(currentContent)) {
                                return Mono.just(ReplaceResult.empty());
                            }

                            // 替换 URL
                            String newRaw = currentRaw != null ? currentRaw : "";
                            String newContent = currentContent != null ? currentContent : "";
                            ReplaceResult replaceResult = ReplaceResult.builder().build();

                            for (Map.Entry<String, String> entry : task.getUrlMapping().entrySet()) {
                                boolean urlReplaced = false;
                                if (StringUtils.hasText(newRaw) && UrlReplacer.containsUrl(newRaw, entry.getKey())) {
                                    newRaw = UrlReplacer.replaceUrl(newRaw, entry.getKey(), entry.getValue());
                                    urlReplaced = true;
                                }
                                if (StringUtils.hasText(newContent) && UrlReplacer.containsUrl(newContent, entry.getKey())) {
                                    newContent = UrlReplacer.replaceUrl(newContent, entry.getKey(), entry.getValue());
                                    urlReplaced = true;
                                }
                                if (urlReplaced) {
                                    replaceResult.addReplaced(entry.getKey());
                                }
                            }

                            if (!replaceResult.hasReplaced()) {
                                return Mono.just(ReplaceResult.empty());
                            }

                            // 直接更新 headSnapshot 的内容（Doc 存储完整内容，不使用差异 patch）
                            final String finalNewRaw = newRaw;
                            final String finalNewContent = newContent;
                            return updateDocSnapshot(headSnapshotName, finalNewRaw, finalNewContent)
                                .thenReturn(replaceResult)
                                .doOnSuccess(v -> {
                                    log.debug("Doc {} 引用替换成功，替换了 {} 个 URL", sourceName, replaceResult.getReplaced().size());
                                    result.incrementTypeCount("Doc");
                                });
                        });
                } catch (Exception e) {
                    log.error("处理 Doc {} 失败: {}", sourceName, e.getMessage());
                    ReplaceResult errorResult = ReplaceResult.builder().build();
                    for (Map.Entry<String, String> entry : task.getUrlMapping().entrySet()) {
                        errorResult.addError(entry.getKey(), e.getMessage());
                    }
                    return Mono.just(errorResult);
                }
            })
            .retryWhen(RetryUtils.optimisticLockRetry())
            .onErrorResume(e -> {
                log.error("Doc {} 替换失败（重试耗尽）: {}", sourceName, e.getMessage());
                ReplaceResult errorResult = ReplaceResult.builder().build();
                for (Map.Entry<String, String> entry : task.getUrlMapping().entrySet()) {
                    errorResult.addError(entry.getKey(), e.getMessage());
                }
                return Mono.just(errorResult);
            })
            .defaultIfEmpty(ReplaceResult.empty());
    }

    /**
     * 获取 Doc 内容（与扫描逻辑一致）
     */
    private Mono<ContentWrapper> fetchDocContent(String headSnapshotName, String baseSnapshotName) {
        return client.fetch(Snapshot.class, baseSnapshotName)
            .flatMap(baseSnapshot -> {
                if (headSnapshotName.equals(baseSnapshotName)) {
                    return Mono.just(ContentWrapper.patchSnapshot(baseSnapshot, baseSnapshot));
                }
                return client.fetch(Snapshot.class, headSnapshotName)
                    .map(headSnapshot -> ContentWrapper.patchSnapshot(headSnapshot, baseSnapshot));
            });
    }

    /**
     * 更新 Doc 的 Snapshot 内容
     * Doc 的 Snapshot 存储完整内容，直接更新 rawPatch 和 contentPatch
     */
    private Mono<Void> updateDocSnapshot(String snapshotName, String newRaw, String newContent) {
        return client.fetch(Snapshot.class, snapshotName)
            .flatMap(snapshot -> {
                snapshot.getSpec().setRawPatch(newRaw);
                snapshot.getSpec().setContentPatch(newContent);
                snapshot.getSpec().setLastModifyTime(Instant.now());
                return client.update(snapshot);
            })
            .then();
    }

    /**
     * 通用的扩展更新方法
     */
    private Mono<ReplaceResult> fetchAndUpdateExtension(Scheme scheme, String sourceName, String typeName,
                                               ReferenceReplacementTask task, ReferenceReplacementResult result,
                                               ExtensionUpdater updater) {
        return client.fetch(scheme.type(), sourceName)
            .flatMap(ext -> {
                try {
                    String json = objectMapper.writeValueAsString(ext);
                    JsonNode rootNode = objectMapper.readTree(json);

                    Set<String> replacedUrls = updater.update(rootNode, task.getUrlMapping());

                    if (replacedUrls.isEmpty()) {
                        return Mono.just(ReplaceResult.empty());
                    }

                    ReplaceResult replaceResult = ReplaceResult.builder().build();
                    replacedUrls.forEach(replaceResult::addReplaced);

                    // 将 JSON 转回 Extension 对象
                    Extension updatedExt = (Extension) objectMapper.treeToValue(rootNode, scheme.type());

                    // 保留 metadata
                    updatedExt.setMetadata(ext.getMetadata());

                    return client.update(updatedExt)
                        .thenReturn(replaceResult)
                        .doOnSuccess(v -> {
                            log.debug("{} {} 引用替换成功，替换了 {} 个 URL", typeName, sourceName, replaceResult.getReplaced().size());
                            result.incrementTypeCount(typeName);
                        });
                } catch (Exception e) {
                    log.error("处理 {} {} 失败: {}", typeName, sourceName, e.getMessage());
                    ReplaceResult errorResult = ReplaceResult.builder().build();
                    for (Map.Entry<String, String> entry : task.getUrlMapping().entrySet()) {
                        errorResult.addError(entry.getKey(), e.getMessage());
                    }
                    return Mono.just(errorResult);
                }
            })
            .retryWhen(RetryUtils.optimisticLockRetry())
            .onErrorResume(e -> {
                log.error("{} {} 替换失败（重试耗尽）: {}", typeName, sourceName, e.getMessage());
                ReplaceResult errorResult = ReplaceResult.builder().build();
                for (Map.Entry<String, String> entry : task.getUrlMapping().entrySet()) {
                    errorResult.addError(entry.getKey(), e.getMessage());
                }
                return Mono.just(errorResult);
            })
            .defaultIfEmpty(ReplaceResult.empty());
    }

    /**
     * 获取所有插件类型的源名称
     */
    public Mono<Set<String>> getAllMomentNames() {
        var schemeOpt = schemeManager.fetch(MOMENT_GVK);
        if (schemeOpt.isEmpty()) {
            return Mono.just(Set.of());
        }
        return client.listAll(schemeOpt.get().type(), ListOptions.builder().build(), Sort.unsorted())
            .map(ext -> ext.getMetadata().getName())
            .collect(Collectors.toSet());
    }

    public Mono<Set<String>> getAllPhotoNames() {
        var schemeOpt = schemeManager.fetch(PHOTO_GVK);
        if (schemeOpt.isEmpty()) {
            return Mono.just(Set.of());
        }
        return client.listAll(schemeOpt.get().type(), ListOptions.builder().build(), Sort.unsorted())
            .map(ext -> ext.getMetadata().getName())
            .collect(Collectors.toSet());
    }

    public Mono<Set<String>> getAllDocNames() {
        var schemeOpt = schemeManager.fetch(DOC_GVK);
        if (schemeOpt.isEmpty()) {
            return Mono.just(Set.of());
        }
        return client.listAll(schemeOpt.get().type(), ListOptions.builder().build(), Sort.unsorted())
            .map(ext -> ext.getMetadata().getName())
            .collect(Collectors.toSet());
    }

    public Mono<Set<String>> getAllProjectNames() {
        var schemeOpt = schemeManager.fetch(PROJECT_GVK);
        if (schemeOpt.isEmpty()) {
            return Mono.just(Set.of());
        }
        return client.listAll(schemeOpt.get().type(), ListOptions.builder().build(), Sort.unsorted())
            .map(ext -> ext.getMetadata().getName())
            .collect(Collectors.toSet());
    }

    /**
     * 扩展更新器接口
     */
    @FunctionalInterface
    private interface ExtensionUpdater {
        Set<String> update(JsonNode rootNode, Map<String, String> urlMapping);
    }
}
