package com.timxs.storagetoolkit.endpoint;

import com.timxs.storagetoolkit.extension.ProcessingLog;
import com.timxs.storagetoolkit.model.ProcessingLogQuery;
import com.timxs.storagetoolkit.model.ProcessingStatus;
import com.timxs.storagetoolkit.service.ProcessingLogService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ApiVersion;

import java.time.Instant;
import java.util.List;

/**
 * 处理日志 REST API 端点
 * 提供日志查询、统计和清空功能
 */
@ApiVersion("console.api.storage-toolkit.timxs.com/v1alpha1")
@RestController
@RequestMapping("/processinglogs")
@RequiredArgsConstructor
public class ProcessingLogEndpoint {

    /**
     * 处理日志服务
     */
    private final ProcessingLogService processingLogService;

    /**
     * 查询处理日志列表
     * 支持文件名搜索、状态过滤、时间范围过滤和分页
     *
     * @param filename  文件名（模糊搜索）
     * @param status    处理状态
     * @param startTime 开始时间（ISO 8601 格式）
     * @param endTime   结束时间（ISO 8601 格式）
     * @param page      页码（从 1 开始）
     * @param size      每页大小
     * @return 日志列表结果
     */
    @GetMapping
    public Mono<ProcessingLogListResult> list(
        @RequestParam(required = false) String filename,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String source,
        @RequestParam(required = false) String startTime,
        @RequestParam(required = false) String endTime,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        // 解析状态枚举
        ProcessingStatus statusEnum = null;
        if (status != null && !status.isBlank()) {
            try {
                statusEnum = ProcessingStatus.valueOf(status);
            } catch (IllegalArgumentException ignored) {
            }
        }

        // 解析时间
        Instant start = parseInstant(startTime);
        Instant end = parseInstant(endTime);

        // 构建查询参数
        ProcessingLogQuery query = new ProcessingLogQuery(filename, statusEnum, source, start, end, page, size);

        // 并行查询列表和总数
        return Mono.zip(
            processingLogService.list(query).collectList(),
            processingLogService.count(query)
        ).map(tuple -> {
            ProcessingLogListResult result = new ProcessingLogListResult();
            result.setItems(tuple.getT1());
            result.setTotal(tuple.getT2());
            result.setPage(page);
            result.setSize(size);
            return result;
        });
    }

    /**
     * 获取处理统计信息
     * 包括总处理数、成功/失败/跳过数量、节省空间等
     *
     * @return 统计信息
     */
    @GetMapping("/stats")
    public Mono<ProcessingStats> stats() {
        // 使用流式处理避免一次性加载所有数据到内存
        return processingLogService.list(new ProcessingLogQuery(null, null, null, null, null, 1, Integer.MAX_VALUE))
            .reduce(new ProcessingStats(), (stats, log) -> {
                stats.setTotalProcessed(stats.getTotalProcessed() + 1);
                
                if (log.getSpec() != null) {
                    ProcessingStatus status = log.getSpec().getStatus();
                    // 按状态分类计数
                    if (status == ProcessingStatus.SUCCESS) {
                        stats.setSuccessCount(stats.getSuccessCount() + 1);
                    } else if (status == ProcessingStatus.FAILED) {
                        stats.setFailedCount(stats.getFailedCount() + 1);
                    } else if (status == ProcessingStatus.SKIPPED) {
                        stats.setSkippedCount(stats.getSkippedCount() + 1);
                    } else if (status == ProcessingStatus.PARTIAL) {
                        stats.setPartialCount(stats.getPartialCount() + 1);
                    }
                    
                    // 计算节省的空间（只统计正值）
                    long saved = Math.max(0, log.getSpec().getOriginalSize() - log.getSpec().getResultSize());
                    stats.setTotalSavedBytes(stats.getTotalSavedBytes() + saved);
                }
                return stats;
            });
    }

    /**
     * 清空所有日志
     *
     * @return 删除结果
     */
    @DeleteMapping
    public Mono<DeleteResult> deleteAll() {
        return processingLogService.deleteAll()
            .map(count -> {
                DeleteResult result = new DeleteResult();
                result.setDeleted(count);
                result.setSuccess(true);
                return result;
            })
            .onErrorResume(e -> {
                DeleteResult result = new DeleteResult();
                result.setDeleted(0L);
                result.setSuccess(false);
                result.setMessage(e.getMessage());
                return Mono.just(result);
            });
    }

    /**
     * 解析 ISO 8601 格式的时间字符串
     *
     * @param value 时间字符串
     * @return Instant 对象，解析失败返回 null
     */
    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 日志列表查询结果
     */
    @Data
    public static class ProcessingLogListResult {
        /**
         * 日志列表
         */
        private List<ProcessingLog> items;
        
        /**
         * 总数
         */
        private long total;
        
        /**
         * 当前页码
         */
        private int page;
        
        /**
         * 每页大小
         */
        private int size;
    }

    /**
     * 处理统计信息
     */
    @Data
    public static class ProcessingStats {
        /**
         * 总处理数
         */
        private long totalProcessed;
        
        /**
         * 成功数
         */
        private long successCount;
        
        /**
         * 失败数
         */
        private long failedCount;
        
        /**
         * 跳过数
         */
        private long skippedCount;
        
        /**
         * 部分成功数
         */
        private long partialCount;
        
        /**
         * 节省的总字节数
         */
        private long totalSavedBytes;
    }

    /**
     * 删除操作结果
     */
    @Data
    public static class DeleteResult {
        /**
         * 删除的数量
         */
        private long deleted;
        
        /**
         * 是否成功
         */
        private boolean success;
        
        /**
         * 错误信息
         */
        private String message;
    }
}
