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

/**
 * 批量处理 REST API 端点
 */
@ApiVersion("console.api.storage-toolkit.timxs.com/v1alpha1")
@RestController
@RequestMapping("/batch-processing")
@RequiredArgsConstructor
public class BatchProcessingEndpoint {

    private final BatchProcessingService batchProcessingService;

    /**
     * 创建批量处理任务
     */
    @PostMapping("/tasks")
    public Mono<StatusResponse> createTask(@RequestBody CreateTaskRequest request) {
        if (request.attachmentNames() == null || request.attachmentNames().isEmpty()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "附件列表不能为空"));
        }

        return batchProcessingService.createTask(request.attachmentNames())
            .map(this::toStatusResponse)
            .onErrorResume(IllegalStateException.class, e ->
                Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage()))
            )
            .onErrorResume(IllegalArgumentException.class, e ->
                Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()))
            );
    }

    /**
     * 取消当前任务
     */
    @DeleteMapping("/tasks/current")
    public Mono<StatusResponse> cancelTask() {
        return batchProcessingService.cancelTask()
            .map(this::toStatusResponse)
            .onErrorResume(IllegalStateException.class, e ->
                Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage()))
            );
    }

    /**
     * 获取当前任务状态
     */
    @GetMapping("/status")
    public Mono<StatusResponse> getStatus() {
        return batchProcessingService.getStatus()
            .map(this::toStatusResponse);
    }

    /**
     * 获取批量处理设置
     */
    @GetMapping("/settings")
    public Mono<SettingsResponse> getSettings() {
        return batchProcessingService.getSettings();
    }

    /**
     * 检查附件引用（用于警告提示）
     */
    @PostMapping("/check-references")
    public Mono<CheckReferencesResponse> checkReferences(@RequestBody CheckReferencesRequest request) {
        if (request.attachmentNames() == null || request.attachmentNames().isEmpty()) {
            return Mono.just(new CheckReferencesResponse(0));
        }

        return batchProcessingService.countReferencedAttachments(request.attachmentNames())
            .map(CheckReferencesResponse::new);
    }

    /**
     * 转换为响应对象
     */
    private StatusResponse toStatusResponse(BatchProcessingStatus status) {
        var spec = status.getSpec();
        var s = status.getStatus();

        ProgressResponse progress = null;
        if (s != null && s.getProgress() != null) {
            var p = s.getProgress();
            progress = new ProgressResponse(p.getTotal(), p.getProcessed(), p.getSucceeded(), p.getFailed());
        }

        List<FailedItemResponse> failedItems = null;
        if (s != null && s.getFailedItems() != null) {
            failedItems = s.getFailedItems().stream()
                .map(f -> new FailedItemResponse(f.getAttachmentName(), f.getDisplayName(), f.getError()))
                .toList();
        }

        List<SkippedItemResponse> skippedItems = null;
        if (s != null && s.getSkippedItems() != null) {
            skippedItems = s.getSkippedItems().stream()
                .map(sk -> new SkippedItemResponse(sk.getAttachmentName(), sk.getDisplayName(), sk.getReason()))
                .toList();
        }

        return new StatusResponse(
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
            s != null ? s.getErrorMessage() : null
        );
    }

    // ========== 请求/响应对象 ==========

    public record CreateTaskRequest(List<String> attachmentNames) {}

    public record CheckReferencesRequest(List<String> attachmentNames) {}

    public record CheckReferencesResponse(int referencedCount) {}

    public record StatusResponse(
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
        String errorMessage
    ) {}

    public record ProgressResponse(int total, int processed, int succeeded, int failed) {}

    public record FailedItemResponse(String attachmentName, String displayName, String error) {}

    public record SkippedItemResponse(String attachmentName, String displayName, String reason) {}

    public record SettingsResponse(boolean keepOriginalFile, boolean enableRemoteStorage) {}
}
