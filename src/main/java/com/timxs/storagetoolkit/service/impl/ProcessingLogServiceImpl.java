package com.timxs.storagetoolkit.service.impl;

import com.timxs.storagetoolkit.extension.ProcessingLog;
import com.timxs.storagetoolkit.model.ProcessingLogQuery;
import com.timxs.storagetoolkit.model.ProcessingResult;
import com.timxs.storagetoolkit.model.ProcessingSource;
import com.timxs.storagetoolkit.model.ProcessingStatus;
import com.timxs.storagetoolkit.service.ProcessingLogService;
import com.timxs.storagetoolkit.storage.SqliteLogStore;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/** 处理日志服务实现 使用 SQLite 进行数据持久化 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessingLogServiceImpl implements ProcessingLogService {

    /** Halo 响应式扩展客户端，用于操作 Extension 数据 */
    private final SqliteLogStore logs;

    /**
     * 保存处理日志 如果日志没有元数据或名称，会自动生成 UUID 作为名称
     *
     * @param processingLog 要保存的日志对象
     * @return 保存后的日志对象
     */
    @Override
    public Mono<ProcessingLog> save(ProcessingLog entry) {
        return logs.save(entry);
    }

    /**
     * 保存跳过日志
     *
     * @param filename 文件名
     * @param contentType 文件 MIME 类型
     * @param fileSize 文件大小
     * @param startTime 开始时间
     * @param reason 跳过原因
     * @param source 来源
     * @return 保存后的日志对象
     */
    @Override
    public Mono<ProcessingLog> saveSkippedLog(
            String filename,
            String contentType,
            long fileSize,
            Instant startTime,
            String reason,
            ProcessingSource source) {
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
                .doOnNext(
                        saved -> log.debug("Skipped log saved: {}", saved.getMetadata().getName()))
                .doOnError(error -> log.error("Failed to save skipped log", error));
    }

    /**
     * 保存处理结果日志
     *
     * @param result 处理结果
     * @param originalFilename 原始文件名
     * @param originalSize 原始文件大小
     * @param startTime 开始时间
     * @param source 来源
     * @return 保存后的日志对象
     */
    @Override
    public Mono<ProcessingLog> saveResultLog(
            ProcessingResult result,
            String originalFilename,
            long originalSize,
            Instant startTime,
            ProcessingSource source) {
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
                .doOnNext(
                        saved ->
                                log.debug(
                                        "Processing log saved: {}", saved.getMetadata().getName()))
                .doOnError(error -> log.error("Failed to save processing log", error));
    }

    /**
     * 查询处理日志列表 使用 listBy 进行数据库级别分页查询
     *
     * @param query 查询参数
     * @return 日志列表流
     */
    private SqliteLogStore.Filter filter(ProcessingLogQuery query) {
        return new SqliteLogStore.Filter(
                query.status() == null ? null : query.status().name(),
                query.source() == null ? null : query.source().name(),
                null,
                query.filename());
    }

    @Override
    public Flux<ProcessingLog> list(ProcessingLogQuery query) {
        return logs.list(ProcessingLog.class, filter(query), query.page(), query.size());
    }

    @Override
    public Mono<Long> count(ProcessingLogQuery query) {
        return logs.count(ProcessingLog.class, filter(query));
    }

    @Override
    public Mono<Void> deleteExpired(int days) {
        return logs.deleteExpired(ProcessingLog.class, days);
    }

    @Override
    public Mono<Long> deleteAll() {
        return logs.deleteAll(ProcessingLog.class);
    }

    @Override
    public Mono<ProcessingLog> getByName(String name) {
        return logs.get(ProcessingLog.class, name);
    }

    @Override
    public Mono<ProcessingLogStats> getStats() {
        return logs.stats(ProcessingLog.class)
                .map(c -> new ProcessingLogStats(c[0], c[1], c[2], c[3], c[4], c[5]));
    }
}
