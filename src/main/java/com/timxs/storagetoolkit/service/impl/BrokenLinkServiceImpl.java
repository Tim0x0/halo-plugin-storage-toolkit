package com.timxs.storagetoolkit.service.impl;

import com.timxs.storagetoolkit.extension.BrokenLink;
import com.timxs.storagetoolkit.extension.BrokenLinkScanStatus;
import com.timxs.storagetoolkit.extension.BrokenLinkScanStatus.BrokenLinkScanStatusStatus;
import com.timxs.storagetoolkit.model.BrokenLinkReplaceResult;
import com.timxs.storagetoolkit.model.BrokenLinkVo;
import com.timxs.storagetoolkit.model.BrokenLinkVo.BrokenLinkSource;
import com.timxs.storagetoolkit.model.ReplaceSource;
import com.timxs.storagetoolkit.service.BrokenLinkService;
import com.timxs.storagetoolkit.service.ReferenceReplacerService;
import com.timxs.storagetoolkit.service.ReferenceService;
import com.timxs.storagetoolkit.service.support.UrlReplacer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.ExternalLinkProcessor;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 断链扫描服务实现
 * 断链检测现在由 ReferenceService 在扫描时同步完成
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrokenLinkServiceImpl implements BrokenLinkService {

    private final ReactiveExtensionClient client;
    private final ReferenceService referenceService;
    private final ReferenceReplacerService referenceReplacerService;

    private final ExternalLinkProcessor externalLinkProcessor;

    @Override
    public Mono<BrokenLinkScanStatus> startScan() {
        // 断链扫描现在由引用统计扫描同步完成
        // 先更新状态为扫描中，然后调用引用扫描（异步执行）
        return getStatus()
            .flatMap(status -> {
                // 检查是否正在扫描
                if (status.getStatus() != null
                    && BrokenLinkScanStatus.Phase.SCANNING.equals(status.getStatus().getPhase())) {
                    return Mono.error(new IllegalStateException("扫描正在进行中"));
                }

                // 更新状态为扫描中
                if (status.getStatus() == null) {
                    status.setStatus(new BrokenLinkScanStatusStatus());
                }
                status.getStatus().setPhase(BrokenLinkScanStatus.Phase.SCANNING);
                status.getStatus().setStartTime(Instant.now());
                status.getStatus().setErrorMessage(null);

                return client.update(status)
                    .flatMap(updated -> {
                        // 异步执行扫描
                        referenceService.startScan()
                            .subscribe(
                                result -> log.info("断链扫描完成"),
                                error -> {
                                    log.error("断链扫描失败", error);
                                    // 更新状态为错误
                                    updateScanError(error.getMessage()).subscribe(
                                        v -> {},
                                        err -> log.error("更新断链扫描错误状态失败", err)
                                    );
                                }
                            );
                        return Mono.just(updated);
                    });
            });
    }

    private Mono<Void> updateScanError(String errorMessage) {
        return getStatus()
            .flatMap(status -> {
                if (status.getStatus() == null) {
                    status.setStatus(new BrokenLinkScanStatusStatus());
                }
                status.getStatus().setPhase(BrokenLinkScanStatus.Phase.ERROR);
                status.getStatus().setErrorMessage(errorMessage);
                return client.update(status);
            })
            .then();
    }

    @Override
    public Mono<BrokenLinkScanStatus> getStatus() {
        return client.fetch(BrokenLinkScanStatus.class, BrokenLinkScanStatus.SINGLETON_NAME)
            .switchIfEmpty(Mono.defer(() -> {
                BrokenLinkScanStatus status = new BrokenLinkScanStatus();
                status.setMetadata(new Metadata());
                status.getMetadata().setName(BrokenLinkScanStatus.SINGLETON_NAME);
                status.setStatus(new BrokenLinkScanStatusStatus());
                return client.create(status);
            }));
    }

    private Mono<ListResult<BrokenLinkVo>> listBrokenLinks(int page, int size) {
        return listBrokenLinks(page, size, null, null, null, null);
    }

    private Mono<ListResult<BrokenLinkVo>> listBrokenLinks(int page, int size, String sourceType, String keyword) {
        return listBrokenLinks(page, size, sourceType, keyword, null, null);
    }

    @Override
    public Mono<ListResult<BrokenLinkVo>> listBrokenLinks(int page, int size, String sourceType, String keyword, String reason, String sort) {
        return client.listAll(BrokenLink.class, ListOptions.builder().build(), Sort.unsorted())
            .filter(link -> {
                // 过滤待删除和已删除的记录（与引用记录逻辑一致）
                if (link.getMetadata().getDeletionTimestamp() != null) {
                    return false;
                }
                if (link.getStatus() != null && link.getStatus().getPendingDelete() != null && link.getStatus().getPendingDelete()) {
                    return false;
                }
                if (link.getSpec() == null || link.getStatus() == null) return false;

                // 按来源类型过滤（检查 sources 列表中是否包含该类型）
                if (StringUtils.hasText(sourceType)) {
                    boolean hasType = link.getStatus().getSources() != null
                        && link.getStatus().getSources().stream()
                            .anyMatch(s -> sourceType.equals(s.getSourceType()));
                    if (!hasType) {
                        return false;
                    }
                }

                // 按关键词过滤（搜索 URL 或来源标题）
                if (StringUtils.hasText(keyword)) {
                    String url = link.getSpec().getUrl();
                    boolean urlMatch = url != null && url.toLowerCase().contains(keyword.toLowerCase());

                    boolean titleMatch = link.getStatus().getSources() != null
                        && link.getStatus().getSources().stream()
                            .anyMatch(s -> s.getSourceTitle() != null
                                && s.getSourceTitle().toLowerCase().contains(keyword.toLowerCase()));

                    if (!urlMatch && !titleMatch) {
                        return false;
                    }
                }

                // 按断链原因过滤
                if (StringUtils.hasText(reason)) {
                    String linkReason = link.getStatus().getReason();
                    if (linkReason == null) {
                        return false;
                    }
                    if ("HTTP_ERROR".equals(reason)) {
                        // 匹配所有 HTTP 状态码错误（如 "HTTP 404"、"HTTP 403"）
                        if (!linkReason.startsWith("HTTP ")) {
                            return false;
                        }
                    } else {
                        // 精确匹配：HTTP_TIMEOUT、CONNECTION_FAILED、ATTACHMENT_NOT_FOUND
                        if (!reason.equals(linkReason)) {
                            return false;
                        }
                    }
                }

                return true;
            })
            .collectList()
            .map(links -> {
                // 转换为 VO 列表
                List<BrokenLinkVo> voList = links.stream()
                    .map(link -> {
                        String url = link.getSpec().getUrl();
                        Instant discoveredAt = link.getStatus() != null ? link.getStatus().getDiscoveredAt() : null;
                        String linkReason = link.getStatus() != null ? link.getStatus().getReason() : null;
                        String originalUrl = link.getStatus() != null ? link.getStatus().getOriginalUrl() : null;

                        // 构建来源列表
                        List<BrokenLinkSource> sources = link.getStatus() != null && link.getStatus().getSources() != null
                            ? link.getStatus().getSources().stream()
                                .map(s -> new BrokenLinkSource(
                                    link.getMetadata().getName(),
                                    s.getSourceType(),
                                    s.getSourceName(),
                                    s.getSourceTitle(),
                                    s.getSourceUrl(),
                                    s.getDeleted(),
                                    s.getReferenceType(),
                                    s.getSettingName()
                                ))
                                .toList()
                            : List.of();

                        int sourceCount = link.getStatus() != null ? link.getStatus().getSourceCount() : 0;

                        return new BrokenLinkVo(url, originalUrl, sources, sourceCount, discoveredAt, linkReason);
                    })
                    .sorted(createBrokenLinkComparator(sort))
                    .collect(Collectors.toList());

                int total = voList.size();
                int start = (page - 1) * size;
                int end = Math.min(start + size, total);

                List<BrokenLinkVo> pageItems = start < total
                    ? voList.subList(start, end)
                    : List.of();

                return new ListResult<>(page, size, total, pageItems);
            });
    }

    @Override
    public Mono<Void> clearAll() {
        log.info("清空断链扫描结果...");

        return client.listAll(BrokenLink.class, ListOptions.builder().build(), Sort.unsorted())
            .flatMap(link -> client.delete(link))
            .then(Mono.defer(() -> getStatus()
                .flatMap(status -> {
                    if (status.getStatus() != null) {
                        status.getStatus().setPhase(null);
                        status.getStatus().setLastScanTime(null);
                        status.getStatus().setStartTime(null);
                        status.getStatus().setScannedContentCount(0);
                        status.getStatus().setCheckedLinkCount(0);
                        status.getStatus().setBrokenLinkCount(0);
                        status.getStatus().setErrorMessage(null);
                    }
                    return client.update(status);
                })))
            .then()
            .doOnSuccess(v -> log.info("断链扫描结果已清空"));
    }

    /**
     * 创建断链排序比较器
     * @param sort 排序参数，格式：field,asc|desc
     * @return 比较器
     */
    private java.util.Comparator<BrokenLinkVo> createBrokenLinkComparator(String sort) {
        // 解析排序参数
        boolean desc = true;
        String sortField = "sourceCount";
        if (StringUtils.hasText(sort)) {
            String[] parts = sort.split(",");
            sortField = parts[0];
            if (parts.length > 1) {
                desc = "desc".equalsIgnoreCase(parts[1]);
            }
        }

        final String finalSortField = sortField;
        final boolean finalDesc = desc;

        return (a, b) -> {
            int result;
            if ("discoveredAt".equals(finalSortField)) {
                // 按发现时间排序
                if (a.discoveredAt() == null && b.discoveredAt() == null) result = 0;
                else if (a.discoveredAt() == null) result = 1;
                else if (b.discoveredAt() == null) result = -1;
                else result = a.discoveredAt().compareTo(b.discoveredAt());
            } else {
                // 默认按出现次数排序
                result = Integer.compare(a.sourceCount(), b.sourceCount());
            }
            return finalDesc ? -result : result;
        };
    }

    @Override
    public Mono<BrokenLinkReplaceResult> replaceBrokenLink(String oldUrl, String newUrl) {
        log.info("开始替换断链: {} -> {}", oldUrl, newUrl);

        // 1. 查找 BrokenLink 记录
        return client.listAll(BrokenLink.class, ListOptions.builder().build(), Sort.unsorted())
            .filter(link -> {
                if (link.getMetadata().getDeletionTimestamp() != null) return false;
                if (link.getStatus() != null && link.getStatus().getPendingDelete() != null
                    && link.getStatus().getPendingDelete()) return false;
                return link.getSpec() != null && oldUrl.equals(link.getSpec().getUrl());
            })
            .collectList()
            .flatMap(links -> {
                if (links.isEmpty()) {
                    log.warn("未找到断链记录: {}", oldUrl);
                    return Mono.just(BrokenLinkReplaceResult.builder()
                        .allSuccess(false)
                        .build());
                }

                BrokenLink brokenLink = links.get(0);
                List<BrokenLink.BrokenLinkSource> sources = brokenLink.getStatus() != null
                    ? brokenLink.getStatus().getSources()
                    : List.of();

                if (sources.isEmpty()) {
                    log.warn("断链记录没有来源，直接删除: {}", oldUrl);
                    return client.delete(brokenLink)
                        .thenReturn(BrokenLinkReplaceResult.builder()
                            .allSuccess(true)
                            .brokenLinkDeleted(true)
                            .build());
                }

                BrokenLinkReplaceResult result = BrokenLinkReplaceResult.builder()
                    .totalSources(sources.size())
                    .build();

                // 2. 构建双形式 URL 映射（完整 URL + 相对路径）
                Map<String, String> urlMapping = UrlReplacer.buildDualFormMapping(
                    oldUrl, newUrl, externalLinkProcessor);
                if (urlMapping.size() > 1) {
                    log.debug("断链替换将同时处理两种形式: {}", urlMapping.keySet());
                }

                // 3. 按 (sourceType, sourceName) 分组，每个实体只替换一次
                List<BrokenLink.BrokenLinkSource> successSources = new ArrayList<>();

                // 分组键：sourceType + "|" + sourceName
                Map<String, List<BrokenLink.BrokenLinkSource>> grouped = sources.stream()
                    .collect(Collectors.groupingBy(
                        s -> s.getSourceType() + "|" + s.getSourceName(),
                        LinkedHashMap::new, Collectors.toList()));

                return Flux.fromIterable(grouped.values())
                    .concatMap(group -> {
                        // 用组内第一个 source 执行替换，传入合并后的 referenceTypes
                        BrokenLink.BrokenLinkSource firstSource = group.get(0);
                        String joinedRefTypes = group.stream()
                            .map(BrokenLink.BrokenLinkSource::getReferenceType)
                            .filter(r -> r != null && !r.isEmpty())
                            .distinct()
                            .collect(Collectors.joining(","));

                        return replaceInSource(firstSource, result, urlMapping, joinedRefTypes)
                            .doOnNext(success -> {
                                if (success != null) {
                                    // 替换成功，组内所有 source 都视为成功
                                    successSources.addAll(group);
                                    // 额外的 source 也计入成功数（第一个已在 replaceInSource 中计过）
                                    for (int i = 1; i < group.size(); i++) {
                                        result.incrementSuccess();
                                    }
                                }
                            });
                    })
                    .then(Mono.defer(() -> {
                        // 4. 处理 BrokenLink 记录
                        if (result.getFailedCount() == 0) {
                            // 全部成功：删除 BrokenLink 记录
                            return client.delete(brokenLink)
                                .then(Mono.fromCallable(() -> {
                                    result.setBrokenLinkDeleted(true);
                                    log.info("断链替换完成，已删除断链记录: {}", oldUrl);
                                    return result;
                                }));
                        } else if (result.getSuccessCount() > 0) {
                            // 部分成功：更新 sources 列表
                            List<BrokenLink.BrokenLinkSource> remainingSources = sources.stream()
                                .filter(s -> successSources.stream().noneMatch(
                                    ss -> ss.getSourceType().equals(s.getSourceType())
                                        && ss.getSourceName().equals(s.getSourceName())))
                                .toList();
                            if (remainingSources.isEmpty()) {
                                // 所有来源都已处理：删除断链记录
                                return client.delete(brokenLink)
                                    .then(Mono.fromCallable(() -> {
                                        result.setBrokenLinkDeleted(true);
                                        log.info("断链替换完成，已删除断链记录: {}", oldUrl);
                                        return result;
                                    }));
                            }
                            brokenLink.getStatus().setSources(new ArrayList<>(remainingSources));
                            brokenLink.getStatus().setSourceCount(remainingSources.size());
                            return client.update(brokenLink)
                                .then(Mono.fromCallable(() -> {
                                    log.info("断链部分替换完成，更新来源列表: {} -> {} 个", oldUrl, remainingSources.size());
                                    return result;
                                }));
                        } else {
                            // 全部失败
                            log.warn("断链替换全部失败: {}", oldUrl);
                            return Mono.just(result);
                        }
                    }));
            });
    }

    /**
     * 在单个来源中执行替换
     */
    private Mono<BrokenLink.BrokenLinkSource> replaceInSource(BrokenLink.BrokenLinkSource source,
                                                               BrokenLinkReplaceResult result,
                                                               Map<String, String> urlMapping,
                                                               String referenceTypes) {
        String sourceType = source.getSourceType();
        String sourceName = source.getSourceName();
        String sourceTitle = source.getSourceTitle();
        String settingName = source.getSettingName();

        // 使用通用替换方法（传递合并后的 referenceTypes）
        return referenceReplacerService.replaceInSingleSource(
                sourceType, sourceName, sourceTitle, settingName, referenceTypes,
                urlMapping, ReplaceSource.BROKEN_LINK)
            .flatMap(success -> {
                if (success) {
                    result.incrementSuccess();
                    return Mono.just(source);
                } else {
                    result.addFailure(sourceType, sourceName, sourceTitle, "替换失败");
                    return Mono.empty();
                }
            });
    }
}
