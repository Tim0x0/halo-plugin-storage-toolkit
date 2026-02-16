package com.timxs.storagetoolkit.endpoint;

import com.timxs.storagetoolkit.service.DuplicateService;
import com.timxs.storagetoolkit.service.ReferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ApiVersion;

import java.util.List;

/**
 * 清理操作 REST API 端点
 * 提供删除重复文件和未引用文件功能
 */
@ApiVersion("console.api.storage-toolkit.timxs.com/v1alpha1")
@RestController
@RequestMapping("/cleanup")
@RequiredArgsConstructor
public class CleanupEndpoint {

    private final DuplicateService duplicateService;
    private final ReferenceService referenceService;

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

        return duplicateService.deleteDuplicates(groupMd5, request.attachmentNames(), request.replaceReferences())
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

    // ========== 请求/响应对象 ==========

    public record DeleteRequest(List<String> attachmentNames, Boolean replaceReferences) {}

    public record CleanupResultResponse(
        int deletedCount,
        int failedCount,
        long freedSize,
        List<String> errors
    ) {}
}
