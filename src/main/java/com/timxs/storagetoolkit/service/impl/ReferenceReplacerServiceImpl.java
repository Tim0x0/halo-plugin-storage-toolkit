package com.timxs.storagetoolkit.service.impl;

import com.timxs.storagetoolkit.extension.AttachmentReference;
import com.timxs.storagetoolkit.model.ReferenceReplacementResult;
import com.timxs.storagetoolkit.model.ReferenceReplacementTask;
import com.timxs.storagetoolkit.model.ReplaceResult;
import com.timxs.storagetoolkit.model.ReplaceSource;
import com.timxs.storagetoolkit.service.ReferenceReplacerService;
import com.timxs.storagetoolkit.service.ReferenceService;
import com.timxs.storagetoolkit.service.SettingsManager;
import com.timxs.storagetoolkit.service.UrlReplaceLogService;
import com.timxs.storagetoolkit.service.support.*;
import run.halo.app.infra.ExternalLinkProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import org.springframework.data.domain.Sort;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 引用替换服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReferenceReplacerServiceImpl implements ReferenceReplacerService {

    private final ReactiveExtensionClient client;
    private final SettingsManager settingsManager;
    private final UrlReplaceLogService urlReplaceLogService;
    private final ReferenceService referenceService;
    private final ExternalLinkProcessor externalLinkProcessor;

    // 各种内容类型的处理器
    private final PostContentUpdateHandler postHandler;
    private final SinglePageContentUpdateHandler singlePageHandler;
    private final CommentContentUpdateHandler commentHandler;
    private final ReplyContentUpdateHandler replyHandler;
    private final UserContentUpdateHandler userHandler;
    private final ConfigMapContentUpdateHandler configMapHandler;
    private final PluginContentUpdateHandler pluginHandler;

    private static final int DEFAULT_CONCURRENCY = 3;

    @Override
    public Mono<ReferenceReplacementResult> replaceReferences(ReferenceReplacementTask task) {
        if (CollectionUtils.isEmpty(task.getUrlMapping())) {
            return Mono.just(ReferenceReplacementResult.builder()
                .totalAttachments(0)
                .executedAt(Instant.now())
                .build());
        }

        Instant startTime = Instant.now();
        ReferenceReplacementResult result = ReferenceReplacementResult.builder()
            .totalAttachments(task.getAttachmentMapping().size())
            .executedAt(startTime)
            .build();

        // 获取所有需要处理的引用源
        return collectReferenceSources(task)
            .flatMap(sources -> {
                if (sources.isEmpty()) {
                    log.info("没有找到需要替换引用的内容源");
                    result.setDurationMs(Duration.between(startTime, Instant.now()).toMillis());
                    return Mono.just(result);
                }

                // 按 sourceType 分组处理
                Map<String, List<AttachmentReference.ReferenceSource>> groupedSources = new HashMap<>();
                for (AttachmentReference.ReferenceSource source : sources) {
                    groupedSources.computeIfAbsent(source.getSourceType(), k -> new ArrayList<>()).add(source);
                }

                // 计算唯一内容源数量（按 sourceName 去重）
                long uniqueSourceCount = sources.stream()
                    .map(AttachmentReference.ReferenceSource::getSourceName)
                    .distinct()
                    .count();
                log.info("开始引用替换任务，共 {} 个内容源需要处理", uniqueSourceCount);

                // 并行处理不同类型，每种类型内部串行处理（避免乐观锁冲突）
                return Flux.fromIterable(groupedSources.entrySet())
                    .flatMap(entry -> {
                        String sourceType = entry.getKey();
                        List<AttachmentReference.ReferenceSource> typeSources = entry.getValue();
                        return processSourceType(sourceType, typeSources, task, result);
                    }, DEFAULT_CONCURRENCY)
                    .then(Mono.fromCallable(() -> {
                        result.setDurationMs(Duration.between(startTime, Instant.now()).toMillis());
                        log.info("引用替换任务完成，共更新 {} 个内容源",
                            result.getUpdatedSources());
                        return result;
                    }));
            });
    }

    @Override
    public Mono<ReferenceReplacementResult> replaceAfterBatchProcessing(
            String oldAttachmentName, String newAttachmentName,
            String oldPermalink, String newPermalink) {

        return isEnabled()
            .flatMap(enabled -> {
                if (!enabled) {
                    log.debug("引用替换功能已禁用，跳过");
                    return Mono.just(createEmptyResult());
                }

                Map<String, String> attachmentMapping = Map.of(oldAttachmentName, newAttachmentName);
                Map<String, String> urlMapping = UrlReplacer.buildDualFormMapping(
                    oldPermalink, newPermalink, externalLinkProcessor);

                ReferenceReplacementTask task = ReferenceReplacementTask.builder()
                    .attachmentMapping(attachmentMapping)
                    .urlMapping(urlMapping)
                    .source(ReplaceSource.BATCH_PROCESSING)
                    .build();

                log.debug("批量处理后执行引用替换：{} -> {}", oldAttachmentName, newAttachmentName);
                return replaceReferences(task);
            });
    }

    @Override
    public Mono<ReferenceReplacementResult> mergeReferencesBeforeDelete(
            String deletedAttachmentName, String keptAttachmentName,
            String deletedPermalink, String keptPermalink) {

        return isEnabled()
            .flatMap(enabled -> {
                if (!enabled) {
                    log.debug("引用替换功能已禁用，跳过");
                    return Mono.just(createEmptyResult());
                }

                Map<String, String> attachmentMapping = Map.of(deletedAttachmentName, keptAttachmentName);
                Map<String, String> urlMapping = UrlReplacer.buildDualFormMapping(
                    deletedPermalink, keptPermalink, externalLinkProcessor);

                ReferenceReplacementTask task = ReferenceReplacementTask.builder()
                    .attachmentMapping(attachmentMapping)
                    .urlMapping(urlMapping)
                    .source(ReplaceSource.DUPLICATE)
                    .build();

                log.debug("删除重复附件前合并引用：{} -> {}", deletedAttachmentName, keptAttachmentName);
                return replaceReferences(task);
            });
    }

    @Override
    public Mono<Boolean> isEnabled() {
        // 暂时返回 true，后续可以添加配置项
        return Mono.just(true);
    }

    /**
     * 收集所有需要处理的引用源
     */
    private Mono<Set<AttachmentReference.ReferenceSource>> collectReferenceSources(ReferenceReplacementTask task) {
        Set<AttachmentReference.ReferenceSource> allSources = ConcurrentHashMap.newKeySet();

        return client.listAll(AttachmentReference.class, ListOptions.builder().build(), Sort.unsorted())
            .filter(ref -> ref.getSpec() != null
                && ref.getSpec().getAttachmentName() != null
                && task.getAttachmentMapping().containsKey(ref.getSpec().getAttachmentName())
                && ref.getStatus() != null
                && ref.getStatus().getReferences() != null)
            .flatMap(ref -> Flux.fromIterable(ref.getStatus().getReferences()))
            .doOnNext(allSources::add)
            .then(Mono.just(allSources));
    }

    /**
     * 处理特定类型的内容源（按 sourceName 分组，每个实体只替换一次，记录一条合并日志）
     */
    private Mono<Void> processSourceType(String sourceType,
                                         List<AttachmentReference.ReferenceSource> sources,
                                         ReferenceReplacementTask task,
                                         ReferenceReplacementResult result) {
        log.debug("处理 {} 类型，共 {} 个内容源", sourceType, sources.size());

        ReplaceSource logSource = task.getSource();
        Map<String, String> urlMapping = task.getUrlMapping();

        // 按 sourceName 分组，每个实体只替换一次
        Map<String, List<AttachmentReference.ReferenceSource>> grouped = sources.stream()
            .collect(Collectors.groupingBy(AttachmentReference.ReferenceSource::getSourceName,
                LinkedHashMap::new, Collectors.toList()));

        return Flux.fromIterable(grouped.entrySet())
            .concatMap(entry -> {
                String sourceName = entry.getKey();
                List<AttachmentReference.ReferenceSource> group = entry.getValue();
                AttachmentReference.ReferenceSource first = group.get(0);

                // 收集所有 referenceType，用逗号连接
                String joinedRefTypes = group.stream()
                    .map(AttachmentReference.ReferenceSource::getReferenceType)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.joining(","));

                return executeReplace(sourceType, sourceName, task)
                    .flatMap(replaceResult ->
                        resolveLogTitleAndRefTypes(sourceType, first.getSourceTitle(),
                            first.getSettingName(), joinedRefTypes)
                        .flatMap(tr -> recordReplaceResult(replaceResult, urlMapping,
                            sourceType, sourceName, tr[0], tr[1], logSource))
                        .then()
                        .doOnSuccess(v -> {
                            if (replaceResult.hasReplaced()) {
                                result.incrementTypeCount(sourceType);
                            }
                        })
                    );
            })
            .then();
    }

    /**
     * 执行替换操作（不记录日志），返回替换结果
     */
    private Mono<ReplaceResult> executeReplace(String sourceType, String sourceName,
                                                ReferenceReplacementTask task) {
        ReferenceReplacementResult dummyResult = ReferenceReplacementResult.builder().build();

        // 插件类型特殊处理
        if ("Moment".equals(sourceType) || "Photo".equals(sourceType) || "Doc".equals(sourceType)) {
            return pluginHandler.replacePluginUrls(sourceType, sourceName, task, dummyResult);
        }

        // 获取处理器
        ContentUpdateHandler handler = getHandlerForType(sourceType);
        if (handler == null) {
            log.warn("未找到 {} 类型的处理器", sourceType);
            ReplaceResult err = ReplaceResult.builder().build();
            task.getUrlMapping().keySet().forEach(url -> err.addError(url, "未找到处理器: " + sourceType));
            return Mono.just(err);
        }

        return handler.replaceUrls(sourceName, task, dummyResult);
    }

    /**
     * 记录成功的替换日志（只记录指定的 URL）
     */
    private Mono<Void> recordSuccessLogs(Set<String> replacedUrls, Map<String, String> urlMapping,
                                          String sourceType, String sourceName, String sourceTitle,
                                          String referenceType, ReplaceSource logSource) {
        if (replacedUrls == null || replacedUrls.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(replacedUrls)
            .filter(urlMapping::containsKey)
            .flatMap(oldUrl -> urlReplaceLogService.saveSuccessLog(
                oldUrl, urlMapping.get(oldUrl), sourceType, sourceName, sourceTitle,
                referenceType, logSource
            ))
            .then();
    }

    /**
     * 记录失败的替换日志（带具体错误原因）
     */
    private Mono<Void> recordFailedLogs(Map<String, String> errors, Map<String, String> urlMapping,
                                         String sourceType, String sourceName, String sourceTitle,
                                         String referenceType, ReplaceSource logSource) {
        if (errors == null || errors.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(errors.entrySet())
            .filter(entry -> urlMapping.containsKey(entry.getKey()))
            .flatMap(entry -> urlReplaceLogService.saveFailedLog(
                entry.getKey(), urlMapping.get(entry.getKey()),
                sourceType, sourceName, sourceTitle, referenceType, logSource, entry.getValue()
            ))
            .then();
    }

    /**
     * 根据类型获取处理器
     */
    private ContentUpdateHandler getHandlerForType(String sourceType) {
        return switch (sourceType) {
            case "Post" -> postHandler;
            case "SinglePage" -> singlePageHandler;
            case "Comment" -> commentHandler;
            case "Reply" -> replyHandler;
            case "User" -> userHandler;
            case "SystemSetting", "PluginSetting", "ThemeSetting", "ConfigMap" -> configMapHandler;
            default -> null;
        };
    }

    @Override
    public Mono<Boolean> replaceInSingleSource(String sourceType, String sourceName, String sourceTitle,
                                                String settingName, String groupKey,
                                                Map<String, String> urlMapping, ReplaceSource logSource) {
        if (urlMapping == null || urlMapping.isEmpty()) {
            return Mono.just(true);
        }

        // 构建替换任务
        ReferenceReplacementTask task = ReferenceReplacementTask.builder()
            .urlMapping(urlMapping)
            .source(logSource)
            .build();
        ReferenceReplacementResult result = ReferenceReplacementResult.builder().build();

        // 解析标题和引用类型（支持逗号分隔的多个 groupKey）
        return resolveLogTitleAndRefTypes(sourceType, sourceTitle, settingName, groupKey)
            .flatMap(titleAndRefTypes -> {
                final String finalTitle = titleAndRefTypes[0];
                final String finalRefType = titleAndRefTypes[1];

                // 插件类型特殊处理
                if ("Moment".equals(sourceType) || "Photo".equals(sourceType) || "Doc".equals(sourceType)) {
                    return pluginHandler.replacePluginUrls(sourceType, sourceName, task, result)
                        .flatMap(replaceResult -> recordReplaceResult(replaceResult, urlMapping,
                            sourceType, sourceName, finalTitle, finalRefType, logSource));
                }

                // 获取处理器
                ContentUpdateHandler handler = getHandlerForType(sourceType);
                if (handler == null) {
                    log.warn("未找到 {} 类型的处理器", sourceType);
                    Map<String, String> errors = new HashMap<>();
                    for (String url : urlMapping.keySet()) {
                        errors.put(url, "未找到处理器: " + sourceType);
                    }
                    return recordFailedLogs(errors, urlMapping, sourceType, sourceName,
                        finalTitle, finalRefType, logSource)
                        .thenReturn(false);
                }

                return handler.replaceUrls(sourceName, task, result)
                    .flatMap(replaceResult -> recordReplaceResult(replaceResult, urlMapping,
                        sourceType, sourceName, finalTitle, finalRefType, logSource));
            });
    }

    /**
     * 根据替换结果记录日志
     */
    private Mono<Boolean> recordReplaceResult(ReplaceResult replaceResult, Map<String, String> urlMapping,
                                               String sourceType, String sourceName, String sourceTitle,
                                               String referenceType, ReplaceSource logSource) {
        // 记录成功的 URL
        Mono<Void> successLogs = recordSuccessLogs(replaceResult.getReplaced(), urlMapping,
            sourceType, sourceName, sourceTitle, referenceType, logSource);

        // 记录失败的 URL
        Mono<Void> failedLogs = recordFailedLogs(replaceResult.getErrors(), urlMapping,
            sourceType, sourceName, sourceTitle, referenceType, logSource);

        return successLogs.then(failedLogs)
            .thenReturn(replaceResult.hasReplaced());
    }

    /**
     * 解析日志标题和引用类型（支持逗号分隔的多个 groupKey）
     * 返回 String[]{title, referenceTypes}
     */
    private Mono<String[]> resolveLogTitleAndRefTypes(String sourceType, String sourceTitle,
                                                       String settingName, String groupKey) {
        if (!isConfigMapType(sourceType)) {
            // 非 ConfigMap：使用第一个 key 解析标题，groupKey 直接作为 referenceType
            String firstKey = groupKey != null && groupKey.contains(",")
                ? groupKey.substring(0, groupKey.indexOf(",")) : groupKey;
            return referenceService.resolveSourceTitle(sourceType, sourceTitle, settingName, firstKey)
                .defaultIfEmpty(sourceTitle != null ? sourceTitle : "")
                .map(title -> new String[]{ title, groupKey != null ? groupKey : "" });
        }

        // ConfigMap：groupKey 可能是逗号分隔的多个 key
        String[] keys = groupKey != null ? groupKey.split(",") : new String[]{};
        if (keys.length <= 1) {
            // 单个 key：解析标题并从中拆分 group label
            return referenceService.resolveSourceTitle(sourceType, sourceTitle, settingName, groupKey)
                .defaultIfEmpty(sourceTitle != null ? sourceTitle : "")
                .map(title -> {
                    if (title.contains(" - ")) {
                        int idx = title.lastIndexOf(" - ");
                        return new String[]{ title.substring(0, idx), title.substring(idx + 3) };
                    }
                    return new String[]{ title, groupKey != null ? groupKey : "" };
                });
        }

        // 多个 key：逐个解析 group label，合并为逗号分隔
        return Flux.fromArray(keys)
            .concatMap(key -> referenceService.resolveSourceTitle(
                    sourceType, sourceTitle, settingName, key.trim())
                .defaultIfEmpty(sourceTitle != null ? sourceTitle : "")
                .map(title -> {
                    if (title.contains(" - ")) {
                        int idx = title.lastIndexOf(" - ");
                        return new String[]{ title.substring(0, idx), title.substring(idx + 3) };
                    }
                    return new String[]{ title, key.trim() };
                }))
            .collectList()
            .map(pairs -> {
                String titleForLog = pairs.get(0)[0];
                String refTypes = pairs.stream()
                    .map(p -> p[1])
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .collect(Collectors.joining(","));
                return new String[]{ titleForLog, refTypes };
            });
    }

    private boolean isConfigMapType(String sourceType) {
        return "ThemeSetting".equals(sourceType)
            || "PluginSetting".equals(sourceType)
            || "SystemSetting".equals(sourceType);
    }

    /**
     * 创建空结果
     */
    private ReferenceReplacementResult createEmptyResult() {
        return ReferenceReplacementResult.builder()
            .totalAttachments(0)
            .executedAt(Instant.now())
            .build();
    }
}
