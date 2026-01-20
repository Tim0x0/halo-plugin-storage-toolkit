package com.timxs.storagetoolkit.endpoint;

import com.timxs.storagetoolkit.model.BrokenLinkVo;
import com.timxs.storagetoolkit.service.BrokenLinkService;
import com.timxs.storagetoolkit.service.WhitelistService;
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
@RequestMapping("/broken-links")
@RequiredArgsConstructor
public class BrokenLinkEndpoint {

    private final BrokenLinkService brokenLinkService;
    private final WhitelistService whitelistService;

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
            @RequestParam(required = false) String sort) {
        return brokenLinkService.listBrokenLinks(page, size, sourceType, keyword, sort);
    }

    /**
     * 获取所有来源类型（用于前端筛选下拉框）
     */
    @GetMapping("/source-types")
    public Mono<List<String>> getSourceTypes() {
        return brokenLinkService.getSourceTypes();
    }

    /**
     * 添加 URL 到忽略白名单
     */
    @PostMapping("/whitelist")
    public Mono<WhitelistResponse> addToWhitelist(@RequestBody WhitelistRequest request) {
        if (request.urls() == null || request.urls().isEmpty()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL 列表不能为空"));
        }
        return brokenLinkService.addToWhitelist(request.urls())
            .thenReturn(new WhitelistResponse("已添加 " + request.urls().size() + " 个 URL 到白名单", request.urls().size()));
    }

    /**
     * 清空扫描结果
     */
    @DeleteMapping
    public Mono<ClearResponse> clearAll() {
        return brokenLinkService.clearAll()
            .thenReturn(new ClearResponse("断链扫描结果已清空"));
    }

    private StatusResponse toStatusResponse(com.timxs.storagetoolkit.extension.BrokenLinkScanStatus status) {
        var s = status.getStatus();
        return new StatusResponse(
            s != null && s.getPhase() != null ? s.getPhase().name() : null,
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

    public record WhitelistRequest(List<String> urls) {}

    public record WhitelistResponse(String message, int count) {}
}
