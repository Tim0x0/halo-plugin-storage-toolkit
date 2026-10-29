package com.timxs.storagetoolkit.endpoint;

import com.timxs.storagetoolkit.extension.BatchProcessingStatus;
import com.timxs.storagetoolkit.service.BatchProcessingService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import run.halo.app.plugin.ApiVersion;

import java.time.Instant;
import java.util.List;

/** 批量处理 REST API 端点 */
@ApiVersion("console.api.storage-toolkit.timxs.com/v1alpha1")
@RestController
@RequestMapping("/batchprocessing")
@RequiredArgsConstructor
public class BatchProcessingEndpoint {

    private final BatchProcessingService batchProcessingService;

    /** 创建批量处理任务 */
    @PostMapping("/tasks")
    public Mono<StatusResponse> createTask(@RequestBody CreateTaskRequest request) {
        if (request.attachmentNames() == null || request.attachmentNames().isEmpty()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "附件列表不能为空"));
        }

        return batchProcessingService
                .createTask(request.attachmentNames(), request.replaceReferences())
                .map(this::toStatusResponse)
                .onErrorResume(
                        IllegalStateException.class,
                        e ->
                                Mono.error(
                                        new ResponseStatusException(
                                                HttpStatus.CONFLICT, e.getMessage())))
                .onErrorResume(
                        IllegalArgumentException.class,
                        e ->
                                Mono.error(
                                        new ResponseStatusException(
                                                HttpStatus.BAD_REQUEST, e.getMessage())));
    }

    @GetMapping("/tasks")
    public Mono<run.halo.app.extension.ListResult<StatusResponse>> listTasks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return batchProcessingService
                .listTasks(page, size)
                .map(
                        result ->
                                new run.halo.app.extension.ListResult<>(
                                        result.getPage(),
                                        result.getSize(),
                                        result.getTotal(),
                                        result.getItems().stream()
                                                .map(this::toStatusResponse)
                                                .toList()));
    }

    @GetMapping("/tasks/{name}")
    public Mono<StatusResponse> getTask(@PathVariable String name) {
        return batchProcessingService
                .requireTask(name)
                .map(this::toStatusResponse)
                .onErrorMap(
                        IllegalArgumentException.class,
                        e -> new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e));
    }

    @PostMapping("/tasks/{name}/resume")
    public Mono<StatusResponse> resumeTask(@PathVariable String name) {
        return batchProcessingService
                .resumeTask(name)
                .map(this::toStatusResponse)
                .onErrorMap(
                        IllegalArgumentException.class,
                        e -> new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e))
                .onErrorMap(
                        IllegalStateException.class,
                        e -> new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e));
    }

    @PostMapping("/tasks/{name}/retry")
    public Mono<StatusResponse> retryTask(@PathVariable String name) {
        return batchProcessingService
                .retryFailed(name)
                .map(this::toStatusResponse)
                .onErrorMap(
                        IllegalArgumentException.class,
                        e -> new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e))
                .onErrorMap(
                        IllegalStateException.class,
                        e -> new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e));
    }

    /** 取消当前任务 */
    @DeleteMapping("/tasks/current")
    public Mono<StatusResponse> cancelTask() {
        return batchProcessingService
                .cancelTask()
                .map(this::toStatusResponse)
                .onErrorResume(
                        IllegalStateException.class,
                        e ->
                                Mono.error(
                                        new ResponseStatusException(
                                                HttpStatus.CONFLICT, e.getMessage())));
    }

    /** 获取当前任务状态 */
    @GetMapping("/status")
    public Mono<StatusResponse> getStatus() {
        return batchProcessingService.getStatus().map(this::toStatusResponse);
    }

    /** 获取批量处理设置 */
    @GetMapping("/settings")
    public Mono<SettingsResponse> getSettings() {
        return batchProcessingService.getSettings();
    }

    /** 转换为响应对象 */
    private StatusResponse toStatusResponse(BatchProcessingStatus status) {
        var spec = status.getSpec();
        var s = status.getStatus();

        ProgressResponse progress = null;
        if (s != null && s.getProgress() != null) {
            var p = s.getProgress();
            progress =
                    new ProgressResponse(
                            p.getTotal(), p.getProcessed(), p.getSucceeded(), p.getFailed());
        }

        List<FailedItemResponse> failedItems = null;
        if (s != null && s.getFailedItems() != null) {
            failedItems =
                    s.getFailedItems().stream()
                            .map(
                                    f ->
                                            new FailedItemResponse(
                                                    f.getAttachmentName(),
                                                    f.getDisplayName(),
                                                    f.getError(),
                                                    f.isRetryable()))
                            .toList();
        }

        List<SkippedItemResponse> skippedItems = null;
        if (s != null && s.getSkippedItems() != null) {
            skippedItems =
                    s.getSkippedItems().stream()
                            .map(
                                    sk ->
                                            new SkippedItemResponse(
                                                    sk.getAttachmentName(),
                                                    sk.getDisplayName(),
                                                    sk.getReason()))
                            .toList();
        }

        int pendingCount =
                spec == null || spec.getAttachmentNames() == null
                        ? 0
                        : (int)
                                spec.getAttachmentNames().stream()
                                        .filter(
                                                name ->
                                                        !spec.getCompletedAttachmentNames()
                                                                        .contains(name)
                                                                && !spec.getReviewAttachmentNames()
                                                                        .contains(name))
                                        .count();
        return new StatusResponse(
                status.getMetadata() == null ? null : status.getMetadata().getName(),
                spec == null ? null : spec.getParentTaskId(),
                pendingCount,
                spec == null ? List.of() : spec.getReviewAttachmentNames(),
                s != null ? (s.getPhase() != null ? s.getPhase().name() : null) : null,
                progress,
                failedItems,
                skippedItems,
                s != null ? s.getSkippedCount() : 0,
                s != null ? s.getStartTime() : null,
                s != null ? s.getEndTime() : null,
                s != null ? s.getSavedBytes() : 0,
                s != null ? s.getKeptOriginalCount() : 0,
                spec != null && spec.isKeepOriginal(),
                s != null ? s.getErrorMessage() : null);
    }

    // ========== 请求/响应对象 ==========

    public record CreateTaskRequest(List<String> attachmentNames, boolean replaceReferences) {}

    public record StatusResponse(
            String taskId,
            String parentTaskId,
            int pendingCount,
            List<String> reviewAttachmentNames,
            String phase,
            ProgressResponse progress,
            List<FailedItemResponse> failedItems,
            List<SkippedItemResponse> skippedItems,
            int skippedCount,
            Instant startTime,
            Instant endTime,
            long savedBytes,
            int keptOriginalCount,
            boolean keepOriginal,
            String errorMessage) {}

    public record ProgressResponse(int total, int processed, int succeeded, int failed) {}

    public record FailedItemResponse(
            String attachmentName, String displayName, String error, boolean retryable) {}

    public record SkippedItemResponse(String attachmentName, String displayName, String reason) {}

    public record SettingsResponse(boolean keepOriginalFile, boolean enableRemoteStorage) {}
}
