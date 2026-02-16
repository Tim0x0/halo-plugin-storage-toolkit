package com.timxs.storagetoolkit.endpoint;

import com.timxs.storagetoolkit.extension.CleanupLog;
import com.timxs.storagetoolkit.model.CleanupReason;
import com.timxs.storagetoolkit.service.CleanupLogService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ApiVersion;

import java.time.Instant;
import java.util.List;

/**
 * 清理日志 REST API 端点
 * 提供清理日志查询、统计和清空功能
 */
@ApiVersion("console.api.storage-toolkit.timxs.com/v1alpha1")
@RestController
@RequestMapping("/cleanuplogs")
@RequiredArgsConstructor
public class CleanupLogEndpoint {

    private final CleanupLogService cleanupLogService;

    /**
     * 查询清理日志列表
     */
    @GetMapping
    public Mono<CleanupLogListResult> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String filename) {

        CleanupReason reasonEnum = reason != null ? CleanupReason.fromValue(reason) : null;

        return Mono.zip(
            cleanupLogService.list(page, size, reasonEnum, filename).collectList(),
            cleanupLogService.count(reasonEnum, filename)
        ).map(tuple -> {
            List<CleanupLogResponse> items = tuple.getT1().stream()
                .map(this::toResponse)
                .toList();

            CleanupLogListResult result = new CleanupLogListResult();
            result.setItems(items);
            result.setTotal(tuple.getT2());
            result.setPage(page);
            result.setSize(size);
            return result;
        });
    }

    /**
     * 获取清理日志统计
     */
    @GetMapping("/stats")
    public Mono<CleanupLogStatsResponse> stats() {
        return cleanupLogService.getStats()
            .map(stats -> new CleanupLogStatsResponse(
                stats.totalCount(),
                stats.duplicateCount(),
                stats.unreferencedCount(),
                stats.freedBytes()
            ));
    }

    /**
     * 清空清理日志
     */
    @DeleteMapping
    public Mono<DeleteResult> deleteAll() {
        return cleanupLogService.deleteAll()
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

    private CleanupLogResponse toResponse(CleanupLog log) {
        var spec = log.getSpec();
        return new CleanupLogResponse(
            log.getMetadata().getName(),
            spec != null ? spec.getAttachmentName() : null,
            spec != null ? spec.getDisplayName() : null,
            spec != null ? spec.getSize() : 0,
            spec != null && spec.getReason() != null ? spec.getReason().name() : null,
            spec != null ? spec.getOperator() : null,
            spec != null ? spec.getDeletedAt() : null,
            spec != null ? spec.getErrorMessage() : null
        );
    }

    // ========== 请求/响应对象 ==========

    @Data
    public static class CleanupLogListResult {
        private List<CleanupLogResponse> items;
        private long total;
        private int page;
        private int size;
    }

    public record CleanupLogResponse(
        String name,
        String attachmentName,
        String displayName,
        long size,
        String reason,
        String operator,
        Instant deletedAt,
        String errorMessage
    ) {}

    public record CleanupLogStatsResponse(
        long totalCount,
        long duplicateCount,
        long unreferencedCount,
        long freedBytes
    ) {}

    @Data
    public static class DeleteResult {
        private long deleted;
        private boolean success;
        private String message;
    }
}
