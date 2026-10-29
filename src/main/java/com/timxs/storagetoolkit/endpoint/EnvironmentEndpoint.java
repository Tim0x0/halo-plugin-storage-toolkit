package com.timxs.storagetoolkit.endpoint;

import com.timxs.storagetoolkit.environment.EnvironmentStatus;
import com.timxs.storagetoolkit.environment.NativeEnvironmentService;
import lombok.RequiredArgsConstructor;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ApiVersion;

@ApiVersion("console.api.storage-toolkit.timxs.com/v1alpha1")
@RestController
@RequestMapping("/environment")
@RequiredArgsConstructor
public class EnvironmentEndpoint {
    private final NativeEnvironmentService environment;

    @GetMapping
    public Mono<EnvironmentStatus> status() { return Mono.fromSupplier(environment::status); }

    @PostMapping("/check")
    public Mono<EnvironmentStatus> check() { return environment.refresh(); }

    @PostMapping("/dependencies/{id}/install")
    public Mono<EnvironmentStatus> install(@PathVariable String id) {
        return environment.install(id)
            // install() caches the operation, so a short HTTP response does not cancel it.
            .timeout(Duration.ofSeconds(1), Mono.fromSupplier(environment::status))
            .onErrorMap(IllegalArgumentException.class, e -> new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e))
            .onErrorMap(IllegalStateException.class, e -> new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e));
    }
}
