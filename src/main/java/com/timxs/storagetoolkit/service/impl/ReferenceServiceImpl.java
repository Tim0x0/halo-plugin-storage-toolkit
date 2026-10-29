package com.timxs.storagetoolkit.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import run.halo.app.infra.utils.JsonUtils;
import run.halo.app.infra.ExternalLinkProcessor;
import com.timxs.storagetoolkit.extension.AttachmentReference;
import com.timxs.storagetoolkit.extension.ReferenceScanStatus;
import com.timxs.storagetoolkit.model.CleanupReason;
import com.timxs.storagetoolkit.model.CleanupResult;
import com.timxs.storagetoolkit.service.CleanupLogService;
import com.timxs.storagetoolkit.service.ContentScanner;
import com.timxs.storagetoolkit.service.ReferenceService;
import com.timxs.storagetoolkit.service.SettingsManager;
import com.timxs.storagetoolkit.service.WhitelistService;
import com.timxs.storagetoolkit.service.support.BrokenLinkDetector;
import com.timxs.storagetoolkit.service.support.ReferenceContentScanner;
import com.timxs.storagetoolkit.service.support.AttachmentCleanupGuard;
import com.timxs.storagetoolkit.service.support.ReferenceScanContext;
import com.timxs.storagetoolkit.service.support.RetryUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.Setting;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.SchemeManager;
import org.springframework.data.domain.Sort;

import static run.halo.app.extension.index.query.Queries.equal;

import com.timxs.storagetoolkit.extension.BrokenLink;
import com.timxs.storagetoolkit.extension.BrokenLinkScanStatus;

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
    private final SettingsManager settingsManager;
    private final ReferenceContentScanner referenceContentScanner;
    private final AttachmentCleanupGuard attachmentCleanupGuard;
    private final SchemeManager schemeManager;
    private final ExternalLinkProcessor externalLinkProcessor;
    private final WhitelistService whitelistService;
    private final BrokenLinkDetector brokenLinkDetector;
    private final CleanupLogService cleanupLogService;

    private static final com.fasterxml.jackson.databind.ObjectMapper objectMapper = JsonUtils.mapper();

    private static final GroupVersionKind DOC_TREE_GVK =
        new GroupVersionKind("doc.halo.run", "v1alpha1", "DocTree");

    // 内存中的扫描标志（用于检测服务重启）
    private final AtomicInteger scanningFlag = new AtomicInteger(0);

    @Override
    public Mono<ReferenceScanStatus> startScan() {
        return getScanStatus()
            .flatMap(status -> {
                // 检查是否正在扫描
                if (status.getStatus() != null
                    && ReferenceScanStatus.Phase.SCANNING.equals(status.getStatus().getPhase())) {
                    // 检查内存标志：如果为 0 说明服务重启过，允许重新扫描
                    if (scanningFlag.get() == 0) {
                        log.warn("检测到服务重启，上次扫描已中断，允许重新触发");
                        return doStartScan(status);
                    }
                    return Mono.error(new IllegalStateException("扫描正在进行中"));
                }
                return doStartScan(status);
            });
    }

    /**
     * 执行扫描
     */
    private Mono<ReferenceScanStatus> doStartScan(ReferenceScanStatus status) {
        // 设置内存扫描标志
        scanningFlag.set(1);

        // 更新状态为扫描中
        if (status.getStatus() == null) {
            status.setStatus(new ReferenceScanStatus.ReferenceScanStatusStatus());
        }
        status.getStatus().setPhase(ReferenceScanStatus.Phase.SCANNING);
        status.getStatus().setStartTime(Instant.now());
        status.getStatus().setErrorMessage(null);
        // 归零上一次的统计，避免扫描过程中页面继续显示旧结果
        // （lastScanTime 保留：它表示"上次扫描完成时间"）
        status.getStatus().setTotalAttachments(0);
        status.getStatus().setReferencedCount(0);
        status.getStatus().setUnreferencedCount(0);
        status.getStatus().setUnreferencedSize(0);

        return client.update(status)
            // 断链检测与引用扫描是同一次扫描，其状态同样要在开始时归零。
            // 必须非致命：此时 phase=SCANNING 已落库且 scanningFlag=1，
            // 一旦这里抛错就走不到下面启动 performScan，flag 永远不会被 doFinally 清零，
            // 守卫逻辑会把这个组合判定为"扫描正在进行中"，用户再也无法触发扫描。
            .flatMap(updated -> resetBrokenLinkScanStatus()
                .retryWhen(RetryUtils.optimisticLockRetry())
                .onErrorResume(e -> {
                    log.warn("重置断链扫描状态失败，本次扫描期间将继续显示上次的断链统计: {}", e.getMessage());
                    return Mono.empty();
                })
                .thenReturn(updated))
            .flatMap(updated -> {
                // 异步执行扫描
                performScan(updated)
                    .doFinally(signal -> scanningFlag.set(0))  // 扫描结束时清除标志
                    .subscribe(
                        result -> log.info("扫描完成: {}", result),
                        error -> {
                            log.error("扫描失败", error);
                            // 更新引用扫描状态为错误
                            updateScanError(updated, error.getMessage()).subscribe(
                                v -> {},
                                err -> log.error("更新引用扫描错误状态失败", err)
                            );
                            // 同时更新断链扫描状态为错误（避免断链扫描一直停留在 SCANNING）
                            updateBrokenLinkScanError(error.getMessage()).subscribe(
                                v -> {},
                                err -> log.error("更新断链扫描错误状态失败", err)
                            );
                        }
                    );
                return Mono.just(updated);
            });
    }

    /**
     * 更新断链扫描错误状态
     */
    private Mono<Void> updateBrokenLinkScanError(String errorMessage) {
        return client.fetch(BrokenLinkScanStatus.class, BrokenLinkScanStatus.SINGLETON_NAME)
            .switchIfEmpty(Mono.defer(() -> {
                BrokenLinkScanStatus status = new BrokenLinkScanStatus();
                status.setMetadata(new Metadata());
                status.getMetadata().setName(BrokenLinkScanStatus.SINGLETON_NAME);
                status.setStatus(new BrokenLinkScanStatus.BrokenLinkScanStatusStatus());
                return client.create(status);
            }))
            .flatMap(status -> {
                if (status.getStatus() == null) {
                    status.setStatus(new BrokenLinkScanStatus.BrokenLinkScanStatusStatus());
                }
                status.getStatus().setPhase(BrokenLinkScanStatus.Phase.ERROR);
                status.getStatus().setErrorMessage(errorMessage);
                return client.update(status);
            })
            .then();
    }

    /**
     * 执行实际的扫描逻辑
     */
    private Mono<ReferenceScanStatus> performScan(ReferenceScanStatus status) {
        log.info("开始扫描附件引用...");

        long scanTimestamp = System.currentTimeMillis();

        return deleteAllExistingRecords()
            .then(settingsManager.getAnalysisSettings())
            .flatMap(referenceContentScanner::scan)
            .flatMap(context -> matchAndCreateReferences(context, status, scanTimestamp))
            .onErrorResume(error -> {
                log.error("扫描过程出错", error);
                return updateScanError(status, error.getMessage());
            });
    }

    /**
     * 删除所有现有记录
     */
    private Mono<Void> deleteAllExistingRecords() {
        // 删除旧的引用记录
        Mono<Void> deleteReferences = client.listAll(AttachmentReference.class, ListOptions.builder().build(), Sort.unsorted())
            .flatMap(client::delete)
            .then()
            .doOnSuccess(v -> log.debug("已删除所有旧引用记录"));

        // 删除旧的断链记录
        Mono<Void> deleteBrokenLinks = client.listAll(BrokenLink.class, ListOptions.builder().build(), Sort.unsorted())
            .flatMap(client::delete)
            .then()
            .doOnSuccess(v -> log.debug("已删除所有旧断链记录"));

        return Mono.when(deleteReferences, deleteBrokenLinks);
    }

    /**
     * 匹配附件并创建新的引用关系
     *
     * 匹配逻辑：
     * 1. 完整 URL：精确匹配附件的 permalink（必须完全一致，包括域名）
     * 2. 相对路径 permalink：拼成完整 URL 后再精确匹配
     *
     * 注意：不进行路径部分匹配，确保域名不同的资源不会错误匹配
     */
    private Mono<ReferenceScanStatus> matchAndCreateReferences(
            ReferenceScanContext context,
            ReferenceScanStatus status,
            long scanTimestamp) {

        Map<String, Set<AttachmentReference.ReferenceSource>> fullUrlToSources = context.getFullUrlToSources();
        log.debug("提取到的完整URL: {} 个, 相对路径: {} 个",
            fullUrlToSources.size(), context.getRelativePathToSources().size());

        final AtomicInteger totalCount = new AtomicInteger(0);
        final AtomicInteger referencedCount = new AtomicInteger(0);
        final AtomicLong unreferencedSize = new AtomicLong(0);
        final AtomicInteger brokenLinkCount = new AtomicInteger(0);

        // 用于记录被成功匹配的完整 URL（用于断链检测）
        final Set<String> matchedFullUrls = ConcurrentHashMap.newKeySet();

        return settingsManager.getExcludeSettings()
            .flatMapMany(excludeSettings ->
                client.listAll(Attachment.class, ListOptions.builder().build(), Sort.unsorted())
                    .filter(attachment -> attachment.getMetadata().getDeletionTimestamp() == null)
                    .flatMap(attachment -> {
                        String attachmentName = attachment.getMetadata().getName();
                        String permalink = attachment.getStatus() != null ? attachment.getStatus().getPermalink() : null;
                        long fileSize = attachment.getSpec().getSize() != null ? attachment.getSpec().getSize() : 0;

                        // 排除判断（仅影响引用统计，不影响断链检测的 matchedFullUrls）
                        boolean excluded = isExcludedAttachment(attachment, excludeSettings);

                        Set<AttachmentReference.ReferenceSource> sources = new HashSet<>();
                        if (StringUtils.hasText(permalink)) {
                            // 1. 完整 URL 精确匹配
                            if (fullUrlToSources.containsKey(permalink)) {
                                matchedFullUrls.add(permalink);
                                if (!excluded) {
                                    sources.addAll(fullUrlToSources.get(permalink));
                                }
                            }

                            // 2. 相对路径拼接完整 URL 再匹配
                            if (!contentScanner.isFullUrl(permalink)) {
                                String fullPermalink = externalLinkProcessor.processLink(permalink);
                                if (fullUrlToSources.containsKey(fullPermalink)) {
                                    matchedFullUrls.add(fullPermalink);
                                    if (!excluded) {
                                        sources.addAll(fullUrlToSources.get(fullPermalink));
                                    }
                                }
                            }
                        }

                        // 排除的附件不参与引用统计
                        if (excluded) {
                            return Mono.<Void>empty();
                        }

                        totalCount.incrementAndGet();
                        if (!sources.isEmpty()) {
                            referencedCount.incrementAndGet();
                        } else {
                            unreferencedSize.addAndGet(fileSize);
                        }

                        return createAttachmentReference(attachmentName, sources, scanTimestamp);
                    })
            )
            .then(Mono.defer(() -> {
                // 附件遍历完成后，进行断链检测
                // 使用 BrokenLinkDetector 执行检测
                return settingsManager.getBrokenLinkSettings()
                    .flatMap(brokenLinkSettings -> {
                        return getBrokenLinkWhitelist()
                            .flatMap(whitelist -> {
                                return brokenLinkDetector.detect(
                                    context,
                                    matchedFullUrls,
                                    whitelist,
                                    brokenLinkSettings,
                                    scanTimestamp
                                ).doOnNext(brokenLinkCount::set);
                            });
                    });
            }))
            .then(Mono.defer(() -> {
                int total = totalCount.get();
                int referenced = referencedCount.get();
                long unrefSize = unreferencedSize.get();
                int brokenCount = brokenLinkCount.get();

                // 更新引用扫描状态
                status.getStatus().setPhase(ReferenceScanStatus.Phase.COMPLETED);
                status.getStatus().setLastScanTime(Instant.now());
                status.getStatus().setTotalAttachments(total);
                status.getStatus().setReferencedCount(referenced);
                status.getStatus().setUnreferencedCount(total - referenced);
                status.getStatus().setUnreferencedSize(unrefSize);
                status.getStatus().setErrorMessage(null);

                log.info("扫描完成 - 总附件: {}, 已引用: {}, 未引用: {}, 断链: {}",
                    total, referenced, total - referenced, brokenCount);

                return client.update(status);
            }))
            // 更新断链扫描状态（确保完成后再返回）
            .flatMap(updatedStatus ->
                updateBrokenLinkScanStatus(
                    fullUrlToSources.size(),
                    brokenLinkCount.get()
                ).then(Mono.just(updatedStatus))
            );
    }

    /**
     * 扫描开始时重置断链扫描状态。
     * <p>
     * 断链检测没有独立的扫描流程，它随引用扫描一起产出，因此其状态原先只在扫描
     * 结束时才写入——导致整个扫描过程中页面显示的仍是上一次的断链统计。
     * 这里把计数归零并置为 SCANNING，前端轮询 phase 才能正确反映"扫描中"。
     * lastScanTime 保留，表示上次扫描完成时间。
     */
    private Mono<Void> resetBrokenLinkScanStatus() {
        return client.fetch(BrokenLinkScanStatus.class, BrokenLinkScanStatus.SINGLETON_NAME)
            .switchIfEmpty(Mono.defer(() -> {
                BrokenLinkScanStatus status = new BrokenLinkScanStatus();
                status.setMetadata(new Metadata());
                status.getMetadata().setName(BrokenLinkScanStatus.SINGLETON_NAME);
                status.setStatus(new BrokenLinkScanStatus.BrokenLinkScanStatusStatus());
                return client.create(status);
            }))
            .flatMap(status -> {
                if (status.getStatus() == null) {
                    status.setStatus(new BrokenLinkScanStatus.BrokenLinkScanStatusStatus());
                }
                status.getStatus().setPhase(BrokenLinkScanStatus.Phase.SCANNING);
                status.getStatus().setStartTime(Instant.now());
                status.getStatus().setScannedContentCount(0);
                status.getStatus().setCheckedLinkCount(0);
                status.getStatus().setBrokenLinkCount(0);
                status.getStatus().setErrorMessage(null);
                return client.update(status);
            })
            .then();
    }

    /**
     * 更新断链扫描状态
     */
    private Mono<Void> updateBrokenLinkScanStatus(int checkedLinkCount, int brokenLinkCount) {
        return client.fetch(BrokenLinkScanStatus.class, BrokenLinkScanStatus.SINGLETON_NAME)
            .switchIfEmpty(Mono.defer(() -> {
                BrokenLinkScanStatus status = new BrokenLinkScanStatus();
                status.setMetadata(new Metadata());
                status.getMetadata().setName(BrokenLinkScanStatus.SINGLETON_NAME);
                status.setStatus(new BrokenLinkScanStatus.BrokenLinkScanStatusStatus());
                return client.create(status);
            }))
            .flatMap(status -> {
                if (status.getStatus() == null) {
                    status.setStatus(new BrokenLinkScanStatus.BrokenLinkScanStatusStatus());
                }
                status.getStatus().setPhase(BrokenLinkScanStatus.Phase.COMPLETED);
                status.getStatus().setLastScanTime(Instant.now());
                status.getStatus().setCheckedLinkCount(checkedLinkCount);
                status.getStatus().setBrokenLinkCount(brokenLinkCount);
                status.getStatus().setErrorMessage(null);
                return client.update(status);
            })
            .then();
    }

    /**
     * 判断附件是否在排除分组或排除策略中
     */
    private boolean isExcludedAttachment(Attachment attachment,
                                         SettingsManager.ExcludeSettings excludeSettings) {
        String groupName = attachment.getSpec().getGroupName();
        if (groupName != null && excludeSettings.excludeGroups().contains(groupName)) {
            return true;
        }
        String policyName = attachment.getSpec().getPolicyName();
        if (policyName != null && excludeSettings.excludePolicies().contains(policyName)) {
            return true;
        }
        return false;
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

    /**
     * 获取断链忽略白名单（包含 URL 和匹配模式）
     */
    private Mono<List<WhitelistService.WhitelistItem>> getBrokenLinkWhitelist() {
        return whitelistService.list()
            .collectList()
            .doOnSuccess(whitelist -> log.debug("断链白名单读取完成: {} 条", whitelist.size()));
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
            client.listAll(Attachment.class, ListOptions.builder().build(), Sort.unsorted())
                .filter(attachment -> attachment.getMetadata().getDeletionTimestamp() == null)
                .collectList(),
            refMapMono,
            settingsManager.getExcludeSettings()
        ).map(tuple -> {
            List<Attachment> attachments = tuple.getT1();
            Map<String, AttachmentReference> refMap = tuple.getT2();
            SettingsManager.ExcludeSettings excludeSettings = tuple.getT3();

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
            .filter(attachment -> attachment.getMetadata().getDeletionTimestamp() == null)
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

    @Override
    public Mono<String> resolveSourceTitle(String sourceType, String sourceTitle,
                                            String settingName, String groupKey) {
        if (!StringUtils.hasText(sourceTitle)) {
            return Mono.just(sourceTitle != null ? sourceTitle : "");
        }

        // ConfigMap 类型（ThemeSetting/PluginSetting/SystemSetting）: 解析为 "displayName - groupLabel" 格式
        if (isConfigMapType(sourceType) && StringUtils.hasText(settingName) && StringUtils.hasText(groupKey)) {
            return getSettingGroupLabel(settingName, groupKey)
                .map(groupLabel -> {
                    // 从原标题中提取 displayName（去掉 " 主题设置"、" 插件设置"、"系统设置" 后缀）
                    String displayName = extractDisplayName(sourceTitle, sourceType);
                    return displayName + " - " + groupLabel;
                })
                .defaultIfEmpty(sourceTitle);
        }

        // Comment 类型: sourceTitle 格式是 "Kind:name"（如 "Post:uuid"），需要解析关联内容的标题
        if ("Comment".equals(sourceType)) {
            int colonIndex = sourceTitle.indexOf(':');
            if (colonIndex > 0) {
                String kind = sourceTitle.substring(0, colonIndex);
                String name = sourceTitle.substring(colonIndex + 1);
                return resolveSubjectTitle(kind, name)
                    .map(title -> title + " - 评论")
                    .defaultIfEmpty(sourceTitle + " - 评论");
            }
            return Mono.just(sourceTitle + " - 评论");
        }

        // Reply 类型: sourceTitle 格式是 "Comment:name"，需要解析关联评论的内容标题
        if ("Reply".equals(sourceType) && sourceTitle.startsWith("Comment:")) {
            String commentName = sourceTitle.substring("Comment:".length());
            return client.fetch(Comment.class, commentName)
                .flatMap(comment -> {
                    var subjectRef = comment.getSpec().getSubjectRef();
                    if (subjectRef == null) {
                        return Mono.just(sourceTitle + " - 回复");
                    }
                    return resolveSubjectTitle(subjectRef.getKind(), subjectRef.getName())
                        .map(title -> title + " - 回复");
                })
                .defaultIfEmpty(sourceTitle + " - 回复");
        }

        // Doc 类型: sourceTitle 格式是 "Doc:name"，需要解析文档标题
        if ("Doc".equals(sourceType) && sourceTitle.startsWith("Doc:")) {
            String docName = sourceTitle.substring("Doc:".length());
            String refTypeLabel = translateReferenceType(groupKey);
            return resolveDocInfo(docName)
                .map(info -> StringUtils.hasText(refTypeLabel)
                    ? info.title() + " - " + refTypeLabel
                    : info.title())
                .defaultIfEmpty(StringUtils.hasText(refTypeLabel)
                    ? sourceTitle + " - " + refTypeLabel
                    : sourceTitle);
        }

        // 其他类型：添加引用类型后缀
        if (StringUtils.hasText(groupKey)) {
            String refTypeLabel = translateReferenceType(groupKey);
            return Mono.just(sourceTitle + " - " + refTypeLabel);
        }

        // 其他类型直接返回原值
        return Mono.just(sourceTitle);
    }

    /**
     * 翻译引用类型为中文
     */
    private String translateReferenceType(String referenceType) {
        if (!StringUtils.hasText(referenceType)) {
            return "";
        }
        return switch (referenceType) {
            case "cover" -> "封面";
            case "content" -> "内容";
            case "draft" -> "草稿";
            case "comment" -> "评论";
            case "reply" -> "回复";
            case "media" -> "媒体";
            case "avatar" -> "头像";
            case "icon" -> "图标";
            case "decoration" -> "装饰";
            case "identity" -> "标识";
            default -> referenceType;
        };
    }

    /**
     * 判断是否为 ConfigMap 类型
     */
    private boolean isConfigMapType(String sourceType) {
        return "ThemeSetting".equals(sourceType)
            || "PluginSetting".equals(sourceType)
            || "SystemSetting".equals(sourceType);
    }

    /**
     * 从原标题中提取 displayName
     * 原标题格式：
     * - ThemeSetting: "displayName 主题设置"
     * - PluginSetting: "displayName 插件设置"
     * - SystemSetting: "系统设置"
     */
    private String extractDisplayName(String sourceTitle, String sourceType) {
        if ("SystemSetting".equals(sourceType)) {
            return "系统设置";
        }
        String suffix = "ThemeSetting".equals(sourceType) ? " 主题设置" : " 插件设置";
        if (sourceTitle.endsWith(suffix)) {
            return sourceTitle.substring(0, sourceTitle.length() - suffix.length());
        }
        return sourceTitle;
    }

    /**
     * 解析关联内容的标题
     */
    private Mono<String> resolveSubjectTitle(String kind, String name) {
        return switch (kind) {
            case "Post" -> client.fetch(Post.class, name)
                .map(post -> post.getSpec().getTitle());
            case "SinglePage" -> client.fetch(SinglePage.class, name)
                .map(page -> page.getSpec().getTitle());
            case "Moment" -> Mono.just("瞬间");
            case "DocTree" -> resolveDocTreeInfo(name)
                .map(SubjectInfo::title);
            default -> Mono.empty();
        };
    }

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

    @Override
    public Mono<CleanupResult> deleteUnreferenced(List<String> attachmentNames) {
        if (attachmentNames == null || attachmentNames.isEmpty()) {
            return Mono.error(new IllegalArgumentException("附件列表不能为空"));
        }
        List<String> requestedNames = new ArrayList<>(new LinkedHashSet<>(attachmentNames));

        log.info("删除未引用文件，附件数: {}", attachmentNames.size());

        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger deletedCount = new AtomicInteger(0);
        AtomicLong freedSize = new AtomicLong(0);

        return attachmentCleanupGuard.validateUnreferenced(requestedNames)
            .thenMany(Flux.fromIterable(requestedNames))
            .flatMap(attachmentName -> deleteUnreferencedAttachment(attachmentName, errors, deletedCount, freedSize))
            .then(Mono.defer(() -> updateScanStatusAfterDelete(deletedCount.get(), freedSize.get())))
            .then(Mono.fromSupplier(() -> new CleanupResult(
                deletedCount.get(),
                requestedNames.size() - deletedCount.get(),
                freedSize.get(),
                errors
            )));
    }

    /**
     * 删除单个未引用附件
     */
    private Mono<Void> deleteUnreferencedAttachment(String attachmentName,
                                                     List<String> errors,
                                                     AtomicInteger deletedCount,
                                                     AtomicLong freedSize) {
        return client.fetch(Attachment.class, attachmentName)
            .filter(attachment -> attachment.getMetadata().getDeletionTimestamp() == null)
            .switchIfEmpty(Mono.error(new IllegalStateException("附件不存在或已删除")))
            .flatMap(attachment -> {
                String displayName = attachment.getSpec().getDisplayName();
                long fileSize = attachment.getSpec().getSize() != null ? attachment.getSpec().getSize() : 0;

                return client.delete(attachment)
                    .doOnNext(deleted -> {
                        deletedCount.incrementAndGet();
                        freedSize.addAndGet(fileSize);
                        log.debug("已删除未引用文件: {}", displayName);
                    })
                    .then(Mono.defer(() -> cleanupLogService.saveLog(
                            attachmentName, displayName, fileSize, CleanupReason.UNREFERENCED, null))
                        .onErrorResume(error -> {
                            log.warn("附件已删除，但保存清理日志失败: {}", attachmentName, error);
                            return Mono.empty();
                        }))
                    .then();
            })
            .onErrorResume(e -> {
                log.error("删除附件 {} 失败: {}", attachmentName, e.getMessage());
                errors.add(attachmentName + ": " + e.getMessage());
                return Mono.empty();
            });
    }

    /**
     * 删除后更新扫描状态统计
     */
    private Mono<Void> updateScanStatusAfterDelete(int deletedCount, long freedSize) {
        return getScanStatus()
            .flatMap(status -> {
                if (status.getStatus() != null) {
                    int currentUnrefCount = status.getStatus().getUnreferencedCount();
                    long currentUnrefSize = status.getStatus().getUnreferencedSize();
                    status.getStatus().setUnreferencedCount(Math.max(0, currentUnrefCount - deletedCount));
                    status.getStatus().setUnreferencedSize(Math.max(0, currentUnrefSize - freedSize));
                    
                    int currentTotal = status.getStatus().getTotalAttachments();
                    status.getStatus().setTotalAttachments(Math.max(0, currentTotal - deletedCount));
                }
                return client.update(status);
            })
            .then();
    }
}
