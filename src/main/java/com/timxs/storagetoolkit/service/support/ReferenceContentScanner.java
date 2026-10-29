package com.timxs.storagetoolkit.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.timxs.storagetoolkit.extension.AttachmentReference;
import com.timxs.storagetoolkit.service.ContentScanner;
import com.timxs.storagetoolkit.service.SettingsManager.AnalysisSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.content.ContentWrapper;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.*;
import run.halo.app.core.extension.content.*;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.SchemeManager;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.app.infra.utils.JsonUtils;

import java.util.*;

/** Reads current content references for analysis, replacement and deletion checks. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReferenceContentScanner {
    private final ReactiveExtensionClient client;
    private final ContentScanner contentScanner;
    private final PostContentService postContentService;
    private final SchemeManager schemeManager;
    private final ExternalLinkProcessor externalLinkProcessor;

    private static final com.fasterxml.jackson.databind.ObjectMapper objectMapper = JsonUtils.mapper();

    // 瞬间插件 GVK (moment.halo.run/v1alpha1/Moment)
    private static final GroupVersionKind MOMENT_GVK =
        new GroupVersionKind("moment.halo.run", "v1alpha1", "Moment");
    // 图库插件 GVK (core.halo.run/v1alpha1/Photo)
    private static final GroupVersionKind PHOTO_GVK =
        new GroupVersionKind("core.halo.run", "v1alpha1", "Photo");
    // Docsme 文档插件 GVK (doc.halo.run/v1alpha1/Doc)
    private static final GroupVersionKind DOC_GVK =
        new GroupVersionKind("doc.halo.run", "v1alpha1", "Doc");
    // Docsme 文档项目 GVK (doc.halo.run/v1alpha1/Project)
    private static final GroupVersionKind PROJECT_GVK =
        new GroupVersionKind("doc.halo.run", "v1alpha1", "Project");
    // DocTree GVK (doc.halo.run/v1alpha1/DocTree)
    private static final GroupVersionKind DOC_TREE_GVK =
        new GroupVersionKind("doc.halo.run", "v1alpha1", "DocTree");
    // Docsme 文档版本 GVK (doc.halo.run/v1alpha1/ProjectVersion)
    private static final GroupVersionKind PROJECT_VERSION_GVK =
        new GroupVersionKind("doc.halo.run", "v1alpha1", "ProjectVersion");
    // 互动增强插件 GVK (interaction-plus.timxs.com/v1alpha1/UserDecorationAsset)
    private static final GroupVersionKind DECORATION_ASSET_GVK =
        new GroupVersionKind("interaction-plus.timxs.com", "v1alpha1", "UserDecorationAsset");
    // 互动增强 身份标识映射 GVK (interaction-plus.timxs.com/v1alpha1/UserIdentityMarkMapping)
    private static final GroupVersionKind IDENTITY_MARK_GVK =
        new GroupVersionKind("interaction-plus.timxs.com", "v1alpha1", "UserIdentityMarkMapping");

    public Mono<ReferenceScanContext> scanAll() {
        // Destructive operations must also check sources excluded from the analysis view.
        return scan(new AnalysisSettings(true, true, true, true, true, true, true));
    }

    public Mono<ReferenceScanContext> scan(AnalysisSettings settings) {
        return Mono.defer(() -> {
            ReferenceScanContext context = new ReferenceScanContext(externalLinkProcessor, contentScanner);
            List<Mono<Void>> tasks = new ArrayList<>();
            if (settings.scanPosts()) tasks.add(scanPosts(context));
            if (settings.scanPages()) tasks.add(scanSinglePages(context));
            if (settings.scanComments()) {
                tasks.add(scanComments(context));
                tasks.add(scanReplies(context));
            }
            if (settings.scanMoments()) tasks.add(scanMoments(context));
            if (settings.scanPhotos()) tasks.add(scanPhotos(context));
            if (settings.scanDocs()) tasks.add(scanDocs(context));
            if (settings.scanInteractions()) tasks.add(scanInteractions(context));
            tasks.add(scanConfigMaps(context));
            tasks.add(scanUserAvatars(context));
            return Flux.concat(tasks).then(Mono.just(context));
        });
    }

    /**
     * 扫描文章
     */
    private Mono<Void> scanPosts(ReferenceScanContext context) {
        log.debug("开始扫描 文章...");
        return client.listAll(Post.class, ListOptions.builder().build(), Sort.unsorted())
            .flatMap(post -> {
                String postName = post.getMetadata().getName();
                String postTitle = post.getSpec().getTitle();
                // 仅使用 status.permalink，避免硬编码主题路由
                String postPermalink = post.getStatus() != null ? post.getStatus().getPermalink() : null;
                String postUrl = StringUtils.hasText(postPermalink) ? postPermalink : null;
                // 检查是否在回收站
                boolean isDeleted = post.getSpec().getDeleted() != null && post.getSpec().getDeleted();
                // 检查是否为草稿（headSnapshot != releaseSnapshot）
                String headSnapshot = post.getSpec().getHeadSnapshot();
                String releaseSnapshot = post.getSpec().getReleaseSnapshot();
                boolean isDraft = !StringUtils.hasText(releaseSnapshot)
                    || !releaseSnapshot.equals(headSnapshot);
                // 内容类型：草稿用 draft，已发布用 content
                String contentType = isDraft ? "draft" : "content";

                // 扫描封面图（封面没有草稿概念）
                String cover = post.getSpec().getCover();
                if (StringUtils.hasText(cover)) {
                    AttachmentReference.ReferenceSource coverSource = createSource(
                        "Post", postName, postTitle, postUrl, isDeleted, "cover");
                    context.addUrl(cover, coverSource);
                }

                Mono<Void> scanHead = StringUtils.hasText(headSnapshot)
                    ? scanSnapshotContent(postContentService.getHeadContent(postName),
                        createSource("Post", postName, postTitle, postUrl, isDeleted, contentType), context)
                    : Mono.empty();
                Mono<Void> scanRelease = StringUtils.hasText(releaseSnapshot) && !releaseSnapshot.equals(headSnapshot)
                    ? scanSnapshotContent(postContentService.getReleaseContent(postName),
                        createSource("Post", postName, postTitle, postUrl, isDeleted, "content"), context)
                    : Mono.empty();
                return scanHead.then(scanRelease).thenReturn(1);
            })
            .count()
            .doOnNext(count -> log.debug("文章 扫描完成，共扫描 {} 条记录", count))
            .then();
    }

    /**
     * 扫描独立页面
     */
    private Mono<Void> scanSinglePages(ReferenceScanContext context) {
        log.debug("开始扫描 独立页面...");
        return client.listAll(SinglePage.class, ListOptions.builder().build(), Sort.unsorted())
            .flatMap(page -> {
                String pageName = page.getMetadata().getName();
                String pageTitle = page.getSpec().getTitle();
                // 优先从 status.permalink 获取，为空时 fallback 到 slug 拼接
                String pagePermalink = page.getStatusOrDefault().getPermalink();
                String pageUrl = StringUtils.hasText(pagePermalink) ? pagePermalink : "/" + page.getSpec().getSlug();
                // 检查是否在回收站
                boolean isDeleted = page.getSpec().getDeleted() != null && page.getSpec().getDeleted();
                // 检查是否为草稿（headSnapshot != releaseSnapshot）
                String headSnapshot = page.getSpec().getHeadSnapshot();
                String releaseSnapshot = page.getSpec().getReleaseSnapshot();
                boolean isDraft = !StringUtils.hasText(releaseSnapshot)
                    || !releaseSnapshot.equals(headSnapshot);
                // 内容类型：草稿用 draft，已发布用 content
                String contentType = isDraft ? "draft" : "content";

                // 扫描封面图（封面没有草稿概念）
                String cover = page.getSpec().getCover();
                if (StringUtils.hasText(cover)) {
                    AttachmentReference.ReferenceSource coverSource = createSource(
                        "SinglePage", pageName, pageTitle, pageUrl, isDeleted, "cover");
                    context.addUrl(cover, coverSource);
                }

                String baseSnapshot = page.getSpec().getBaseSnapshot();
                Mono<Void> scanHead = StringUtils.hasText(headSnapshot)
                    ? scanSnapshotContent(getSinglePageContent(headSnapshot, baseSnapshot),
                        createSource("SinglePage", pageName, pageTitle, pageUrl, isDeleted, contentType), context)
                    : Mono.empty();
                Mono<Void> scanRelease = StringUtils.hasText(releaseSnapshot) && !releaseSnapshot.equals(headSnapshot)
                    ? scanSnapshotContent(getSinglePageContent(releaseSnapshot, baseSnapshot),
                        createSource("SinglePage", pageName, pageTitle, pageUrl, isDeleted, "content"), context)
                    : Mono.empty();
                return scanHead.then(scanRelease).thenReturn(1);
            })
            .count()
            .doOnNext(count -> log.debug("独立页面 扫描完成，共扫描 {} 条记录", count))
            .then();
    }

    /**
     * 获取独立页面内容（合并 Snapshot）
     */
    private Mono<ContentWrapper> getSinglePageContent(String snapshotName, String baseSnapshotName) {
        String baseName = StringUtils.hasText(baseSnapshotName) ? baseSnapshotName : snapshotName;
        return client.fetch(Snapshot.class, baseName)
            .switchIfEmpty(Mono.error(new IllegalStateException("缺少基础快照: " + baseName)))
            .flatMap(baseSnapshot -> {
                if (snapshotName.equals(baseName)) {
                    return Mono.just(ContentWrapper.patchSnapshot(baseSnapshot, baseSnapshot));
                }
                return client.fetch(Snapshot.class, snapshotName)
                    .switchIfEmpty(Mono.error(new IllegalStateException("缺少内容快照: " + snapshotName)))
                    .map(snapshot -> ContentWrapper.patchSnapshot(snapshot, baseSnapshot));
            });
    }

    private Mono<Void> scanSnapshotContent(Mono<ContentWrapper> content,
                                           AttachmentReference.ReferenceSource source,
                                           ReferenceScanContext context) {
        return content
            .switchIfEmpty(Mono.error(new IllegalStateException(
                "无法读取内容: " + source.getSourceType() + "/" + source.getSourceName())))
            .doOnNext(wrapper -> {
                context.addExtractResult(contentScanner.extractUrlsFromHtml(wrapper.getContent()), source);
                context.addExtractResult(contentScanner.extractUrlsWithType(wrapper.getRaw()), source);
            })
            .then();
    }

    /**
     * 扫描评论
     */
    private Mono<Void> scanComments(ReferenceScanContext context) {
        log.debug("开始扫描 评论...");
        return client.listAll(Comment.class, ListOptions.builder().build(), Sort.unsorted())
            .doOnNext(comment -> {
                String commentName = comment.getMetadata().getName();
                // 使用 getContent() 获取渲染后的 HTML 内容
                String content = comment.getSpec().getContent();

                if (!StringUtils.hasText(content)) {
                    return;
                }

                // 获取评论关联的文章/页面信息，存储 kind:name 格式，详情弹窗再查询标题
                var subjectRef = comment.getSpec().getSubjectRef();
                String sourceTitle = "评论";
                if (subjectRef != null) {
                    sourceTitle = subjectRef.getKind() + ":" + subjectRef.getName();
                }

                AttachmentReference.ReferenceSource source = createSource(
                    "Comment", commentName, sourceTitle, null, false, "comment");
                ContentScanner.ExtractResult result = contentScanner.extractUrlsFromHtml(content);
                context.addExtractResult(result, source);
            })
            .count()
            .doOnNext(count -> log.debug("评论 扫描完成，共扫描 {} 条记录", count))
            .then();
    }

    /**
     * 扫描回复
     */
    private Mono<Void> scanReplies(ReferenceScanContext context) {
        log.debug("开始扫描 回复...");
        return client.listAll(Reply.class, ListOptions.builder().build(), Sort.unsorted())
            .doOnNext(reply -> {
                String replyName = reply.getMetadata().getName();
                // 使用 getContent() 获取渲染后的 HTML 内容
                String content = reply.getSpec().getContent();

                if (!StringUtils.hasText(content)) {
                    return;
                }

                // 存储 Comment:comment-name 格式，详情弹窗再追溯查询
                String commentName = reply.getSpec().getCommentName();
                String sourceTitle = StringUtils.hasText(commentName)
                    ? "Comment:" + commentName
                    : "回复";

                AttachmentReference.ReferenceSource source = createSource(
                    "Reply", replyName, sourceTitle, null, false, "reply");
                ContentScanner.ExtractResult result = contentScanner.extractUrlsFromHtml(content);
                context.addExtractResult(result, source);
            })
            .count()
            .doOnNext(count -> log.debug("回复 扫描完成，共扫描 {} 条记录", count))
            .then();
    }

    /**
     * 扫描系统配置、插件配置和主题配置
     * 分别扫描系统设置、所有插件设置、所有主题设置的 ConfigMap
     */
    private Mono<Void> scanConfigMaps(ReferenceScanContext context) {
        // 1. 扫描系统设置
        log.debug("开始扫描 系统设置...");
        Mono<Void> scanSystem = client.fetch(ConfigMap.class, "system")
            .doOnNext(configMap -> {
                scanConfigMapData(configMap, "SystemSetting", "系统设置", "system",
                    groupKey -> "/console/settings?tab=" + groupKey,
                    context);
            })
            .doOnSuccess(v -> log.debug("系统设置 扫描完成，共扫描 1 条记录"))
            .then();

        // 2. 扫描所有插件设置
        Mono<Void> scanPlugins = Mono.defer(() -> {
            log.debug("开始扫描 插件设置...");
            return client.listAll(Plugin.class, ListOptions.builder().build(), Sort.unsorted())
                .filter(plugin -> StringUtils.hasText(plugin.getSpec().getConfigMapName()))
                .flatMap(plugin -> {
                    String pluginName = plugin.getMetadata().getName();
                    String displayName = plugin.getSpec().getDisplayName();
                    String configMapName = plugin.getSpec().getConfigMapName();
                    String settingName = plugin.getSpec().getSettingName();
                    String sourceTitle = (StringUtils.hasText(displayName) ? displayName : pluginName) + " 插件设置";

                    return client.fetch(ConfigMap.class, configMapName)
                        .doOnNext(configMap -> {
                            scanConfigMapData(configMap, "PluginSetting", sourceTitle, settingName,
                                groupKey -> "/console/plugins/" + pluginName + "?tab=" + groupKey,
                                context);
                        })
                        .thenReturn(1);
                })
                .count()
                .doOnNext(count -> log.debug("插件设置 扫描完成，共扫描 {} 条记录", count))
                .then();
        });

        // 3. 扫描所有主题设置
        Mono<Void> scanThemes = Mono.defer(() -> {
            log.debug("开始扫描 主题设置...");
            return client.listAll(Theme.class, ListOptions.builder().build(), Sort.unsorted())
                .filter(theme -> StringUtils.hasText(theme.getSpec().getConfigMapName()))
                .flatMap(theme -> {
                    String themeName = theme.getMetadata().getName();
                    String displayName = theme.getSpec().getDisplayName();
                    String configMapName = theme.getSpec().getConfigMapName();
                    String settingName = theme.getSpec().getSettingName();
                    String sourceTitle = (StringUtils.hasText(displayName) ? displayName : themeName) + " 主题设置";

                    return client.fetch(ConfigMap.class, configMapName)
                        .doOnNext(configMap -> {
                            scanConfigMapData(configMap, "ThemeSetting", sourceTitle, settingName,
                                groupKey -> "/console/theme/settings/" + groupKey,
                                context);
                        })
                        .thenReturn(1);
                })
                .count()
                .doOnNext(count -> log.debug("主题设置 扫描完成，共扫描 {} 条记录", count))
                .then();
        });

        return scanSystem.then(scanPlugins).then(scanThemes);
    }

    /**
     * 扫描 ConfigMap 数据
     * @param configMap ConfigMap 对象
     * @param sourceType 来源类型（SystemSetting/PluginSetting/ThemeSetting）
     * @param sourceTitle 显示标题
     * @param settingName Setting 名称（用于异步查询 label）
     * @param urlBuilder URL 构建函数，接收 groupKey 返回完整 URL
     * @param context 扫描上下文
     */
    private void scanConfigMapData(ConfigMap configMap, String sourceType, String sourceTitle, String settingName,
                                    java.util.function.Function<String, String> urlBuilder,
                                    ReferenceScanContext context) {
        String configMapName = configMap.getMetadata().getName();
        Map<String, String> data = configMap.getData();
        if (data == null) return;

        // 扫描每个配置项
        data.forEach((groupKey, jsonValue) -> {
            if (!StringUtils.hasText(jsonValue)) return;

            String sourceUrl = urlBuilder.apply(groupKey);
            AttachmentReference.ReferenceSource source = createSource(
                sourceType, configMapName, sourceTitle,
                sourceUrl, false, groupKey, settingName);

            try {
                JsonNode groupNode = objectMapper.readTree(jsonValue);
                // 递归扫描 JSON 节点
                scanJsonNode(groupNode, source, context);
            } catch (Exception e) {
                // JSON 解析失败，直接扫描原始值
                ContentScanner.ExtractResult result = contentScanner.extractUrlsWithType(jsonValue);
                context.addExtractResult(result, source);
            }
        });
    }

    /**
     * 递归扫描 JSON 节点，提取所有文本值中的 URL
     * @param node JSON 节点
     * @param source 引用来源
     * @param context 扫描上下文
     */
    private void scanJsonNode(JsonNode node, AttachmentReference.ReferenceSource source, ReferenceScanContext context) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }

        if (node.isTextual()) {
            // 文本节点，提取 URL
            String text = node.asText();
            if (StringUtils.hasText(text)) {
                ContentScanner.ExtractResult result = contentScanner.extractUrlsWithType(text);
                context.addExtractResult(result, source);
            }
        } else if (node.isObject()) {
            // 对象节点，递归处理每个字段
            node.fields().forEachRemaining(entry -> {
                scanJsonNode(entry.getValue(), source, context);
            });
        } else if (node.isArray()) {
            // 数组节点，递归处理每个元素
            for (JsonNode element : node) {
                scanJsonNode(element, source, context);
            }
        }
        // 其他类型（数字、布尔等）不处理
    }

    /**
     * 扫描用户头像
     */
    private Mono<Void> scanUserAvatars(ReferenceScanContext context) {
        log.debug("开始扫描 用户头像...");
        return client.listAll(User.class, ListOptions.builder().build(), Sort.unsorted())
            .doOnNext(user -> {
                String userName = user.getMetadata().getName();
                String displayName = user.getSpec().getDisplayName();
                String avatar = user.getSpec().getAvatar();

                if (!StringUtils.hasText(avatar)) {
                    return;
                }

                // sourceTitle 显示用户显示名，sourceUrl 指向用户主页
                String userUrl = user.getStatus() != null ? user.getStatus().getPermalink() : null;
                AttachmentReference.ReferenceSource source = createSource(
                    "User", userName, displayName, userUrl, false, "avatar");
                context.addUrl(avatar, source);
            })
            .count()
            .doOnNext(count -> log.debug("用户头像 扫描完成，共扫描 {} 条记录", count))
            .then();
    }

    /**
     * 扫描瞬间（Moment 插件）
     */
    private Mono<Void> scanMoments(ReferenceScanContext context) {
        return scanExtensions(context, MOMENT_GVK, "瞬间", (ext, rootNode) -> {
            String momentName = ext.getMetadata().getName();
            // 优先从 status.permalink 获取，为空时 fallback 到硬编码路径
            JsonNode statusNode = rootNode.get("status");
            String permalink = statusNode != null && statusNode.has("permalink")
                ? statusNode.get("permalink").asText(null) : null;
            String sourceUrl = StringUtils.hasText(permalink) ? permalink : "/moments/" + momentName;
            JsonNode specNode = rootNode.get("spec");

            if (specNode != null) {
                JsonNode contentNode = specNode.get("content");
                if (contentNode != null) {
                    // 1. 提取 HTML 内容
                    String htmlContent = contentNode.has("html") ? contentNode.get("html").asText(null) : null;
                    if (StringUtils.hasText(htmlContent)) {
                        AttachmentReference.ReferenceSource htmlSource = createSource(
                            "Moment", momentName, "瞬间", sourceUrl, false, "content");
                        ContentScanner.ExtractResult result = contentScanner.extractUrlsFromHtml(htmlContent);
                        context.addExtractResult(result, htmlSource);
                    }

                    // 2. 提取媒体文件 URL
                    JsonNode mediumNode = contentNode.get("medium");
                    if (mediumNode != null && mediumNode.isArray()) {
                        for (JsonNode mediaItem : mediumNode) {
                            String mediaUrl = mediaItem.has("url") ? mediaItem.get("url").asText(null) : null;
                            if (StringUtils.hasText(mediaUrl)) {
                                AttachmentReference.ReferenceSource mediaSource = createSource(
                                    "Moment", momentName, "瞬间", sourceUrl, false, "media");
                                context.addUrl(mediaUrl, mediaSource);
                            }
                        }
                    }
                }
            }
            return Mono.empty();
        });
    }

    /**
     * 扫描图库（Photos 插件）
     */
    private Mono<Void> scanPhotos(ReferenceScanContext context) {
        return scanExtensions(context, PHOTO_GVK, "图库", (ext, rootNode) -> {
            String name = ext.getMetadata().getName();
            JsonNode specNode = rootNode.get("spec");

            if (specNode != null) {
                // 获取分组信息，拼接精确的图库 URL
                String groupName = specNode.has("groupName") ? specNode.get("groupName").asText(null) : null;
                String sourceUrl = StringUtils.hasText(groupName)
                    ? "/photos?group=" + groupName : "/photos";

                // 1. 提取 url 字段（内容）
                String url = specNode.has("url") ? specNode.get("url").asText(null) : null;
                if (StringUtils.hasText(url)) {
                    AttachmentReference.ReferenceSource urlSource = createSource(
                        "Photo", name, "图库", sourceUrl, false, "content");
                    context.addUrl(url, urlSource);
                }

                // 2. 提取 cover 字段（封面）
                String cover = specNode.has("cover") ? specNode.get("cover").asText(null) : null;
                if (StringUtils.hasText(cover) && !cover.equals(url)) {
                    AttachmentReference.ReferenceSource coverSource = createSource(
                        "Photo", name, "图库", sourceUrl, false, "cover");
                    context.addUrl(cover, coverSource);
                }
            }
            return Mono.empty();
        });
    }

    /**
     * 扫描文档（Docsme 插件）
     */
    private Mono<Void> scanDocs(ReferenceScanContext context) {
        var docSchemeOpt = schemeManager.fetch(DOC_GVK);
        var projectSchemeOpt = schemeManager.fetch(PROJECT_GVK);

        if (docSchemeOpt.isEmpty() && projectSchemeOpt.isEmpty()) {
            log.debug("Docsme 文档插件未安装，跳过扫描");
            return Mono.empty();
        }

        // 预获取 Doc 所属版本的发布状态，用于确定引用类型
        return fetchDocVersionPublishStates()
            .defaultIfEmpty(Map.of())
            .flatMap(docPublishStates -> {
                // 1. 扫描 Doc 内容
                Mono<Void> scanDocContent = scanExtensions(context, DOC_GVK, "文档内容", (ext, rootNode) -> {
                    String docName = ext.getMetadata().getName();
                    JsonNode specNode = rootNode.get("spec");

                    String headSnapshotName = specNode != null && specNode.has("headSnapshot")
                        ? specNode.get("headSnapshot").asText() : null;
                    String releaseSnapshotName = specNode != null && specNode.has("releaseSnapshot")
                        ? specNode.get("releaseSnapshot").asText() : null;

                    // 确定引用类型：版本未发布 -> unpublished，版本已发布 -> draft/content
                    String contentType;
                    Boolean isInPublishedVersion = docPublishStates.get(docName);
                    if (isInPublishedVersion != null && !isInPublishedVersion) {
                        contentType = "unpublished";
                    } else {
                        boolean isDraft = !StringUtils.hasText(releaseSnapshotName)
                            || !releaseSnapshotName.equals(headSnapshotName);
                        contentType = isDraft ? "draft" : "content";
                    }

                    Mono<Void> scanHead = StringUtils.hasText(headSnapshotName)
                        ? scanDocSnapshot(headSnapshotName,
                            createSource("Doc", docName, "Doc:" + docName, null, false, contentType), context)
                        : Mono.empty();
                    Mono<Void> scanRelease = StringUtils.hasText(releaseSnapshotName)
                        && !releaseSnapshotName.equals(headSnapshotName)
                        ? scanDocSnapshot(releaseSnapshotName,
                            createSource("Doc", docName, "Doc:" + docName, null, false, "content"), context)
                        : Mono.empty();
                    return scanHead.then(scanRelease);

                });

                // 2. 扫描 Project 图标
                Mono<Void> scanProjectIcon = scanExtensions(context, PROJECT_GVK, "文档项目图标", (ext, rootNode) -> {
                    JsonNode specNode = rootNode.get("spec");
                    JsonNode statusNode = rootNode.get("status");

                    String projectName = ext.getMetadata().getName();
                    String displayName = specNode != null && specNode.has("displayName")
                        ? specNode.get("displayName").asText() : projectName;
                    String icon = specNode != null && specNode.has("icon")
                        ? specNode.get("icon").asText() : null;
                    String permalink = statusNode != null && statusNode.has("permalink")
                        ? statusNode.get("permalink").asText() : null;

                    if (StringUtils.hasText(icon)) {
                        AttachmentReference.ReferenceSource source = createSource(
                            "Doc", projectName, displayName, permalink, false, "icon");
                        context.addUrl(icon, source);
                    }
                    return Mono.empty();
                });

                return scanDocContent.then(scanProjectIcon);
            });
    }

    private Mono<Void> scanDocSnapshot(String snapshotName, AttachmentReference.ReferenceSource source,
                                       ReferenceScanContext context) {
        // Docsme snapshots contain full HTML rather than JSON patches.
        return scanSnapshotContent(client.fetch(Snapshot.class, snapshotName)
            .map(snapshot -> ContentWrapper.builder()
                .raw(snapshot.getSpec().getRawPatch())
                .content(snapshot.getSpec().getContentPatch())
                .build()), source, context);
    }

    /**
     * 扫描互动增强插件（Interaction Plus）
     * 覆盖 2 类含附件 URL 的记录：
     * - 装饰素材（UserDecorationAsset.spec.asset.url，勋章/头像框/名片背景/称号图片）
     * - 身份标识图片（UserIdentityMarkMapping.spec.image）
     * 授予记录 / 佩戴配置存装饰内部名而非 URL，元数据为纯文本，均无需扫描；
     * 自定义模板为站长贴的渲染片段，约定不放图片，不扫描
     */
    private Mono<Void> scanInteractions(ReferenceScanContext context) {
        // 1. 扫描装饰素材（草稿/投稿中的装饰同样持有素材，一律视为引用，避免误删导致前台裂图）
        Mono<Void> scanDecorationAssets = scanExtensions(context, DECORATION_ASSET_GVK, "装饰资产", (ext, rootNode) -> {
            String name = ext.getMetadata().getName();
            JsonNode specNode = rootNode.get("spec");
            String displayName = specNode != null && specNode.has("displayName")
                ? specNode.get("displayName").asText() : name;
            String url = null;
            if (specNode != null && specNode.has("asset") && specNode.get("asset").has("url")) {
                url = specNode.get("asset").get("url").asText(null);
            }
            if (StringUtils.hasText(url)) {
                AttachmentReference.ReferenceSource source = createSource(
                    "InteractionPlus", name, displayName,
                    "/console/interaction-plus/decorations/assets", false, "decoration");
                context.addUrl(url, source);
            }
            return Mono.empty();
        });

        // 2. 扫描身份标识图片（displayMode=image 时上传的附件图）
        Mono<Void> scanIdentityMarks = scanExtensions(context, IDENTITY_MARK_GVK, "身份标识", (ext, rootNode) -> {
            String name = ext.getMetadata().getName();
            JsonNode specNode = rootNode.get("spec");
            String displayName = specNode != null && specNode.has("displayName")
                ? specNode.get("displayName").asText() : name;
            String image = specNode != null && specNode.has("image")
                ? specNode.get("image").asText(null) : null;
            if (StringUtils.hasText(image)) {
                AttachmentReference.ReferenceSource source = createSource(
                    "InteractionPlus", name, displayName,
                    "/console/interaction-plus/decorations/identity-marks", false, "identity");
                context.addUrl(image, source);
            }
            return Mono.empty();
        });

        return scanDecorationAssets.then(scanIdentityMarks);
    }

    /**
     * 预获取 Doc 所属版本的发布状态
     * 通过 DocTree 关联 Doc 和 ProjectVersion（DocTree.spec.docName -> Doc, DocTree.spec.projectVersionName -> ProjectVersion）
     * 如果一个 Doc 被多个版本引用，任一版本已发布则视为已发布
     * @return Map: docName -> isInPublishedVersion
     */
    private Mono<Map<String, Boolean>> fetchDocVersionPublishStates() {
        var docTreeSchemeOpt = schemeManager.fetch(DOC_TREE_GVK);
        var versionSchemeOpt = schemeManager.fetch(PROJECT_VERSION_GVK);
        if (docTreeSchemeOpt.isEmpty() || versionSchemeOpt.isEmpty()) {
            return Mono.just(Map.of());
        }

        return Mono.zip(
                // ProjectVersion 列表
                client.listAll(versionSchemeOpt.get().type(), ListOptions.builder().build(), Sort.unsorted())
                    .collectList(),
                // DocTree 列表
                client.listAll(docTreeSchemeOpt.get().type(), ListOptions.builder().build(), Sort.unsorted())
                    .collectList()
            )
            .map(tuple -> {
                // 构建 versionName -> published
                Map<String, Boolean> versionPublished = new HashMap<>();
                for (var ext : tuple.getT1()) {
                    try {
                        JsonNode root = objectMapper.readTree(objectMapper.writeValueAsString(ext));
                        JsonNode specNode = root.get("spec");
                        boolean published = specNode != null && specNode.has("publish")
                            && specNode.get("publish").asBoolean(false);
                        versionPublished.put(ext.getMetadata().getName(), published);
                    } catch (Exception e) {
                        log.debug("解析 ProjectVersion 失败: {}", e.getMessage());
                    }
                }

                // 通过 DocTree 关联，构建 docName -> isInPublishedVersion
                Map<String, Boolean> docPublished = new HashMap<>();
                for (var ext : tuple.getT2()) {
                    try {
                        JsonNode root = objectMapper.readTree(objectMapper.writeValueAsString(ext));
                        JsonNode specNode = root.get("spec");
                        String docName = specNode != null && specNode.has("docName")
                            ? specNode.get("docName").asText(null) : null;
                        String versionName = specNode != null && specNode.has("projectVersionName")
                            ? specNode.get("projectVersionName").asText(null) : null;

                        if (docName != null && versionName != null) {
                            boolean published = versionPublished.getOrDefault(versionName, true);
                            // 任一关联版本已发布，则视为已发布
                            docPublished.merge(docName, published, (a, b) -> a || b);
                        }
                    } catch (Exception e) {
                        log.debug("解析 DocTree 失败: {}", e.getMessage());
                    }
                }
                return docPublished;
            })
            .onErrorResume(e -> {
                log.debug("获取文档版本发布状态失败: {}", e.getMessage());
                return Mono.just(Map.of());
            });
    }

    /**
     * 通用扩展扫描方法
     * 处理 GVK 检查、列表获取、JSON 解析和错误处理
     */
    private Mono<Void> scanExtensions(ReferenceScanContext context,
                                      GroupVersionKind gvk,
                                      String logName,
                                      ExtensionProcessor processor) {
        var schemeOpt = schemeManager.fetch(gvk);
        if (schemeOpt.isEmpty()) {
            log.debug("{} 未安装（GVK: {}），跳过扫描", logName, gvk);
            return Mono.empty();
        }

        log.debug("开始扫描 {}，GVK: {}", logName, gvk);
        return client.listAll(schemeOpt.get().type(), ListOptions.builder().build(), Sort.unsorted())
            .concatMap(ext -> {
                try {
                    String json = objectMapper.writeValueAsString(ext);
                    JsonNode rootNode = objectMapper.readTree(json);
                    // 处理完成后返回 1，用于计数
                    return processor.process(ext, rootNode).thenReturn(1);
                } catch (Exception e) {
                    log.warn("扫描 {} 失败: {}", logName, e.getMessage());
                    return Mono.error(new IllegalStateException("无法扫描 " + logName, e));
                }
            })
            .count()
            .doOnNext(count -> log.debug("{} 扫描完成，共扫描 {} 条记录", logName, count))
            .then()
            .onErrorMap(e -> new IllegalStateException("无法扫描 " + logName + ": " + e.getMessage(), e));
    }

    @FunctionalInterface
    private interface ExtensionProcessor {
        Mono<Void> process(run.halo.app.extension.Extension extension, JsonNode rootNode);
    }

    /**
     * 创建引用源对象
     */
    private AttachmentReference.ReferenceSource createSource(
            String sourceType, String sourceName, String sourceTitle,
            String sourceUrl, boolean deleted, String referenceType) {
        return createSource(sourceType, sourceName, sourceTitle, sourceUrl, deleted, referenceType, null);
    }

    /**
     * 创建引用源对象（带 settingName）
     */
    private AttachmentReference.ReferenceSource createSource(
            String sourceType, String sourceName, String sourceTitle,
            String sourceUrl, boolean deleted, String referenceType, String settingName) {
        AttachmentReference.ReferenceSource source = new AttachmentReference.ReferenceSource();
        source.setSourceType(sourceType);
        source.setSourceName(sourceName);
        source.setSourceTitle(sourceTitle);
        source.setSourceUrl(sourceUrl);
        source.setDeleted(deleted);
        source.setReferenceType(referenceType);
        source.setSettingName(settingName);
        return source;
    }

}
