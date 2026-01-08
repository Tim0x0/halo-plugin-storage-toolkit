package com.timxs.storagetoolkit.endpoint;

import com.timxs.storagetoolkit.extension.DuplicateScanStatus;
import com.timxs.storagetoolkit.model.DuplicateGroupVo;
import com.timxs.storagetoolkit.service.DuplicateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListResult;
import run.halo.app.plugin.ApiVersion;

import java.time.Instant;

/**
 * 重复检测 REST API 端点
 */
@ApiVersion("console.api.storage-toolkit.timxs.com/v1alpha1")
@RestController
@RequestMapping("/duplicates")
@RequiredArgsConstructor
public class DuplicateEndpoint {

    private final DuplicateService duplicateService;

    /**
     * 触发重复检测扫描
     */
    @PostMapping("/scan")
    public Mono<ScanResponse> startScan() {
        return duplicateService.startScan()
            .map(status -> new ScanResponse(
                status.getStatus().getPhase(),
                "扫描已开始",
                status.getStatus().getTotalCount(),
                status.getStatus().getScannedCount()
            ))
            .onErrorResume(IllegalStateException.class, e ->
                Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage()))
            );
    }

    /**
     * 获取扫描状态和统计概览
     */
    @GetMapping("/stats")
    public Mono<StatsResponse> getStats() {
        return duplicateService.getScanStatus()
            .map(status -> {
                var s = status.getStatus();
                return new StatsResponse(
                    s != null ? s.getPhase() : null,
                    s != null ? s.getLastScanTime() : null,
                    s != null ? s.getStartTime() : null,
                    s != null ? s.getTotalCount() : 0,
                    s != null ? s.getScannedCount() : 0,
                    s != null ? s.getDuplicateGroupCount() : 0,
                    s != null ? s.getDuplicateFileCount() : 0,
                    s != null ? s.getSavableSize() : 0,
                    s != null ? s.getErrorMessage() : null
                );
            });
    }

    /**
     * 获取重复组列表
     */
    @GetMapping
    public Mono<ListResult<DuplicateGroupVo>> listDuplicateGroups(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return duplicateService.listDuplicateGroups(page, size);
    }

    /**
     * 清空所有重复检测记录和扫描状态
     */
    @DeleteMapping("/clear")
    public Mono<ClearResponse> clearAll() {
        return duplicateService.clearAll()
            .thenReturn(new ClearResponse("重复检测记录已清空"));
    }

    /**
     * 扫描响应
     */
    public record ScanResponse(
        String phase,
        String message,
        int totalCount,
        int scannedCount
    ) {}

    /**
     * 统计响应
     */
    public record StatsResponse(
        String phase,
        Instant lastScanTime,
        Instant startTime,
        int totalCount,
        int scannedCount,
        int duplicateGroupCount,
        int duplicateFileCount,
        long savableSize,
        String errorMessage
    ) {}

    public record ClearResponse(String message) {}
}
