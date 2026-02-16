package com.timxs.storagetoolkit.endpoint;

import com.timxs.storagetoolkit.extension.ProcessingLog;
import com.timxs.storagetoolkit.model.ProcessingLogQuery;
import com.timxs.storagetoolkit.model.ProcessingSource;
import com.timxs.storagetoolkit.model.ProcessingStatus;
import com.timxs.storagetoolkit.service.ProcessingLogService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ApiVersion;

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
     * 支持文件名搜索、状态过滤、来源过滤和分页
     *
     * @param filename 文件名（模糊搜索）
     * @param status   处理状态
     * @param source   来源
     * @param page     页码（从 1 开始）
     * @param size     每页大小
     * @return 日志列表结果
     */
    @GetMapping
    public Mono<ProcessingLogListResult> list(
        @RequestParam(required = false) String filename,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String source,
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

        // 解析来源枚举
        ProcessingSource sourceEnum = source != null ? ProcessingSource.fromValue(source) : null;

        // 构建查询参数
        ProcessingLogQuery query = new ProcessingLogQuery(filename, statusEnum, sourceEnum, page, size);

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
        return processingLogService.getStats()
            .map(stats -> {
                ProcessingStats result = new ProcessingStats();
                result.setTotalProcessed(stats.totalProcessed());
                result.setSuccessCount(stats.successCount());
                result.setFailedCount(stats.failedCount());
                result.setSkippedCount(stats.skippedCount());
                result.setPartialCount(stats.partialCount());
                result.setTotalSavedBytes(stats.totalSavedBytes());
                return result;
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
