package com.timxs.storagetoolkit.endpoint;

import com.timxs.storagetoolkit.extension.UrlReplaceLog;
import com.timxs.storagetoolkit.model.ReplaceSource;
import com.timxs.storagetoolkit.service.UrlReplaceLogService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ApiVersion;

import java.time.Instant;
import java.util.List;

/**
 * URL 替换日志 REST API 端点
 */
@ApiVersion("console.api.storage-toolkit.timxs.com/v1alpha1")
@RestController
@RequestMapping("/urlreplacelogs")
@RequiredArgsConstructor
public class UrlReplaceLogEndpoint {

    private final UrlReplaceLogService urlReplaceLogService;

    /**
     * 查询替换日志列表
     */
    @GetMapping
    public Mono<UrlReplaceLogListResult> list(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String source,
        @RequestParam(required = false) String keyword
    ) {
        ReplaceSource replaceSource = source != null ? ReplaceSource.fromValue(source) : null;
        return Mono.zip(
            urlReplaceLogService.list(page, size, replaceSource, keyword).collectList(),
            urlReplaceLogService.count(replaceSource, keyword)
        ).map(tuple -> {
            UrlReplaceLogListResult result = new UrlReplaceLogListResult();
            result.setItems(tuple.getT1().stream().map(this::toVo).toList());
            result.setTotal(tuple.getT2());
            result.setPage(page);
            result.setSize(size);
            return result;
        });
    }

    /**
     * 获取统计信息
     */
    @GetMapping("/stats")
    public Mono<StatsResponse> stats() {
        return urlReplaceLogService.getStats()
            .map(stats -> new StatsResponse(
                stats.totalCount(),
                stats.successCount(),
                stats.failedCount(),
                stats.brokenLinkCount(),
                stats.duplicateCount(),
                stats.batchProcessingCount()
            ));
    }

    /**
     * 清空所有日志
     */
    @DeleteMapping
    public Mono<DeleteResult> deleteAll() {
        return urlReplaceLogService.deleteAll()
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
     * 将 Extension 转换为 VO
     */
    private UrlReplaceLogVo toVo(UrlReplaceLog log) {
        UrlReplaceLogVo vo = new UrlReplaceLogVo();
        vo.setName(log.getMetadata() != null ? log.getMetadata().getName() : null);
        if (log.getSpec() != null) {
            var spec = log.getSpec();
            vo.setOldUrl(spec.getOldUrl());
            vo.setNewUrl(spec.getNewUrl());
            vo.setSourceType(spec.getSourceType());
            vo.setSourceName(spec.getSourceName());
            vo.setSourceTitle(spec.getSourceTitle());
            vo.setReferenceType(spec.getReferenceType());
            vo.setSource(spec.getSource());
            vo.setReplacedAt(spec.getReplacedAt());
            vo.setSuccess(spec.isSuccess());
            vo.setErrorMessage(spec.getErrorMessage());
        }
        return vo;
    }

    // ========== DTO ==========

    @Data
    public static class UrlReplaceLogVo {
        private String name;
        private String oldUrl;
        private String newUrl;
        private String sourceType;
        private String sourceName;
        private String sourceTitle;
        private String referenceType;
        // 使用枚举，Jackson 通过 @JsonValue 自动序列化为小写字符串
        private ReplaceSource source;
        private Instant replacedAt;
        private boolean success;
        private String errorMessage;
    }

    @Data
    public static class UrlReplaceLogListResult {
        private List<UrlReplaceLogVo> items;
        private long total;
        private int page;
        private int size;
    }

    public record StatsResponse(
        long totalCount,
        long successCount,
        long failedCount,
        long brokenLinkCount,
        long duplicateCount,
        long batchProcessingCount
    ) {}

    @Data
    public static class DeleteResult {
        private long deleted;
        private boolean success;
        private String message;
    }
}
