package com.timxs.storagetoolkit.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.timxs.storagetoolkit.model.CategoryStats;
import com.timxs.storagetoolkit.model.StatisticsData;
import com.timxs.storagetoolkit.model.TotalStats;
import com.timxs.storagetoolkit.service.StatisticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.attachment.Group;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import org.springframework.data.domain.Sort;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 存储统计服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {

    private final ReactiveExtensionClient client;
    
    /**
     * 统计数据缓存，2分钟过期
     */
    private static final Cache<String, StatisticsData> statsCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(2))
        .maximumSize(1)
        .build();
    
    private static final String CACHE_KEY = "statistics";

    @Override
    public Mono<StatisticsData> getStatistics() {
        // 优先从缓存获取
        StatisticsData cached = statsCache.getIfPresent(CACHE_KEY);
        if (cached != null) {
            log.debug("Statistics cache hit");
            return Mono.just(cached);
        }
        
        // 缓存未命中，查询数据库
        // 注意：并发请求可能同时触发查询，但结果都会被缓存，影响不大
        log.debug("Statistics cache miss, fetching from database");
        return doGetStatistics()
            .doOnNext(data -> statsCache.put(CACHE_KEY, data));
    }
    
    /**
     * 实际获取统计数据的逻辑
     */
    private Mono<StatisticsData> doGetStatistics() {
        // 并行获取策略和分组名称映射
        Mono<Map<String, String>> policyNamesMono = client.listAll(Policy.class, ListOptions.builder().build(), Sort.unsorted())
            .collectMap(
                p -> p.getMetadata().getName(),
                p -> p.getSpec().getDisplayName()
            );
        Mono<Map<String, String>> groupNamesMono = client.listAll(Group.class, ListOptions.builder().build(), Sort.unsorted())
            .collectMap(
                g -> g.getMetadata().getName(),
                g -> g.getSpec().getDisplayName()
            );

        // 使用 reduce 流式聚合附件数据，避免一次性加载所有附件到内存
        return Mono.zip(policyNamesMono, groupNamesMono)
            .flatMap(tuple -> {
                Map<String, String> policyNames = tuple.getT1();
                Map<String, String> groupNames = tuple.getT2();
                
                return client.listAll(Attachment.class, ListOptions.builder().build(), Sort.unsorted())
                    .filter(attachment -> attachment.getMetadata().getDeletionTimestamp() == null)
                    .reduce(new AggregationState(), (state, attachment) -> {
                        if (attachment.getSpec() == null) return state;
                        
                        long size = attachment.getSpec().getSize() != null ? attachment.getSpec().getSize() : 0;
                        state.totalSize += size;
                        state.count++;
                        
                        // 按类型聚合
                        String typeKey = classifyMediaType(attachment.getSpec().getMediaType());
                        state.byType.computeIfAbsent(typeKey, k -> new long[2]);
                        state.byType.get(typeKey)[0]++;
                        state.byType.get(typeKey)[1] += size;
                        
                        // 按策略聚合
                        String policyKey = attachment.getSpec().getPolicyName();
                        if (policyKey != null && !policyKey.isBlank()) {
                            state.byPolicy.computeIfAbsent(policyKey, k -> new long[2]);
                            state.byPolicy.get(policyKey)[0]++;
                            state.byPolicy.get(policyKey)[1] += size;
                        }
                        
                        // 按分组聚合
                        String groupKey = attachment.getSpec().getGroupName();
                        if (groupKey == null || groupKey.isBlank()) {
                            groupKey = "_ungrouped";
                        }
                        state.byGroup.computeIfAbsent(groupKey, k -> new long[2]);
                        state.byGroup.get(groupKey)[0]++;
                        state.byGroup.get(groupKey)[1] += size;
                        
                        return state;
                    })
                    .map(state -> buildStatisticsDataFromState(state, policyNames, groupNames));
            });
    }
    
    /**
     * 聚合状态（用于 reduce 操作）
     */
    private static class AggregationState {
        int count = 0;
        long totalSize = 0;
        Map<String, long[]> byType = new HashMap<>();
        Map<String, long[]> byPolicy = new HashMap<>();
        Map<String, long[]> byGroup = new HashMap<>();
    }
    
    /**
     * 从聚合状态构建统计数据
     */
    private StatisticsData buildStatisticsDataFromState(
            AggregationState state,
            Map<String, String> policyNames,
            Map<String, String> groupNames) {
        
        // 构建总体统计
        TotalStats total = TotalStats.builder()
            .attachmentCount(state.count)
            .totalSize(state.totalSize)
            .policyCount(policyNames.size())
            .groupCount(groupNames.size())
            .build();
        
        // 构建分类统计列表
        List<CategoryStats> typeStats = buildTypeStats(state.byType, state.totalSize);
        List<CategoryStats> policyStats = buildPolicyStats(state.byPolicy, policyNames, state.totalSize);
        List<CategoryStats> groupStats = buildGroupStats(state.byGroup, groupNames, state.totalSize);
        
        return StatisticsData.builder()
            .total(total)
            .byType(typeStats)
            .byPolicy(policyStats)
            .byGroup(groupStats)
            .build();
    }

    /**
     * 根据 mediaType 分类文件类型
     */
    private String classifyMediaType(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            return "other";
        }
        
        if (mediaType.startsWith("image/")) {
            return "image";
        } else if (mediaType.startsWith("video/")) {
            return "video";
        } else if (mediaType.startsWith("audio/")) {
            return "audio";
        } else if (isDocumentType(mediaType)) {
            return "document";
        }
        return "other";
    }

    /**
     * 判断是否为文档类型
     */
    private boolean isDocumentType(String mediaType) {
        return mediaType.equals("application/pdf")
            || mediaType.startsWith("application/msword")
            || mediaType.startsWith("application/vnd.openxmlformats-officedocument")
            || mediaType.startsWith("application/vnd.ms-")
            || mediaType.startsWith("text/");
    }

    /**
     * 构建按类型统计列表
     */
    private List<CategoryStats> buildTypeStats(Map<String, long[]> byType, long totalSize) {
        Map<String, String[]> typeConfig = Map.of(
            "image", new String[]{"图片", "image"},
            "video", new String[]{"视频", "video"},
            "audio", new String[]{"音频", "audio"},
            "document", new String[]{"文档", "document"},
            "other", new String[]{"其他", "file"}
        );
        
        // 计算该分类的总大小
        long categoryTotal = byType.values().stream().mapToLong(d -> d[1]).sum();
        
        List<CategoryStats> result = new ArrayList<>();
        for (String key : Arrays.asList("image", "video", "audio", "document", "other")) {
            long[] data = byType.getOrDefault(key, new long[2]);
            String[] config = typeConfig.get(key);
            result.add(CategoryStats.builder()
                .key(key)
                .name(config[0])
                .icon(config[1])
                .count(data[0])
                .size(data[1])
                .percent(calculatePercent(data[1], categoryTotal))
                .build());
        }
        return result;
    }

    /**
     * 构建按策略统计列表 - 显示所有策略（按大小排序）
     */
    private List<CategoryStats> buildPolicyStats(
            Map<String, long[]> byPolicy,
            Map<String, String> policyNames,
            long totalSize) {
        
        // 计算该分类的总大小
        long categoryTotal = byPolicy.values().stream().mapToLong(d -> d[1]).sum();
        
        // 遍历所有策略，包括没有附件的，按大小排序
        return policyNames.entrySet().stream()
            .map(entry -> {
                String key = entry.getKey();
                String displayName = entry.getValue();
                long[] data = byPolicy.getOrDefault(key, new long[2]);
                return CategoryStats.builder()
                    .key("policy-" + key)
                    .name(displayName)
                    .icon("storage")
                    .count(data[0])
                    .size(data[1])
                    .percent(calculatePercent(data[1], categoryTotal))
                    .build();
            })
            .sorted((a, b) -> Long.compare(b.getSize(), a.getSize()))
            .collect(Collectors.toList());
    }

    /**
     * 构建按分组统计列表 - 显示所有分组（包括没有附件的）+ 未分组
     */
    private List<CategoryStats> buildGroupStats(
            Map<String, long[]> byGroup,
            Map<String, String> groupNames,
            long totalSize) {
        
        // 计算该分类的总大小
        long categoryTotal = byGroup.values().stream().mapToLong(d -> d[1]).sum();
        
        List<CategoryStats> result = new ArrayList<>();
        
        // 添加未分组统计
        long[] ungroupedData = byGroup.getOrDefault("_ungrouped", new long[2]);
        result.add(CategoryStats.builder()
            .key("group-_ungrouped")
            .name("未分组")
            .icon("folder")
            .count(ungroupedData[0])
            .size(ungroupedData[1])
            .percent(calculatePercent(ungroupedData[1], categoryTotal))
            .build());
        
        // 遍历所有分组，包括没有附件的
        groupNames.forEach((key, displayName) -> {
            long[] data = byGroup.getOrDefault(key, new long[2]);
            result.add(CategoryStats.builder()
                .key("group-" + key)
                .name(displayName)
                .icon("folder")
                .count(data[0])
                .size(data[1])
                .percent(calculatePercent(data[1], categoryTotal))
                .build());
        });
        
        // 按大小排序
        result.sort((a, b) -> Long.compare(b.getSize(), a.getSize()));
        return result;
    }

    /**
     * 计算百分比（保留两位小数）
     */
    private double calculatePercent(long part, long total) {
        if (total == 0) return 0;
        return Math.round((double) part / total * 10000) / 100.0;
    }
}
