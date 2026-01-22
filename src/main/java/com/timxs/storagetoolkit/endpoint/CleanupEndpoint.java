package com.timxs.storagetoolkit.endpoint;

import com.timxs.storagetoolkit.extension.CleanupLog;
import com.timxs.storagetoolkit.service.DuplicateService;
import com.timxs.storagetoolkit.service.ReferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ApiVersion;

import java.time.Instant;
import java.util.List;

/**
 * 清理操作 REST API 端点
 */
@ApiVersion("console.api.storage-toolkit.timxs.com/v1alpha1")
@RestController
@RequestMapping("/cleanup")
@RequiredArgsConstructor
public class CleanupEndpoint {

    private final DuplicateService duplicateService;
    private final ReferenceService referenceService;
    private final ReactiveExtensionClient client;

    /**
     * 删除重复文件
     */
    @DeleteMapping("/duplicates/{groupMd5}")
    public Mono<CleanupResultResponse> deleteDuplicates(
            @PathVariable String groupMd5,
            @RequestBody DeleteRequest request) {
        if (request.attachmentNames() == null || request.attachmentNames().isEmpty()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "附件列表不能为空"));
        }

        return duplicateService.deleteDuplicates(groupMd5, request.attachmentNames())
            .map(result -> new CleanupResultResponse(
                result.deletedCount(),
                result.failedCount(),
                result.freedSize(),
                result.errors()
            ))
            .onErrorResume(IllegalArgumentException.class, e ->
                Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()))
            );
    }

    /**
     * 删除未引用文件
     */
    @DeleteMapping("/unreferenced")
    public Mono<CleanupResultResponse> deleteUnreferenced(@RequestBody DeleteRequest request) {
        if (request.attachmentNames() == null || request.attachmentNames().isEmpty()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "附件列表不能为空"));
        }

        return referenceService.deleteUnreferenced(request.attachmentNames())
            .map(result -> new CleanupResultResponse(
                result.deletedCount(),
                result.failedCount(),
                result.freedSize(),
                result.errors()
            ))
            .onErrorResume(IllegalArgumentException.class, e ->
                Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()))
            );
    }

    /**
     * 获取清理日志
     */
    @GetMapping("/logs")
    public Mono<ListResult<CleanupLogResponse>> listLogs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String filename) {

        return client.listAll(CleanupLog.class, ListOptions.builder().build(), Sort.unsorted())
            .filter(log -> {
                if (log.getSpec() == null) return false;

                // 按原因过滤
                if (reason != null && !reason.isEmpty()) {
                    if (log.getSpec().getReason() == null
                        || !log.getSpec().getReason().name().equals(reason)) {
                        return false;
                    }
                }

                // 按文件名过滤
                if (filename != null && !filename.isEmpty()) {
                    String displayName = log.getSpec().getDisplayName();
                    if (displayName == null || !displayName.toLowerCase().contains(filename.toLowerCase())) {
                        return false;
                    }
                }

                return true;
            })
            .collectList()
            .map(logs -> {
                // 按删除时间降序排序
                logs.sort((a, b) -> {
                    Instant timeA = a.getSpec() != null ? a.getSpec().getDeletedAt() : null;
                    Instant timeB = b.getSpec() != null ? b.getSpec().getDeletedAt() : null;
                    if (timeA == null && timeB == null) return 0;
                    if (timeA == null) return 1;
                    if (timeB == null) return -1;
                    return timeB.compareTo(timeA);
                });

                int total = logs.size();
                int start = (page - 1) * size;
                int end = Math.min(start + size, total);

                List<CleanupLogResponse> pageItems = start < total
                    ? logs.subList(start, end).stream()
                        .map(this::toLogResponse)
                        .toList()
                    : List.of();

                return new ListResult<>(page, size, total, pageItems);
            });
    }

    /**
     * 获取清理日志统计
     */
    @GetMapping("/logs/stats")
    public Mono<CleanupLogStatsResponse> getLogStats() {
        return client.listAll(CleanupLog.class, ListOptions.builder().build(), Sort.unsorted())
            .collectList()
            .map(logs -> {
                int totalCount = logs.size();
                int duplicateCount = 0;
                int unreferencedCount = 0;
                long freedBytes = 0;

                for (CleanupLog log : logs) {
                    if (log.getSpec() == null) continue;
                    freedBytes += log.getSpec().getSize();
                    if (log.getSpec().getReason() == CleanupLog.Reason.DUPLICATE) {
                        duplicateCount++;
                    } else if (log.getSpec().getReason() == CleanupLog.Reason.UNREFERENCED) {
                        unreferencedCount++;
                    }
                }

                return new CleanupLogStatsResponse(totalCount, duplicateCount, unreferencedCount, freedBytes);
            });
    }

    /**
     * 清空清理日志
     */
    @DeleteMapping("/logs")
    public Mono<Void> clearLogs() {
        return client.listAll(CleanupLog.class, ListOptions.builder().build(), Sort.unsorted())
            .flatMap(client::delete)
            .then();
    }

    /**
     * 预览删除操作
     */
    @PostMapping("/preview")
    public Mono<PreviewResponse> preview(@RequestBody DeleteRequest request) {
        if (request.attachmentNames() == null || request.attachmentNames().isEmpty()) {
            return Mono.just(new PreviewResponse(0, 0));
        }

        var attachmentNames = request.attachmentNames();

        // 计算总大小
        Mono<Long> totalSizeMono = client.listAll(run.halo.app.core.extension.attachment.Attachment.class,
                ListOptions.builder().build(), Sort.unsorted())
            .filter(att -> att.getMetadata().getDeletionTimestamp() == null)
            .filter(att -> attachmentNames.contains(att.getMetadata().getName()))
            .map(att -> att.getSpec().getSize() != null ? att.getSpec().getSize() : 0L)
            .reduce(0L, Long::sum);

        // 计算有引用的附件数量
        Mono<Integer> referencedCountMono = client.listAll(
                com.timxs.storagetoolkit.extension.AttachmentReference.class,
                ListOptions.builder().build(), Sort.unsorted())
            .filter(ref -> ref.getSpec() != null 
                && ref.getSpec().getAttachmentName() != null
                && attachmentNames.contains(ref.getSpec().getAttachmentName())
                && ref.getStatus() != null 
                && ref.getStatus().getReferenceCount() > 0)
            .map(ref -> ref.getSpec().getAttachmentName())
            .distinct()
            .count()
            .map(Long::intValue);

        return Mono.zip(totalSizeMono, referencedCountMono)
            .map(tuple -> new PreviewResponse(tuple.getT1(), tuple.getT2()));
    }

    private CleanupLogResponse toLogResponse(CleanupLog log) {
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

    public record DeleteRequest(List<String> attachmentNames) {}

    public record CleanupResultResponse(
        int deletedCount,
        int failedCount,
        long freedSize,
        List<String> errors
    ) {}

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

    public record PreviewResponse(long totalSize, int referencedCount) {}

    public record CleanupLogStatsResponse(
        int totalCount,
        int duplicateCount,
        int unreferencedCount,
        long freedBytes
    ) {}
}
