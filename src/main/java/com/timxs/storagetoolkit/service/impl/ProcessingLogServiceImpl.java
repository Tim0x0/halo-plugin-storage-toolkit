package com.timxs.storagetoolkit.service.impl;

import com.timxs.storagetoolkit.extension.ProcessingLog;
import com.timxs.storagetoolkit.model.ProcessingLogQuery;
import com.timxs.storagetoolkit.model.ProcessingResult;
import com.timxs.storagetoolkit.model.ProcessingSource;
import com.timxs.storagetoolkit.model.ProcessingStatus;
import com.timxs.storagetoolkit.service.ProcessingLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;

import java.time.Instant;
import java.util.UUID;

import static run.halo.app.extension.index.query.Queries.contains;
import static run.halo.app.extension.index.query.Queries.equal;

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
     * 保存跳过日志
     *
     * @param filename    文件名
     * @param contentType 文件 MIME 类型
     * @param fileSize    文件大小
     * @param startTime   开始时间
     * @param reason      跳过原因
     * @param source      来源
     * @return 保存后的日志对象
     */
    @Override
    public Mono<ProcessingLog> saveSkippedLog(String filename, String contentType, long fileSize,
                                               Instant startTime, String reason, ProcessingSource source) {
        ProcessingLog logEntry = new ProcessingLog();
        ProcessingLog.ProcessingLogSpec spec = new ProcessingLog.ProcessingLogSpec();

        spec.setOriginalFilename(filename);
        spec.setResultFilename(filename);
        spec.setOriginalSize(fileSize);
        spec.setResultSize(fileSize);
        spec.setStatus(ProcessingStatus.SKIPPED);
        spec.setProcessedAt(startTime);
        spec.setProcessingDuration(Instant.now().toEpochMilli() - startTime.toEpochMilli());
        spec.setErrorMessage(reason);
        spec.setSource(source);

        logEntry.setSpec(spec);

        return save(logEntry)
            .doOnNext(saved -> log.debug("Skipped log saved: {}", saved.getMetadata().getName()))
            .doOnError(error -> log.error("Failed to save skipped log", error));
    }

    /**
     * 保存处理结果日志
     *
     * @param result           处理结果
     * @param originalFilename 原始文件名
     * @param originalSize     原始文件大小
     * @param startTime        开始时间
     * @param source           来源
     * @return 保存后的日志对象
     */
    @Override
    public Mono<ProcessingLog> saveResultLog(ProcessingResult result, String originalFilename,
                                              long originalSize, Instant startTime, ProcessingSource source) {
        ProcessingLog logEntry = new ProcessingLog();
        ProcessingLog.ProcessingLogSpec spec = new ProcessingLog.ProcessingLogSpec();

        spec.setOriginalFilename(originalFilename);
        spec.setResultFilename(result.filename());
        spec.setOriginalSize(originalSize);
        spec.setResultSize(result.data().length);
        spec.setStatus(result.status());
        spec.setProcessedAt(startTime);
        spec.setProcessingDuration(Instant.now().toEpochMilli() - startTime.toEpochMilli());
        spec.setSource(source);

        if (result.message() != null) {
            spec.setErrorMessage(result.message());
        }

        logEntry.setSpec(spec);

        return save(logEntry)
            .doOnNext(saved -> log.debug("Processing log saved: {}", saved.getMetadata().getName()))
            .doOnError(error -> log.error("Failed to save processing log", error));
    }

    /**
     * 查询处理日志列表
     * 使用 listBy 进行数据库级别分页查询
     *
     * @param query 查询参数
     * @return 日志列表流
     */
    @Override
    public Flux<ProcessingLog> list(ProcessingLogQuery query) {
        var listOptions = buildListOptions(query);
        var sort = Sort.by(Sort.Order.desc("spec.processedAt"));

        var pageRequest = PageRequestImpl.of(query.page(), query.size(), sort);
        return client.listBy(ProcessingLog.class, listOptions, pageRequest)
            .flatMapMany(result -> Flux.fromIterable(result.getItems()));
    }

    /**
     * 统计符合条件的日志数量
     *
     * @param query 查询参数
     * @return 日志数量
     */
    @Override
    public Mono<Long> count(ProcessingLogQuery query) {
        var listOptions = buildListOptions(query);
        var sort = Sort.unsorted();

        var pageRequest = PageRequestImpl.ofSize(1).withSort(sort);
        return client.listBy(ProcessingLog.class, listOptions, pageRequest)
            .map(listResult -> listResult.getTotal());
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

        return client.listAll(ProcessingLog.class, ListOptions.builder().build(), Sort.unsorted())
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
        return client.listAll(ProcessingLog.class, ListOptions.builder().build(), Sort.unsorted())
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
     * 获取处理统计信息
     * 使用 listAll + reduce 流式处理，避免一次性加载全部数据到内存
     *
     * @return 统计信息
     */
    @Override
    public Mono<ProcessingLogStats> getStats() {
        return client.listAll(ProcessingLog.class, ListOptions.builder().build(), Sort.unsorted())
            .filter(logEntry -> logEntry.getMetadata() == null
                || logEntry.getMetadata().getDeletionTimestamp() == null)
            .reduce(new long[]{0, 0, 0, 0, 0, 0}, (counts, logEntry) -> {
                counts[0]++; // totalProcessed
                if (logEntry.getSpec() != null) {
                    ProcessingStatus status = logEntry.getSpec().getStatus();
                    if (status == ProcessingStatus.SUCCESS) {
                        counts[1]++; // successCount
                    } else if (status == ProcessingStatus.FAILED) {
                        counts[2]++; // failedCount
                    } else if (status == ProcessingStatus.SKIPPED) {
                        counts[3]++; // skippedCount
                    } else if (status == ProcessingStatus.PARTIAL) {
                        counts[4]++; // partialCount
                    }
                    // 计算节省的空间（只统计正值）
                    long saved = Math.max(0, logEntry.getSpec().getOriginalSize() - logEntry.getSpec().getResultSize());
                    counts[5] += saved; // totalSavedBytes
                }
                return counts;
            })
            .map(counts -> new ProcessingLogStats(
                counts[0], counts[1], counts[2], counts[3], counts[4], counts[5]
            ));
    }

    /**
     * 构建查询选项
     * 使用 Queries + ListOptions.builder().andQuery() 链式方法动态组合条件
     *
     * @param query 查询参数
     * @return ListOptions
     */
    private ListOptions buildListOptions(ProcessingLogQuery query) {
        var builder = ListOptions.builder();

        // 状态精确匹配
        if (query.status() != null) {
            builder.andQuery(equal("spec.status", query.status().name()));
        }

        // 来源精确匹配
        if (query.source() != null) {
            builder.andQuery(equal("spec.source", query.source().name()));
        }

        // 文件名模糊搜索
        if (query.filename() != null && !query.filename().isBlank()) {
            builder.andQuery(contains("spec.originalFilename", query.filename()));
        }

        return builder.build();
    }
}
