package com.timxs.storagetoolkit.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import run.halo.app.infra.utils.JsonUtils;
import run.halo.app.infra.ExternalLinkProcessor;
import com.timxs.storagetoolkit.extension.AttachmentReference;
import com.timxs.storagetoolkit.extension.ReferenceScanStatus;
import com.timxs.storagetoolkit.service.ContentScanner;
import com.timxs.storagetoolkit.service.ReferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.content.ContentWrapper;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.content.Snapshot;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.Plugin;
import run.halo.app.core.extension.Theme;
import run.halo.app.core.extension.Setting;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.ReactiveSettingFetcher;
import org.springframework.data.domain.Sort;

import static run.halo.app.extension.index.query.Queries.equal;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 引用扫描服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReferenceServiceImpl implements ReferenceService {

    private final ReactiveExtensionClient client;
    private final ContentScanner contentScanner;
    private final ReactiveSettingFetcher settingFetcher;
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

    /**
     * 默认扫描超时时间（分钟）
     */
    private static final int DEFAULT_SCAN_TIMEOUT_MINUTES = 5;

    @Override
    public Mono<ReferenceScanStatus> startScan() {
        return getScanStatus()
            .flatMap(status -> {
                // 检查是否正在扫描
                if (status.getStatus() != null 
                    && ReferenceScanStatus.Phase.SCANNING.equals(status.getStatus().getPhase())) {
                    // 检查是否超时
                    return getScanTimeoutMinutes()
                        .flatMap(timeout -> {
                            if (!isStuck(status.getStatus().getStartTime(), timeout)) {
                                return Mono.error(new IllegalStateException("扫描正在进行中"));
                            }
                            // 超时，允许重新扫描
                            log.warn("上次扫描超时，允许重新触发");
                            return doStartScan(status);
                        });
                }
                return doStartScan(status);
            });
    }

    /**
     * 执行扫描
     */
    private Mono<ReferenceScanStatus> doStartScan(ReferenceScanStatus status) {
        // 更新状态为扫描中
        if (status.getStatus() == null) {
            status.setStatus(new ReferenceScanStatus.ReferenceScanStatusStatus());
        }
        status.getStatus().setPhase(ReferenceScanStatus.Phase.SCANNING);
        status.getStatus().setStartTime(Instant.now());
        status.getStatus().setErrorMessage(null);

        return client.update(status)
            .flatMap(updated -> {
                // 异步执行扫描
                performScan(updated)
                    .subscribe(
                        result -> log.info("扫描完成: {}", result),
                        error -> {
                            log.error("扫描失败", error);
                            // 更新状态为错误，避免状态停留在 SCANNING
                            updateScanError(updated, error.getMessage()).subscribe();
                        }
                    );
                return Mono.just(updated);
            });
    }

    /**
     * 执行实际的扫描逻辑
     */
    private Mono<ReferenceScanStatus> performScan(ReferenceScanStatus status) {
        log.info("开始扫描附件引用...");

        // 分开存储完整 URL 和相对路径的引用
        Map<String, Set<AttachmentReference.ReferenceSource>> fullUrlToSources = new ConcurrentHashMap<>();
        Map<String, Set<AttachmentReference.ReferenceSource>> relativePathToSources = new ConcurrentHashMap<>();
        
        // 本次扫描的时间戳，用于生成唯一的记录名称
        long scanTimestamp = System.currentTimeMillis();

        // 先标记所有现有记录为待删除，然后提交删除
        return markAllAsPendingDeleteAndDelete()
            .then(getAnalysisSettings())
            .flatMap(settings -> {
                // 根据配置决定扫描哪些内容
                List<Mono<Void>> scanTasks = new ArrayList<>();
                
                if (settings.scanPosts()) {
                    scanTasks.add(scanPosts(fullUrlToSources, relativePathToSources));
                }
                if (settings.scanPages()) {
                    scanTasks.add(scanSinglePages(fullUrlToSources, relativePathToSources));
                }
                if (settings.scanComments()) {
                    scanTasks.add(scanComments(fullUrlToSources, relativePathToSources));
                    scanTasks.add(scanReplies(fullUrlToSources, relativePathToSources));
                }
                if (settings.scanMoments()) {
                    scanTasks.add(scanMoments(fullUrlToSources, relativePathToSources));
                }
                if (settings.scanPhotos()) {
                    scanTasks.add(scanPhotos(fullUrlToSources, relativePathToSources));
                }
                if (settings.scanDocs()) {
                    scanTasks.add(scanDocs(fullUrlToSources, relativePathToSources));
                }
                // 系统设置始终扫描
                scanTasks.add(scanConfigMaps(fullUrlToSources, relativePathToSources));
                // 用户头像始终扫描
                scanTasks.add(scanUserAvatars(fullUrlToSources, relativePathToSources));

                return Flux.merge(scanTasks).then();
            })
            .then(Mono.defer(() -> {
                log.info("内容扫描完成，完整URL: {} 个, 相对路径: {} 个", 
                    fullUrlToSources.size(), relativePathToSources.size());
                // 匹配附件并创建新的引用关系（使用时间戳避免名称冲突）
                return matchAndCreateReferences(fullUrlToSources, relativePathToSources, status, scanTimestamp);
            }))
            .onErrorResume(error -> {
                log.error("扫描过程出错", error);
                return updateScanError(status, error.getMessage());
            });
    }

    /**
     * 标记所有现有记录为待删除，然后提交删除
     */
    private Mono<Void> markAllAsPendingDeleteAndDelete() {
        return client.listAll(AttachmentReference.class, ListOptions.builder().build(), Sort.unsorted())
            .flatMap(ref -> {
                if (ref.getStatus() == null) {
                    ref.setStatus(new AttachmentReference.AttachmentReferenceStatus());
                }
                ref.getStatus().setPendingDelete(true);
                return client.update(ref)
                    .flatMap(updated -> client.delete(updated));
            })
            .then()
            .doOnSuccess(v -> log.info("已标记并删除所有旧引用记录"));
    }

    /**
     * 扫描文章
     */
    private Mono<Void> scanPosts(Map<String, Set<AttachmentReference.ReferenceSource>> fullUrlToSources,
                                  Map<String, Set<AttachmentReference.ReferenceSource>> relativePathToSources) {
        return client.listAll(Post.class, ListOptions.builder().build(), Sort.unsorted())
            .flatMap(post -> {
                String postName = post.getMetadata().getName();
                String postTitle = post.getSpec().getTitle();
                String postUrl = "/archives/" + post.getSpec().getSlug();
                // 检查是否在回收站
                boolean isDeleted = post.getSpec().getDeleted() != null && post.getSpec().getDeleted();

                // 扫描封面图
                String cover = post.getSpec().getCover();
                if (StringUtils.hasText(cover)) {
                    AttachmentReference.ReferenceSource coverSource = createSource(
                        "Post", postName, postTitle, postUrl, isDeleted, "cover");
                    addUrlSourceWithType(fullUrlToSources, relativePathToSources, cover, coverSource);
                }

                // 使用 PostContentService 获取完整内容
                return postContentService.getHeadContent(postName)
                    .doOnNext(contentWrapper -> {
                        AttachmentReference.ReferenceSource contentSource = createSource(
                            "Post", postName, postTitle, postUrl, isDeleted, "content");
                        
                        // 扫描原始内容
                        String rawContent = contentWrapper.getRaw();
                        if (StringUtils.hasText(rawContent)) {
                            addExtractedUrls(fullUrlToSources, relativePathToSources, rawContent, contentSource);
                        }
                        
                        // 扫描渲染内容
                        String htmlContent = contentWrapper.getContent();
                        if (StringUtils.hasText(htmlContent)) {
                            addExtractedUrls(fullUrlToSources, relativePathToSources, htmlContent, contentSource);
                        }
                    })
                    .onErrorResume(e -> {
                        log.warn("获取文章 {} 内容失败: {}", postTitle, e.getMessage());
                        return Mono.empty();
                    })
                    .then();
            })
            .then();
    }

    /**
     * 扫描独立页面
     */
    private Mono<Void> scanSinglePages(Map<String, Set<AttachmentReference.ReferenceSource>> fullUrlToSources,
                                        Map<String, Set<AttachmentReference.ReferenceSource>> relativePathToSources) {
        return client.listAll(SinglePage.class, ListOptions.builder().build(), Sort.unsorted())
            .flatMap(page -> {
                String pageName = page.getMetadata().getName();
                String pageTitle = page.getSpec().getTitle();
                String pageUrl = "/" + page.getSpec().getSlug();
                // 检查是否在回收站
                boolean isDeleted = page.getSpec().getDeleted() != null && page.getSpec().getDeleted();

                // 扫描封面图
                String cover = page.getSpec().getCover();
                if (StringUtils.hasText(cover)) {
                    AttachmentReference.ReferenceSource coverSource = createSource(
                        "SinglePage", pageName, pageTitle, pageUrl, isDeleted, "cover");
                    addUrlSourceWithType(fullUrlToSources, relativePathToSources, cover, coverSource);
                }

                // 获取页面内容（使用 Snapshot 合并逻辑）
                String headSnapshotName = page.getSpec().getHeadSnapshot();
                String baseSnapshotName = page.getSpec().getBaseSnapshot();
                
                return getSinglePageContent(headSnapshotName, baseSnapshotName)
                    .doOnNext(contentWrapper -> {
                        AttachmentReference.ReferenceSource contentSource = createSource(
                            "SinglePage", pageName, pageTitle, pageUrl, isDeleted, "content");
                        
                        // 扫描原始内容
                        String rawContent = contentWrapper.getRaw();
                        if (StringUtils.hasText(rawContent)) {
                            addExtractedUrls(fullUrlToSources, relativePathToSources, rawContent, contentSource);
                        }
                        
                        // 扫描渲染内容
                        String htmlContent = contentWrapper.getContent();
                        if (StringUtils.hasText(htmlContent)) {
                            addExtractedUrls(fullUrlToSources, relativePathToSources, htmlContent, contentSource);
                        }
                    })
                    .onErrorResume(e -> {
                        log.warn("获取页面 {} 内容失败: {}", pageTitle, e.getMessage());
                        return Mono.empty();
                    })
                    .then();
            })
            .then();
    }

    /**
     * 获取独立页面内容（合并 Snapshot）
     */
    private Mono<ContentWrapper> getSinglePageContent(String headSnapshotName, String baseSnapshotName) {
        if (!StringUtils.hasText(headSnapshotName) || !StringUtils.hasText(baseSnapshotName)) {
            return Mono.empty();
        }
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
     * 扫描评论
     */
    private Mono<Void> scanComments(Map<String, Set<AttachmentReference.ReferenceSource>> fullUrlToSources,
                                     Map<String, Set<AttachmentReference.ReferenceSource>> relativePathToSources) {
        return client.listAll(Comment.class, ListOptions.builder().build(), Sort.unsorted())
            .doOnNext(comment -> {
                String commentName = comment.getMetadata().getName();
                String content = comment.getSpec().getRaw();

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
                addExtractedUrls(fullUrlToSources, relativePathToSources, content, source);
            })
            .then();
    }

    /**
     * 扫描回复
     */
    private Mono<Void> scanReplies(Map<String, Set<AttachmentReference.ReferenceSource>> fullUrlToSources,
                                    Map<String, Set<AttachmentReference.ReferenceSource>> relativePathToSources) {
        return client.listAll(Reply.class, ListOptions.builder().build(), Sort.unsorted())
            .doOnNext(reply -> {
                String replyName = reply.getMetadata().getName();
                String content = reply.getSpec().getRaw();

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
                addExtractedUrls(fullUrlToSources, relativePathToSources, content, source);
            })
            .then();
    }

    /**
     * 扫描系统配置、插件配置和主题配置
     * 分别扫描系统设置、所有插件设置、所有主题设置的 ConfigMap
     */
    private Mono<Void> scanConfigMaps(Map<String, Set<AttachmentReference.ReferenceSource>> fullUrlToSources,
                                       Map<String, Set<AttachmentReference.ReferenceSource>> relativePathToSources) {
        // 1. 扫描系统设置
        Mono<Void> scanSystem = client.fetch(ConfigMap.class, "system")
            .doOnNext(configMap -> {
                scanConfigMapData(configMap, "SystemSetting", "系统设置", "system", 
                    groupKey -> "/console/settings?tab=" + groupKey,
                    fullUrlToSources, relativePathToSources);
            })
            .then();

        // 2. 扫描所有插件设置
        Mono<Void> scanPlugins = client.listAll(Plugin.class, ListOptions.builder().build(), Sort.unsorted())
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
                            fullUrlToSources, relativePathToSources);
                    })
                    .onErrorResume(e -> {
                        log.warn("获取插件 {} 的 ConfigMap {} 失败: {}", pluginName, configMapName, e.getMessage());
                        return Mono.empty();
                    });
            })
            .then();

        // 3. 扫描所有主题设置
        Mono<Void> scanThemes = client.listAll(Theme.class, ListOptions.builder().build(), Sort.unsorted())
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
                            fullUrlToSources, relativePathToSources);
                    })
                    .onErrorResume(e -> {
                        log.warn("获取主题 {} 的 ConfigMap {} 失败: {}", themeName, configMapName, e.getMessage());
                        return Mono.empty();
                    });
            })
            .then();

        return Flux.merge(scanSystem, scanPlugins, scanThemes).then();
    }

    /**
     * 扫描 ConfigMap 数据
     * @param configMap ConfigMap 对象
     * @param sourceType 来源类型（SystemSetting/PluginSetting/ThemeSetting）
     * @param sourceTitle 显示标题
     * @param settingName Setting 名称（用于异步查询 label）
     * @param urlBuilder URL 构建函数，接收 groupKey 返回完整 URL
     */
    private void scanConfigMapData(ConfigMap configMap, String sourceType, String sourceTitle, String settingName,
                                    java.util.function.Function<String, String> urlBuilder,
                                    Map<String, Set<AttachmentReference.ReferenceSource>> fullUrlToSources,
                                    Map<String, Set<AttachmentReference.ReferenceSource>> relativePathToSources) {
        String configMapName = configMap.getMetadata().getName();
        Map<String, String> data = configMap.getData();
        if (data == null) return;

        // 扫描每个配置项
        data.forEach((groupKey, jsonValue) -> {
            if (!StringUtils.hasText(jsonValue)) return;
            
            String sourceUrl = urlBuilder.apply(groupKey);
            
            try {
                JsonNode groupNode = objectMapper.readTree(jsonValue);
                if (groupNode.isObject()) {
                    // 遍历 JSON 对象的每个字段
                    groupNode.fields().forEachRemaining(entry -> {
                        String fieldValue = entry.getValue().asText();
                        if (StringUtils.hasText(fieldValue)) {
                            // referenceType 存储 groupKey，settingName 用于异步查询 label
                            AttachmentReference.ReferenceSource source = createSource(
                                sourceType, configMapName, sourceTitle, 
                                sourceUrl, false, groupKey, settingName);
                            addExtractedUrls(fullUrlToSources, relativePathToSources, fieldValue, source);
                        }
                    });
                } else {
                    // 非对象类型，直接扫描
                    AttachmentReference.ReferenceSource source = createSource(
                        sourceType, configMapName, sourceTitle, 
                        sourceUrl, false, groupKey, settingName);
                    addExtractedUrls(fullUrlToSources, relativePathToSources, jsonValue, source);
                }
            } catch (Exception e) {
                // JSON 解析失败，直接扫描原始值
                AttachmentReference.ReferenceSource source = createSource(
                    sourceType, configMapName, sourceTitle, 
                    sourceUrl, false, groupKey, settingName);
                addExtractedUrls(fullUrlToSources, relativePathToSources, jsonValue, source);
            }
        });
    }

    /**
     * 扫描用户头像
     */
    private Mono<Void> scanUserAvatars(Map<String, Set<AttachmentReference.ReferenceSource>> fullUrlToSources,
                                        Map<String, Set<AttachmentReference.ReferenceSource>> relativePathToSources) {
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
                addUrlSourceWithType(fullUrlToSources, relativePathToSources, avatar, source);
            })
            .then();
    }

    /**
     * 扫描瞬间（Moment 插件）
     */
    private Mono<Void> scanMoments(Map<String, Set<AttachmentReference.ReferenceSource>> fullUrlToSources,
                                    Map<String, Set<AttachmentReference.ReferenceSource>> relativePathToSources) {
        var schemeOpt = schemeManager.fetch(MOMENT_GVK);
        if (schemeOpt.isEmpty()) {
            log.info("瞬间插件未安装（GVK: {}），跳过扫描", MOMENT_GVK);
            return Mono.empty();
        }
        
        log.info("开始扫描瞬间，GVK: {}", MOMENT_GVK);
        return client.listAll(schemeOpt.get().type(), ListOptions.builder().build(), Sort.unsorted())
            .doOnNext(ext -> {
                try {
                    String momentName = ext.getMetadata().getName();
                    String sourceUrl = "/moments/" + momentName;
                    String json = objectMapper.writeValueAsString(ext);
                    AttachmentReference.ReferenceSource source = createSource(
                        "Moment", momentName, "瞬间", sourceUrl, false, "content");
                    addExtractedUrls(fullUrlToSources, relativePathToSources, json, source);
                } catch (Exception e) {
                    log.warn("扫描瞬间失败: {}", e.getMessage());
                }
            })
            .count()
            .doOnNext(count -> log.info("瞬间扫描完成，共扫描 {} 条记录", count))
            .then()
            .onErrorResume(e -> {
                log.warn("瞬间扫描出错: {}", e.getMessage());
                return Mono.empty();
            });
    }

    /**
     * 扫描图库（Photos 插件）
     * 分别提取 url（内容）和 cover（封面）字段
     */
    private Mono<Void> scanPhotos(Map<String, Set<AttachmentReference.ReferenceSource>> fullUrlToSources,
                                   Map<String, Set<AttachmentReference.ReferenceSource>> relativePathToSources) {
        var schemeOpt = schemeManager.fetch(PHOTO_GVK);
        if (schemeOpt.isEmpty()) {
            log.info("图库插件未安装（GVK: {}），跳过扫描", PHOTO_GVK);
            return Mono.empty();
        }
        
        log.info("开始扫描图库，GVK: {}", PHOTO_GVK);
        return client.listAll(schemeOpt.get().type(), ListOptions.builder().build(), Sort.unsorted())
            .doOnNext(ext -> {
                try {
                    String name = ext.getMetadata().getName();
                    String json = objectMapper.writeValueAsString(ext);
                    JsonNode specNode = objectMapper.readTree(json).get("spec");
                    
                    if (specNode != null) {
                        // 提取 url 字段（内容）
                        String url = specNode.has("url") ? specNode.get("url").asText(null) : null;
                        if (StringUtils.hasText(url)) {
                            AttachmentReference.ReferenceSource urlSource = createSource(
                                "Photo", name, "图库", "/photos", false, "content");
                            addUrlSourceWithType(fullUrlToSources, relativePathToSources, url, urlSource);
                        }
                        
                        // 提取 cover 字段（封面），避免与 url 重复
                        String cover = specNode.has("cover") ? specNode.get("cover").asText(null) : null;
                        if (StringUtils.hasText(cover) && !cover.equals(url)) {
                            AttachmentReference.ReferenceSource coverSource = createSource(
                                "Photo", name, "图库", "/photos", false, "cover");
                            addUrlSourceWithType(fullUrlToSources, relativePathToSources, cover, coverSource);
                        }
                    }
                } catch (Exception e) {
                    log.warn("扫描图库失败: {}", e.getMessage());
                }
            })
            .count()
            .doOnNext(count -> log.info("图库扫描完成，共扫描 {} 条记录", count))
            .then()
            .onErrorResume(e -> {
                log.warn("图库扫描出错: {}", e.getMessage());
                return Mono.empty();
            });
    }

    /**
     * 扫描文档（Docsme 插件）
     * 包括 Doc 内容和 Project 图标
     */
    private Mono<Void> scanDocs(Map<String, Set<AttachmentReference.ReferenceSource>> fullUrlToSources,
                                 Map<String, Set<AttachmentReference.ReferenceSource>> relativePathToSources) {
        var docSchemeOpt = schemeManager.fetch(DOC_GVK);
        var projectSchemeOpt = schemeManager.fetch(PROJECT_GVK);
        
        if (docSchemeOpt.isEmpty() && projectSchemeOpt.isEmpty()) {
            log.info("Docsme 文档插件未安装，跳过扫描");
            return Mono.empty();
        }
        
        // 1. 扫描 Doc 内容
        Mono<Void> scanDocContent = Mono.empty();
        if (docSchemeOpt.isPresent()) {
            log.info("开始扫描文档内容，GVK: {}", DOC_GVK);
            scanDocContent = client.listAll(docSchemeOpt.get().type(), ListOptions.builder().build(), Sort.unsorted())
                .flatMap(ext -> {
                    String docName = ext.getMetadata().getName();
                    try {
                        // 从 Doc 的 spec 中获取 headSnapshot
                        String json = objectMapper.writeValueAsString(ext);
                        JsonNode docNode = objectMapper.readTree(json);
                        JsonNode specNode = docNode.get("spec");
                        
                        String headSnapshotName = specNode != null && specNode.has("headSnapshot") 
                            ? specNode.get("headSnapshot").asText() : null;
                        String baseSnapshotName = specNode != null && specNode.has("releaseSnapshot")
                            ? specNode.get("releaseSnapshot").asText() : null;
                        
                        // 如果没有 baseSnapshot，使用 headSnapshot
                        if (!StringUtils.hasText(baseSnapshotName)) {
                            baseSnapshotName = headSnapshotName;
                        }
                        
                        // 存储 Doc:docName 格式，详情弹窗再查询 DocTree 获取标题
                        AttachmentReference.ReferenceSource source = createSource(
                            "Doc", docName, "Doc:" + docName, null, false, "content");
                        
                        // 获取 Snapshot 内容
                        if (StringUtils.hasText(headSnapshotName) && StringUtils.hasText(baseSnapshotName)) {
                            final String finalBaseSnapshotName = baseSnapshotName;
                            return client.fetch(Snapshot.class, baseSnapshotName)
                                .flatMap(baseSnapshot -> {
                                    if (headSnapshotName.equals(finalBaseSnapshotName)) {
                                        return Mono.just(ContentWrapper.patchSnapshot(baseSnapshot, baseSnapshot));
                                    }
                                    return client.fetch(Snapshot.class, headSnapshotName)
                                        .map(headSnapshot -> ContentWrapper.patchSnapshot(headSnapshot, baseSnapshot));
                                })
                                .doOnNext(contentWrapper -> {
                                    // 扫描原始内容
                                    String rawContent = contentWrapper.getRaw();
                                    if (StringUtils.hasText(rawContent)) {
                                        addExtractedUrls(fullUrlToSources, relativePathToSources, rawContent, source);
                                    }
                                    // 扫描渲染内容
                                    String htmlContent = contentWrapper.getContent();
                                    if (StringUtils.hasText(htmlContent)) {
                                        addExtractedUrls(fullUrlToSources, relativePathToSources, htmlContent, source);
                                    }
                                })
                                .onErrorResume(e -> {
                                    log.warn("获取文档 {} 内容失败: {}", docName, e.getMessage());
                                    return Mono.empty();
                                })
                                .then(Mono.just(1));
                        }
                    } catch (Exception e) {
                        log.warn("扫描文档 {} 失败: {}", docName, e.getMessage());
                    }
                    return Mono.just(0);
                })
                .count()
                .doOnNext(count -> log.info("文档内容扫描完成，共扫描 {} 条记录", count))
                .then();
        }
        
        // 2. 扫描 Project 图标
        Mono<Void> scanProjectIcon = Mono.empty();
        if (projectSchemeOpt.isPresent()) {
            log.info("开始扫描文档项目图标，GVK: {}", PROJECT_GVK);
            scanProjectIcon = client.listAll(projectSchemeOpt.get().type(), ListOptions.builder().build(), Sort.unsorted())
                .doOnNext(ext -> {
                    try {
                        String json = objectMapper.writeValueAsString(ext);
                        JsonNode projectNode = objectMapper.readTree(json);
                        JsonNode specNode = projectNode.get("spec");
                        JsonNode statusNode = projectNode.get("status");
                        
                        String projectName = ext.getMetadata().getName();
                        String displayName = specNode != null && specNode.has("displayName") 
                            ? specNode.get("displayName").asText() : projectName;
                        String icon = specNode != null && specNode.has("icon") 
                            ? specNode.get("icon").asText() : null;
                        String permalink = statusNode != null && statusNode.has("permalink")
                            ? statusNode.get("permalink").asText() : null;
                        
                        if (StringUtils.hasText(icon)) {
                            // sourceType 使用 Doc，referenceType 使用 icon 区分
                            AttachmentReference.ReferenceSource source = createSource(
                                "Doc", projectName, displayName, permalink, false, "icon");
                            addUrlSourceWithType(fullUrlToSources, relativePathToSources, icon, source);
                        }
                    } catch (Exception e) {
                        log.warn("扫描文档项目失败: {}", e.getMessage());
                    }
                })
                .count()
                .doOnNext(count -> log.info("文档项目图标扫描完成，共扫描 {} 条记录", count))
                .then();
        }
        
        return Flux.merge(scanDocContent, scanProjectIcon)
            .then()
            .onErrorResume(e -> {
                log.warn("文档扫描出错: {}", e.getMessage());
                return Mono.empty();
            });
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

    /**
     * 从内容中提取 URL 并分类添加到对应的 Map
     */
    private void addExtractedUrls(Map<String, Set<AttachmentReference.ReferenceSource>> fullUrlToSources,
                                   Map<String, Set<AttachmentReference.ReferenceSource>> relativePathToSources,
                                   String content, AttachmentReference.ReferenceSource source) {
        ContentScanner.ExtractResult result = contentScanner.extractUrlsWithType(content);
        result.fullUrls().forEach(url -> addUrlSource(fullUrlToSources, url, source));
        result.relativePaths().forEach(path -> addUrlSource(relativePathToSources, path, source));
    }

    /**
     * 添加单个 URL 到引用源映射（根据类型分类）
     */
    private void addUrlSourceWithType(Map<String, Set<AttachmentReference.ReferenceSource>> fullUrlToSources,
                                       Map<String, Set<AttachmentReference.ReferenceSource>> relativePathToSources,
                                       String url, AttachmentReference.ReferenceSource source) {
        if (!StringUtils.hasText(url) || url.startsWith("data:")) {
            return;
        }
        if (contentScanner.isFullUrl(url)) {
            fullUrlToSources.computeIfAbsent(url, k -> ConcurrentHashMap.newKeySet()).add(source);
        } else if (url.startsWith("/")) {
            relativePathToSources.computeIfAbsent(url, k -> ConcurrentHashMap.newKeySet()).add(source);
        }
    }

    /**
     * 添加 URL 到引用源映射
     */
    private void addUrlSource(Map<String, Set<AttachmentReference.ReferenceSource>> urlToSources,
                              String url, AttachmentReference.ReferenceSource source) {
        if (!StringUtils.hasText(url) || url.startsWith("data:")) {
            return;
        }
        urlToSources.computeIfAbsent(url, k -> ConcurrentHashMap.newKeySet()).add(source);
    }

    /**
     * 匹配附件并创建新的引用关系
     * 
     * 匹配逻辑：
     * 1. 完整 URL：精确匹配附件的 permalink
     * 2. 相对路径：匹配附件 permalink 的路径部分（仅限本地附件）
     */
    private Mono<ReferenceScanStatus> matchAndCreateReferences(
            Map<String, Set<AttachmentReference.ReferenceSource>> fullUrlToSources,
            Map<String, Set<AttachmentReference.ReferenceSource>> relativePathToSources,
            ReferenceScanStatus status,
            long scanTimestamp) {
        
        log.info("提取到的完整URL: {}, 相对路径: {}", fullUrlToSources.keySet(), relativePathToSources.keySet());
        
        final AtomicInteger totalCount = new AtomicInteger(0);
        final AtomicInteger referencedCount = new AtomicInteger(0);
        final AtomicLong unreferencedSize = new AtomicLong(0);

        return getExcludeSettings()
            .flatMapMany(excludeSettings -> 
                client.listAll(Attachment.class, ListOptions.builder().build(), Sort.unsorted())
                    .filter(attachment -> {
                        // 过滤排除的分组
                        String groupName = attachment.getSpec().getGroupName();
                        if (groupName != null && excludeSettings.excludeGroups().contains(groupName)) {
                            return false;
                        }
                        // 过滤排除的存储策略
                        String policyName = attachment.getSpec().getPolicyName();
                        if (policyName != null && excludeSettings.excludePolicies().contains(policyName)) {
                            return false;
                        }
                        return true;
                    })
            )
            .flatMap(attachment -> {
                String attachmentName = attachment.getMetadata().getName();
                String permalink = attachment.getStatus() != null ? attachment.getStatus().getPermalink() : null;
                long fileSize = attachment.getSpec().getSize() != null ? attachment.getSpec().getSize() : 0;

                totalCount.incrementAndGet();

                Set<AttachmentReference.ReferenceSource> sources = new HashSet<>();
                if (StringUtils.hasText(permalink)) {
                    // 对 permalink 进行 URL 解码后匹配
                    String decodedPermalink = decodeUrl(permalink);
                    
                    // 1. 完整 URL 精确匹配（permalink 本身是完整 URL）
                    if (fullUrlToSources.containsKey(decodedPermalink)) {
                        sources.addAll(fullUrlToSources.get(decodedPermalink));
                        log.debug("附件 {} 完整URL匹配成功: {}", attachmentName, decodedPermalink);
                    }
                    
                    // 2. 如果 permalink 是相对路径，拼成完整 URL 再匹配
                    if (!contentScanner.isFullUrl(decodedPermalink)) {
                        String fullPermalink = externalLinkProcessor.processLink(decodedPermalink);
                        if (fullUrlToSources.containsKey(fullPermalink)) {
                            sources.addAll(fullUrlToSources.get(fullPermalink));
                            log.debug("附件 {} 拼接完整URL匹配成功: {} -> {}", attachmentName, decodedPermalink, fullPermalink);
                        }
                    }
                    
                    // 3. 相对路径匹配（提取 permalink 的路径部分）
                    String permalinkPath = contentScanner.extractPath(decodedPermalink);
                    if (relativePathToSources.containsKey(permalinkPath)) {
                        sources.addAll(relativePathToSources.get(permalinkPath));
                        log.debug("附件 {} 相对路径匹配成功: {}", attachmentName, permalinkPath);
                    }
                }

                if (!sources.isEmpty()) {
                    referencedCount.incrementAndGet();
                } else {
                    unreferencedSize.addAndGet(fileSize);
                }

                return createAttachmentReference(attachmentName, sources, scanTimestamp);
            })
            .then(Mono.defer(() -> {
                int total = totalCount.get();
                int referenced = referencedCount.get();
                long unrefSize = unreferencedSize.get();
                
                // 更新扫描状态
                status.getStatus().setPhase(ReferenceScanStatus.Phase.COMPLETED);
                status.getStatus().setLastScanTime(Instant.now());
                status.getStatus().setTotalAttachments(total);
                status.getStatus().setReferencedCount(referenced);
                status.getStatus().setUnreferencedCount(total - referenced);
                status.getStatus().setUnreferencedSize(unrefSize);
                status.getStatus().setErrorMessage(null);

                log.info("扫描完成 - 总附件: {}, 已引用: {}, 未引用: {}, 未引用占用: {} bytes",
                    total, referenced, total - referenced, unrefSize);

                return client.update(status);
            }));
    }

    /**
     * 创建附件引用记录（使用时间戳生成唯一名称，避免与旧记录冲突）
     */
    private Mono<AttachmentReference> createAttachmentReference(
            String attachmentName, Set<AttachmentReference.ReferenceSource> sources, long scanTimestamp) {
        
        // 使用时间戳生成唯一名称，避免与待删除的旧记录冲突
        String refName = "ref-" + attachmentName + "-" + scanTimestamp;
        
        AttachmentReference ref = new AttachmentReference();
        ref.setMetadata(new Metadata());
        ref.getMetadata().setName(refName);
        
        AttachmentReference.AttachmentReferenceSpec spec = new AttachmentReference.AttachmentReferenceSpec();
        spec.setAttachmentName(attachmentName);
        ref.setSpec(spec);
        
        AttachmentReference.AttachmentReferenceStatus refStatus = new AttachmentReference.AttachmentReferenceStatus();
        refStatus.setReferenceCount(sources.size());
        refStatus.setReferences(new ArrayList<>(sources));
        refStatus.setLastScannedAt(Instant.now());
        refStatus.setPendingDelete(false);
        ref.setStatus(refStatus);
        
        return client.create(ref);
    }

    /**
     * 更新扫描错误状态
     */
    private Mono<ReferenceScanStatus> updateScanError(ReferenceScanStatus status, String errorMessage) {
        status.getStatus().setPhase(ReferenceScanStatus.Phase.ERROR);
        status.getStatus().setErrorMessage(errorMessage);
        return client.update(status);
    }

    @Override
    public Mono<ReferenceScanStatus> getScanStatus() {
        return client.fetch(ReferenceScanStatus.class, ReferenceScanStatus.SINGLETON_NAME)
            .switchIfEmpty(Mono.defer(() -> {
                // 创建初始状态
                ReferenceScanStatus status = new ReferenceScanStatus();
                status.setMetadata(new Metadata());
                status.getMetadata().setName(ReferenceScanStatus.SINGLETON_NAME);
                status.setStatus(new ReferenceScanStatus.ReferenceScanStatusStatus());
                return client.create(status);
            }));
    }

    @Override
    public Mono<ListResult<AttachmentReferenceVo>> listReferences(ReferenceQuery query) {
        // 优化：先批量获取所有 AttachmentReference，避免 N+1 查询
        // 过滤掉 pendingDelete 的记录
        Mono<Map<String, AttachmentReference>> refMapMono = client.listAll(AttachmentReference.class, ListOptions.builder().build(), Sort.unsorted())
            .filter(ref -> ref.getStatus() == null || 
                           ref.getStatus().getPendingDelete() == null || 
                           !ref.getStatus().getPendingDelete())
            .collectMap(
                ref -> ref.getSpec().getAttachmentName(),
                ref -> ref
            );
        
        return Mono.zip(
            client.listAll(Attachment.class, ListOptions.builder().build(), Sort.unsorted()).collectList(),
            refMapMono,
            getExcludeSettings()
        ).map(tuple -> {
            List<Attachment> attachments = tuple.getT1();
            Map<String, AttachmentReference> refMap = tuple.getT2();
            ExcludeSettings excludeSettings = tuple.getT3();
            
            // 构建 VO 列表（排除被过滤的附件）
            List<AttachmentReferenceVo> list = attachments.stream()
                .filter(attachment -> {
                    // 过滤排除的分组
                    String groupName = attachment.getSpec().getGroupName();
                    if (groupName != null && excludeSettings.excludeGroups().contains(groupName)) {
                        return false;
                    }
                    // 过滤排除的存储策略
                    String policyName = attachment.getSpec().getPolicyName();
                    if (policyName != null && excludeSettings.excludePolicies().contains(policyName)) {
                        return false;
                    }
                    return true;
                })
                .map(attachment -> {
                    String attachmentName = attachment.getMetadata().getName();
                    AttachmentReference ref = refMap.get(attachmentName);
                    return createVo(attachment, ref);
                })
                .collect(Collectors.toList());
            
            // 过滤
            List<AttachmentReferenceVo> filtered = list.stream()
                .filter(vo -> matchFilter(vo, query.filter()))
                .filter(vo -> matchKeyword(vo, query.keyword()))
                .collect(Collectors.toList());

            // 排序
            if (StringUtils.hasText(query.sort())) {
                sortList(filtered, query.sort());
            }

            // 分页
            int total = filtered.size();
            int start = (query.page() - 1) * query.size();
            int end = Math.min(start + query.size(), total);
            
            List<AttachmentReferenceVo> pageItems = start < total 
                ? filtered.subList(start, end) 
                : Collections.emptyList();

            return new ListResult<>(query.page(), query.size(), total, pageItems);
        });
    }

    @Override
    public Mono<AttachmentReferenceVo> getReference(String attachmentName) {
        return client.fetch(Attachment.class, attachmentName)
            .flatMap(attachment -> 
                // 通过 spec.attachmentName 查找，过滤掉待删除的记录
                findReferenceByAttachmentName(attachmentName)
                    .map(ref -> createVo(attachment, ref))
                    .defaultIfEmpty(createVo(attachment, null))
            );
    }

    /**
     * 通过附件名称查找引用记录（排除待删除的）
     * 利用 spec.attachmentName 索引进行查询
     */
    private Mono<AttachmentReference> findReferenceByAttachmentName(String attachmentName) {
        return client.listBy(AttachmentReference.class,
                ListOptions.builder()
                    .fieldQuery(equal("spec.attachmentName", attachmentName))
                    .build(),
                PageRequestImpl.ofSize(10))  // 理论上只有一条，但留点余量
            .flatMap(result -> Mono.justOrEmpty(result.getItems().stream()
                .filter(ref -> ref.getStatus() == null || 
                               ref.getStatus().getPendingDelete() == null || 
                               !ref.getStatus().getPendingDelete())
                .filter(ref -> ref.getMetadata().getDeletionTimestamp() == null)
                .findFirst()));
    }

    @Override
    public Mono<AttachmentReferenceVo> updateReferenceSource(String attachmentName, String sourceName,
                                                              String sourceTitle, String sourceUrl) {
        return findReferenceByAttachmentName(attachmentName)
            .flatMap(ref -> {
                if (ref.getStatus() == null || ref.getStatus().getReferences() == null) {
                    return Mono.empty();
                }
                
                // 找到对应的引用源并更新
                boolean updated = false;
                for (AttachmentReference.ReferenceSource source : ref.getStatus().getReferences()) {
                    if (sourceName.equals(source.getSourceName())) {
                        if (sourceTitle != null) {
                            source.setSourceTitle(sourceTitle);
                        }
                        if (sourceUrl != null) {
                            source.setSourceUrl(sourceUrl);
                        }
                        updated = true;
                        break;
                    }
                }
                
                if (!updated) {
                    return Mono.empty();
                }
                
                return client.update(ref);
            })
            .flatMap(ref -> getReference(attachmentName));
    }

    // DocTree GVK (doc.halo.run/v1alpha1/DocTree)
    private static final GroupVersionKind DOC_TREE_GVK = 
        new GroupVersionKind("doc.halo.run", "v1alpha1", "DocTree");

    @Override
    public Mono<SubjectInfo> resolveDocInfo(String docName) {
        var docTreeSchemeOpt = schemeManager.fetch(DOC_TREE_GVK);
        if (docTreeSchemeOpt.isEmpty()) {
            log.debug("DocTree 未注册，无法解析文档信息");
            return Mono.empty();
        }
        
        // 查询 DocTree，找到 spec.docName 匹配的记录
        return client.listAll(docTreeSchemeOpt.get().type(), ListOptions.builder().build(), Sort.unsorted())
            .filter(ext -> {
                try {
                    String json = objectMapper.writeValueAsString(ext);
                    JsonNode node = objectMapper.readTree(json);
                    JsonNode specNode = node.get("spec");
                    if (specNode != null && specNode.has("docName")) {
                        return docName.equals(specNode.get("docName").asText());
                    }
                } catch (Exception e) {
                    log.warn("解析 DocTree 失败: {}", e.getMessage());
                }
                return false;
            })
            .next()
            .map(ext -> {
                try {
                    String json = objectMapper.writeValueAsString(ext);
                    JsonNode node = objectMapper.readTree(json);
                    JsonNode specNode = node.get("spec");
                    JsonNode statusNode = node.get("status");
                    
                    String title = specNode != null && specNode.has("title") 
                        ? specNode.get("title").asText() : "";
                    String slug = specNode != null && specNode.has("slug")
                        ? specNode.get("slug").asText() : "";
                    // 格式: "title - slug"
                    String displayTitle;
                    if (StringUtils.hasText(title) && StringUtils.hasText(slug)) {
                        displayTitle = title + " - " + slug;
                    } else if (StringUtils.hasText(title)) {
                        displayTitle = title;
                    } else {
                        displayTitle = docName;
                    }
                    
                    String permalink = statusNode != null && statusNode.has("permalink")
                        ? statusNode.get("permalink").asText() : null;
                    
                    return new SubjectInfo(displayTitle, permalink);
                } catch (Exception e) {
                    log.warn("解析 DocTree 信息失败: {}", e.getMessage());
                    return new SubjectInfo(docName, null);
                }
            });
    }

    @Override
    public Mono<SubjectInfo> resolveDocTreeInfo(String docTreeName) {
        var docTreeSchemeOpt = schemeManager.fetch(DOC_TREE_GVK);
        if (docTreeSchemeOpt.isEmpty()) {
            log.debug("DocTree 未注册，无法解析文档信息");
            return Mono.empty();
        }
        
        // 直接通过 DocTree name 查找
        return client.listAll(docTreeSchemeOpt.get().type(), ListOptions.builder().build(), Sort.unsorted())
            .filter(ext -> docTreeName.equals(ext.getMetadata().getName()))
            .next()
            .map(ext -> {
                try {
                    String json = objectMapper.writeValueAsString(ext);
                    JsonNode node = objectMapper.readTree(json);
                    JsonNode specNode = node.get("spec");
                    JsonNode statusNode = node.get("status");
                    
                    String title = specNode != null && specNode.has("title") 
                        ? specNode.get("title").asText() : "";
                    String slug = specNode != null && specNode.has("slug")
                        ? specNode.get("slug").asText() : "";
                    // 格式: "title - slug"
                    String displayTitle;
                    if (StringUtils.hasText(title) && StringUtils.hasText(slug)) {
                        displayTitle = title + " - " + slug;
                    } else if (StringUtils.hasText(title)) {
                        displayTitle = title;
                    } else {
                        displayTitle = docTreeName;
                    }
                    
                    String permalink = statusNode != null && statusNode.has("permalink")
                        ? statusNode.get("permalink").asText() : null;
                    
                    return new SubjectInfo(displayTitle, permalink);
                } catch (Exception e) {
                    log.warn("解析 DocTree 信息失败: {}", e.getMessage());
                    return new SubjectInfo(docTreeName, null);
                }
            });
    }

    @Override
    public Mono<Void> clearAll() {
        log.info("开始清空引用扫描记录...");
        
        // 删除所有 AttachmentReference 记录
        return client.listAll(AttachmentReference.class, ListOptions.builder().build(), Sort.unsorted())
            .flatMap(ref -> client.delete(ref))
            .then(Mono.defer(() -> {
                // 重置扫描状态
                return getScanStatus()
                    .flatMap(status -> {
                        if (status.getStatus() != null) {
                            status.getStatus().setPhase(null);
                            status.getStatus().setLastScanTime(null);
                            status.getStatus().setStartTime(null);
                            status.getStatus().setTotalAttachments(0);
                            status.getStatus().setReferencedCount(0);
                            status.getStatus().setUnreferencedCount(0);
                            status.getStatus().setUnreferencedSize(0);
                            status.getStatus().setErrorMessage(null);
                        }
                        return client.update(status);
                    });
            }))
            .then()
            .doOnSuccess(v -> log.info("引用扫描记录已清空"));
    }

    @Override
    public Mono<String> getSettingGroupLabel(String settingName, String groupKey) {
        if (!StringUtils.hasText(settingName) || !StringUtils.hasText(groupKey)) {
            return Mono.just(groupKey != null ? groupKey : "");
        }
        
        return client.fetch(Setting.class, settingName)
            .map(setting -> {
                // 遍历 spec.forms 找到匹配的 group
                var forms = setting.getSpec().getForms();
                if (forms != null) {
                    for (var form : forms) {
                        if (groupKey.equals(form.getGroup())) {
                            return StringUtils.hasText(form.getLabel()) ? form.getLabel() : groupKey;
                        }
                    }
                }
                return groupKey;
            })
            .defaultIfEmpty(groupKey)
            .onErrorReturn(groupKey);
    }

    /**
     * 创建视图对象
     */
    private AttachmentReferenceVo createVo(Attachment attachment, AttachmentReference ref) {
        int referenceCount = 0;
        List<AttachmentReference.ReferenceSource> references = Collections.emptyList();
        
        if (ref != null && ref.getStatus() != null) {
            referenceCount = ref.getStatus().getReferenceCount();
            references = ref.getStatus().getReferences() != null 
                ? ref.getStatus().getReferences() 
                : Collections.emptyList();
        }

        return new AttachmentReferenceVo(
            attachment.getMetadata().getName(),
            attachment.getSpec().getDisplayName(),
            attachment.getSpec().getMediaType(),
            attachment.getSpec().getSize() != null ? attachment.getSpec().getSize() : 0,
            attachment.getStatus() != null ? attachment.getStatus().getPermalink() : null,
            attachment.getSpec().getPolicyName(),
            attachment.getSpec().getGroupName(),
            referenceCount,
            references
        );
    }

    /**
     * 过滤匹配
     */
    private boolean matchFilter(AttachmentReferenceVo vo, String filter) {
        if (filter == null || "all".equals(filter)) {
            return true;
        }
        if ("referenced".equals(filter)) {
            return vo.referenceCount() > 0;
        }
        if ("unreferenced".equals(filter)) {
            return vo.referenceCount() == 0;
        }
        return true;
    }

    /**
     * 关键词匹配
     */
    private boolean matchKeyword(AttachmentReferenceVo vo, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return true;
        }
        String displayName = vo.displayName();
        return displayName != null && displayName.toLowerCase().contains(keyword.toLowerCase());
    }

    /**
     * 排序
     */
    private void sortList(List<AttachmentReferenceVo> list, String sort) {
        String[] parts = sort.split(",");
        String field = parts[0];
        boolean desc = parts.length > 1 && "desc".equalsIgnoreCase(parts[1]);

        Comparator<AttachmentReferenceVo> comparator = switch (field) {
            case "referenceCount" -> Comparator.comparingInt(AttachmentReferenceVo::referenceCount);
            case "size" -> Comparator.comparingLong(AttachmentReferenceVo::size);
            case "displayName" -> Comparator.comparing(
                AttachmentReferenceVo::displayName, 
                Comparator.nullsLast(String::compareToIgnoreCase)
            );
            default -> Comparator.comparing(AttachmentReferenceVo::attachmentName);
        };

        if (desc) {
            comparator = comparator.reversed();
        }
        list.sort(comparator);
    }

    /**
     * 检查扫描是否超时
     */
    private boolean isStuck(Instant startTime, int timeoutMinutes) {
        if (startTime == null) {
            return true;
        }
        return Duration.between(startTime, Instant.now()).toMinutes() > timeoutMinutes;
    }

    /**
     * 获取扫描超时时间配置（从 global.analysis.scanTimeoutMinutes 读取）
     */
    private Mono<Integer> getScanTimeoutMinutes() {
        return settingFetcher.get("global")
            .map(setting -> {
                JsonNode analysis = setting.get("analysis");
                if (analysis != null) {
                    JsonNode timeout = analysis.get("scanTimeoutMinutes");
                    if (timeout != null) {
                        if (timeout.isNumber()) {
                            return timeout.asInt();
                        } else if (timeout.isTextual()) {
                            try {
                                return Integer.parseInt(timeout.asText());
                            } catch (NumberFormatException e) {
                                // ignore
                            }
                        }
                    }
                }
                return DEFAULT_SCAN_TIMEOUT_MINUTES;
            })
            .defaultIfEmpty(DEFAULT_SCAN_TIMEOUT_MINUTES);
    }

    /**
     * 获取分析设置
     */
    private Mono<AnalysisSettings> getAnalysisSettings() {
        return settingFetcher.get("analysis")
            .map(setting -> {
                JsonNode refScanning = setting.get("referenceScanning");
                if (refScanning != null) {
                    boolean scanPosts = getBooleanValue(refScanning, "scanPosts", true);
                    boolean scanPages = getBooleanValue(refScanning, "scanPages", true);
                    boolean scanComments = getBooleanValue(refScanning, "scanComments", false);
                    boolean scanMoments = getBooleanValue(refScanning, "scanMoments", false);
                    boolean scanPhotos = getBooleanValue(refScanning, "scanPhotos", false);
                    boolean scanDocs = getBooleanValue(refScanning, "scanDocs", false);
                    return new AnalysisSettings(scanPosts, scanPages, scanComments, scanMoments, scanPhotos, scanDocs);
                }
                return new AnalysisSettings(true, true, false, false, false, false);
            })
            .defaultIfEmpty(new AnalysisSettings(true, true, false, false, false, false));
    }

    private boolean getBooleanValue(JsonNode node, String key, boolean defaultValue) {
        JsonNode value = node.get(key);
        if (value != null && value.isBoolean()) {
            return value.asBoolean();
        }
        return defaultValue;
    }

    /**
     * URL 解码
     * 处理 %20、%E4%B8%AD 等编码
     */
    private String decodeUrl(String url) {
        try {
            return java.net.URLDecoder.decode(url, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    private record AnalysisSettings(boolean scanPosts, boolean scanPages, boolean scanComments, 
                                     boolean scanMoments, boolean scanPhotos, boolean scanDocs) {}

    private record ExcludeSettings(Set<String> excludeGroups, Set<String> excludePolicies) {}

    /**
     * 获取排除设置
     */
    private Mono<ExcludeSettings> getExcludeSettings() {
        return settingFetcher.get("global")
            .map(setting -> {
                Set<String> excludeGroups = new HashSet<>();
                Set<String> excludePolicies = new HashSet<>();
                
                JsonNode analysis = setting.get("analysis");
                if (analysis != null) {
                    JsonNode groups = analysis.get("excludeGroups");
                    if (groups != null && groups.isArray()) {
                        groups.forEach(node -> excludeGroups.add(node.asText()));
                    }
                    JsonNode policies = analysis.get("excludePolicies");
                    if (policies != null && policies.isArray()) {
                        policies.forEach(node -> excludePolicies.add(node.asText()));
                    }
                }
                return new ExcludeSettings(excludeGroups, excludePolicies);
            })
            .defaultIfEmpty(new ExcludeSettings(Set.of(), Set.of()));
    }
}
