package com.timxs.storagetoolkit.service.impl;

import com.timxs.storagetoolkit.extension.ProcessingLog;
import com.timxs.storagetoolkit.model.ProcessingLogQuery;
import com.timxs.storagetoolkit.service.ProcessingLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 处理日志服务实现
 * 使用 Halo 的 ReactiveExtensionClient 进行数据持久化
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessingLogServiceImpl implements ProcessingLogService {

    /**
     * Halo 响应式扩展客户端，用于操作 Extension 数据
     */
    private final ReactiveExtensionClient client;

    /**
     * 保存处理日志
     * 如果日志没有元数据或名称，会自动生成 UUID 作为名称
     *
     * @param processingLog 要保存的日志对象
     * @return 保存后的日志对象
     */
    @Override
    public Mono<ProcessingLog> save(ProcessingLog processingLog) {
        // 确保元数据存在
        if (processingLog.getMetadata() == null) {
            processingLog.setMetadata(new Metadata());
        }
        // 自动生成唯一名称
        if (processingLog.getMetadata().getName() == null) {
            processingLog.getMetadata().setName(UUID.randomUUID().toString());
        }
        return client.create(processingLog);
    }

    /**
     * 查询处理日志列表
     * 支持分页、文件名搜索、状态过滤、时间范围过滤
     *
     * @param query 查询参数
     * @return 日志列表流
     */
    @Override
    public Flux<ProcessingLog> list(ProcessingLogQuery query) {
        // 获取所有日志并过滤
        Flux<ProcessingLog> filtered = client.listAll(ProcessingLog.class, new ListOptions(), null)
            .filter(log -> matchesQuery(log, query))
            .sort((a, b) -> {
                // 按处理时间倒序排列（最新的在前）
                Instant timeA = a.getSpec() != null ? a.getSpec().getProcessedAt() : null;
                Instant timeB = b.getSpec() != null ? b.getSpec().getProcessedAt() : null;
                if (timeA == null && timeB == null) return 0;
                if (timeA == null) return 1;
                if (timeB == null) return -1;
                return timeB.compareTo(timeA);
            });
        
        // 如果 size 是 Integer.MAX_VALUE，表示需要全部数据（用于统计）
        if (query.size() == Integer.MAX_VALUE) {
            return filtered;
        }
        
        // 应用分页
        return filtered
            .skip((long) (query.page() - 1) * query.size())
            .take(query.size());
    }

    /**
     * 统计符合条件的日志数量
     *
     * @param query 查询参数
     * @return 日志数量
     */
    @Override
    public Mono<Long> count(ProcessingLogQuery query) {
        return client.listAll(ProcessingLog.class, new ListOptions(), null)
            .filter(log -> matchesQuery(log, query))
            .count();
    }

    /**
     * 删除过期日志
     * 根据保留天数删除超过期限的日志（按日期截断，删除 N 天前的日志）
     *
     * @param retentionDays 保留天数
     * @return 完成信号
     */
    @Override
    public Mono<Void> deleteExpired(int retentionDays) {
        // 计算截止日期（当天 0 点），删除该日期之前的所有日志
        Instant cutoff = Instant.now()
            .atZone(java.time.ZoneId.systemDefault())
            .minusDays(retentionDays)
            .toLocalDate()
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant();
        
        return client.listAll(ProcessingLog.class, new ListOptions(), null)
            .filter(log -> {
                // 跳过已标记删除的
                if (log.getMetadata() != null && log.getMetadata().getDeletionTimestamp() != null) {
                    return false;
                }
                if (log.getSpec() == null || log.getSpec().getProcessedAt() == null) {
                    return false;
                }
                // 只删除超过保留期限的日志
                return log.getSpec().getProcessedAt().isBefore(cutoff);
            })
            .flatMap(log -> client.delete(log).then(), 100)
            .then()
            .doOnSuccess(v -> log.info("Deleted expired processing logs older than {} days", retentionDays));
    }

    /**
     * 清空所有日志
     *
     * @return 删除的日志数量
     */
    @Override
    public Mono<Long> deleteAll() {
        return client.listAll(ProcessingLog.class, new ListOptions(), null)
            // 过滤掉已标记删除的
            .filter(log -> log.getMetadata() == null || log.getMetadata().getDeletionTimestamp() == null)
            .collectList()
            .flatMap(logs -> {
                if (logs.isEmpty()) {
                    return Mono.just(0L);
                }
                long count = logs.size();
                // 逐个删除
                return Flux.fromIterable(logs)
                    .flatMap(logEntry -> client.delete(logEntry), 100)
                    .then(Mono.just(count));
            })
            .doOnSuccess(count -> log.info("Deleted all {} processing logs", count));
    }

    /**
     * 根据名称获取日志
     *
     * @param name 日志名称（UUID）
     * @return 日志对象
     */
    @Override
    public Mono<ProcessingLog> getByName(String name) {
        return client.get(ProcessingLog.class, name);
    }

    /**
     * 检查日志是否匹配查询条件
     *
     * @param log   日志对象
     * @param query 查询参数
     * @return 是否匹配
     */
    private boolean matchesQuery(ProcessingLog log, ProcessingLogQuery query) {
        if (log.getSpec() == null) {
            return false;
        }
        
        // 过滤掉已标记删除的记录
        if (log.getMetadata() != null && log.getMetadata().getDeletionTimestamp() != null) {
            return false;
        }
        
        var spec = log.getSpec();
        
        // 文件名模糊匹配（不区分大小写）
        if (query.filename() != null && !query.filename().isBlank()) {
            String filename = spec.getOriginalFilename();
            if (filename == null || !filename.toLowerCase().contains(query.filename().toLowerCase())) {
                return false;
            }
        }
        
        // 状态精确匹配
        if (query.status() != null && spec.getStatus() != query.status()) {
            return false;
        }
        
        // 来源精确匹配
        if (query.source() != null && !query.source().isBlank()) {
            String source = spec.getSource();
            if (source == null || !source.equals(query.source())) {
                return false;
            }
        }
        
        // 时间范围过滤
        Instant processedAt = spec.getProcessedAt();
        if (processedAt != null) {
            if (query.startTime() != null && processedAt.isBefore(query.startTime())) {
                return false;
            }
            if (query.endTime() != null && processedAt.isAfter(query.endTime())) {
                return false;
            }
        }
        
        return true;
    }
}
