package com.timxs.storagetoolkit.endpoint;

import com.timxs.storagetoolkit.service.support.ImageComparisonService;
import com.timxs.storagetoolkit.service.support.OperationPreviewService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import run.halo.app.plugin.ApiVersion;

@ApiVersion("console.api.storage-toolkit.timxs.com/v1alpha1")
@RestController
@RequestMapping("/previews")
@RequiredArgsConstructor
public class OperationPreviewEndpoint {
    private final ImageComparisonService images;
    private final OperationPreviewService operations;

    @PostMapping("/images/{name}")
    public Mono<ResponseEntity<ImageComparisonService.Comparison>> compare(
            @PathVariable String name) {
        return images.compare(name)
                .map(result -> ResponseEntity.ok().header("Cache-Control", "no-store").body(result))
                .onErrorMap(
                        IllegalArgumentException.class,
                        e -> new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e))
                .onErrorMap(
                        IllegalStateException.class,
                        e -> new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e));
    }

    @PostMapping("/operations")
    public Mono<OperationPreviewService.Preview> preview(
            @RequestBody OperationPreviewService.Request request) {
        return operations
                .preview(request)
                .onErrorMap(
                        IllegalArgumentException.class,
                        e -> new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e))
                .onErrorMap(
                        IllegalStateException.class,
                        e -> new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e));
    }
}
