package com.timxs.storagetoolkit.endpoint;

import com.timxs.storagetoolkit.model.BrokenLinkReplaceResult;
import com.timxs.storagetoolkit.model.BrokenLinkVo;
import com.timxs.storagetoolkit.service.BrokenLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListResult;
import run.halo.app.plugin.ApiVersion;

import java.time.Instant;
import java.util.List;

/**
 * 断链扫描 REST API 端点
 */
@ApiVersion("console.api.storage-toolkit.timxs.com/v1alpha1")
@RestController
@RequestMapping("/brokenlinks")
@RequiredArgsConstructor
public class BrokenLinkEndpoint {

    private final BrokenLinkService brokenLinkService;

    /**
     * 开始断链扫描
     */
    @PostMapping("/scan")
    public Mono<StatusResponse> startScan() {
        return brokenLinkService.startScan()
            .map(this::toStatusResponse)
            .onErrorResume(IllegalStateException.class, e ->
                Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage()))
            );
    }

    /**
     * 获取扫描状态
     */
    @GetMapping("/status")
    public Mono<StatusResponse> getStatus() {
        return brokenLinkService.getStatus()
            .map(this::toStatusResponse);
    }

    /**
     * 获取断链列表
     */
    @GetMapping
    public Mono<ListResult<BrokenLinkVo>> listBrokenLinks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String sort) {
        return brokenLinkService.listBrokenLinks(page, size, sourceType, keyword, reason, sort);
    }

    /**
     * 清空扫描结果
     */
    @DeleteMapping
    public Mono<ClearResponse> clearAll() {
        return brokenLinkService.clearAll()
            .thenReturn(new ClearResponse("断链扫描结果已清空"));
    }

    /**
     * 替换断链
     */
    @PostMapping("/replace")
    public Mono<ReplaceResponse> replaceBrokenLink(@RequestBody ReplaceRequest request) {
        if (request.oldUrl() == null || request.oldUrl().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "旧 URL 不能为空"));
        }
        if (request.newUrl() == null || request.newUrl().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "新 URL 不能为空"));
        }

        return brokenLinkService.replaceBrokenLink(request.oldUrl(), request.newUrl())
            .map(result -> new ReplaceResponse(
                result.isAllSuccess(),
                result.getTotalSources(),
                result.getSuccessCount(),
                result.getFailedCount(),
                result.isBrokenLinkDeleted(),
                result.getFailures().stream()
                    .map(f -> new ReplaceFailure(
                        f.getSourceType(),
                        f.getSourceName(),
                        f.getSourceTitle(),
                        f.getErrorMessage()
                    ))
                    .toList()
            ));
    }

    private StatusResponse toStatusResponse(com.timxs.storagetoolkit.extension.BrokenLinkScanStatus status) {
        var s = status.getStatus();
        return new StatusResponse(
            s != null ? s.getPhase() : null,
            s != null ? s.getStartTime() : null,
            s != null ? s.getLastScanTime() : null,
            s != null ? s.getScannedContentCount() : 0,
            s != null ? s.getCheckedLinkCount() : 0,
            s != null ? s.getBrokenLinkCount() : 0,
            s != null ? s.getErrorMessage() : null
        );
    }

    // ========== 请求/响应对象 ==========

    public record StatusResponse(
        String phase,
        Instant startTime,
        Instant lastScanTime,
        int scannedContentCount,
        int checkedLinkCount,
        int brokenLinkCount,
        String errorMessage
    ) {}

    public record ClearResponse(String message) {}

    public record ReplaceRequest(String oldUrl, String newUrl) {}

    public record ReplaceResponse(
        boolean allSuccess,
        int totalSources,
        int successCount,
        int failedCount,
        boolean brokenLinkDeleted,
        List<ReplaceFailure> failures
    ) {}

    public record ReplaceFailure(
        String sourceType,
        String sourceName,
        String sourceTitle,
        String errorMessage
    ) {}
}
