package com.timxs.storagetoolkit.service.impl;

import com.timxs.storagetoolkit.extension.BrokenLink;
import com.timxs.storagetoolkit.extension.BrokenLinkScanStatus;
import com.timxs.storagetoolkit.extension.BrokenLinkScanStatus.BrokenLinkScanStatusStatus;
import com.timxs.storagetoolkit.model.BrokenLinkVo;
import com.timxs.storagetoolkit.model.BrokenLinkVo.BrokenLinkSource;
import com.timxs.storagetoolkit.service.BrokenLinkService;
import com.timxs.storagetoolkit.service.ReferenceService;
import com.timxs.storagetoolkit.service.WhitelistService;
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
    private final WhitelistService whitelistService;

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
                                    updateScanError(error.getMessage()).subscribe();
                                }
                            );
                        return Mono.just(updated);
                    });
            });
    }

    /**
     * 更新扫描错误状态（重新获取最新状态避免乐观锁冲突）
     */
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

    @Override
    public Mono<ListResult<BrokenLinkVo>> listBrokenLinks(int page, int size) {
        return listBrokenLinks(page, size, null, null);
    }

    @Override
    public Mono<ListResult<BrokenLinkVo>> listBrokenLinks(int page, int size, String sourceType, String keyword) {
        return listBrokenLinks(page, size, sourceType, keyword, null);
    }

    @Override
    public Mono<ListResult<BrokenLinkVo>> listBrokenLinks(int page, int size, String sourceType, String keyword, String sort) {
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

                return true;
            })
            .collectList()
            .map(links -> {
                // 转换为 VO 列表
                List<BrokenLinkVo> voList = links.stream()
                    .map(link -> {
                        String url = link.getSpec().getUrl();
                        Instant discoveredAt = link.getStatus() != null ? link.getStatus().getDiscoveredAt() : null;

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

                        return new BrokenLinkVo(url, sources, sourceCount, discoveredAt);
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
    public Mono<List<String>> getSourceTypes() {
        return client.listAll(BrokenLink.class, ListOptions.builder().build(), Sort.unsorted())
            .filter(link -> {
                // 过滤待删除和已删除的记录（与引用记录逻辑一致）
                if (link.getMetadata().getDeletionTimestamp() != null) {
                    return false;
                }
                if (link.getStatus() != null && link.getStatus().getPendingDelete() != null && link.getStatus().getPendingDelete()) {
                    return false;
                }
                return link.getStatus() != null && link.getStatus().getSources() != null;
            })
            .flatMap(link -> Flux.fromIterable(link.getStatus().getSources())
                .map(s -> s.getSourceType())
                .filter(StringUtils::hasText))
            .distinct()
            .collectList();
    }

    @Override
    public Mono<Void> addToWhitelist(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return Mono.empty();
        }

        log.info("添加 {} 个 URL 到白名单", urls.size());

        // 使用 WhitelistService 批量添加
        return whitelistService.addBatch(urls)
            .then(Mono.defer(() -> {
                // 删除已添加到白名单的断链记录
                return client.listAll(BrokenLink.class, ListOptions.builder().build(), Sort.unsorted())
                    .filter(link -> {
                        // 过滤待删除和已删除的记录（与引用记录逻辑一致）
                        if (link.getMetadata().getDeletionTimestamp() != null) {
                            return false;
                        }
                        if (link.getStatus() != null && link.getStatus().getPendingDelete() != null && link.getStatus().getPendingDelete()) {
                            return false;
                        }
                        return link.getSpec() != null && urls.contains(link.getSpec().getUrl());
                    })
                    .flatMap(client::delete)
                    .then();
            }))
            .doOnSuccess(v -> log.info("成功添加 {} 个 URL 到白名单", urls.size()))
            .doOnError(e -> log.error("添加白名单失败", e));
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
}
