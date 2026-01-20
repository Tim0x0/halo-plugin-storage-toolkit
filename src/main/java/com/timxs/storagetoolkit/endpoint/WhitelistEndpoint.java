package com.timxs.storagetoolkit.endpoint;

import com.timxs.storagetoolkit.service.WhitelistService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ApiVersion;

import java.util.List;

/**
 * 白名单管理 REST API 端点
 */
@ApiVersion("console.api.storage-toolkit.timxs.com/v1alpha1")
@RestController
@RequestMapping("/whitelist")
@RequiredArgsConstructor
public class WhitelistEndpoint {

    private final WhitelistService whitelistService;

    /**
     * 获取所有白名单
     */
    @GetMapping
    public Flux<WhitelistService.WhitelistItem> list() {
        return whitelistService.list();
    }

    /**
     * 搜索白名单
     */
    @GetMapping("/search")
    public Flux<WhitelistService.WhitelistItem> search(@RequestParam String keyword) {
        return whitelistService.search(keyword);
    }

    /**
     * 添加单个白名单
     */
    @PostMapping
    public Mono<WhitelistService.WhitelistItem> add(@RequestBody AddRequest request) {
        if (request.url == null || request.url.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL 不能为空"));
        }
        return whitelistService.add(request.url, request.note, request.matchMode);
    }

    /**
     * 批量添加白名单
     */
    @PostMapping("/batch")
    public Flux<WhitelistService.WhitelistItem> addBatch(@RequestBody BatchAddRequest request) {
        if (request.urls == null || request.urls.isEmpty()) {
            return Flux.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL 列表不能为空"));
        }
        return whitelistService.addBatch(request.urls);
    }

    /**
     * 删除白名单
     */
    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable String name) {
        return whitelistService.delete(name)
            .onErrorResume(e -> Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "白名单不存在")));
    }

    /**
     * 清空所有白名单
     */
    @DeleteMapping("/all")
    public Mono<Void> clearAll() {
        return whitelistService.clearAll();
    }

    /**
     * 检查 URL 是否在白名单中
     */
    @GetMapping("/check")
    public Mono<CheckResponse> check(@RequestParam String url) {
        return whitelistService.isWhitelisted(url)
            .map(CheckResponse::new);
    }

    // ========== 请求/响应对象 ==========

    public record AddRequest(
        String url,
        String note,
        String matchMode
    ) {}

    public record BatchAddRequest(List<String> urls) {}

    public record CheckResponse(boolean whitelisted) {}
}
