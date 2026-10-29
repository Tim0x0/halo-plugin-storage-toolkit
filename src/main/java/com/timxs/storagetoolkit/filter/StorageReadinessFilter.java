package com.timxs.storagetoolkit.filter;

import com.timxs.storagetoolkit.storage.StorageRuntime;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import run.halo.app.security.AfterSecurityWebFilter;

/** Gives actionable errors while keeping environment and attachment analysis available. */
@Component
@RequiredArgsConstructor
public class StorageReadinessFilter implements AfterSecurityWebFilter {
    private static final String PREFIX = "/apis/console.api.storage-toolkit.timxs.com/v1alpha1/";
    private static final Set<String> STORAGE_RESOURCES = Set.of("batchprocessing", "processinglogs", "cleanuplogs", "urlreplacelogs", "cleanup");
    private final StorageRuntime storage;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return Mono.defer(() -> {
            String path = exchange.getRequest().getPath().pathWithinApplication().value();
            if (path.startsWith(PREFIX)) {
                String operation = path.substring(PREFIX.length());
                String resource = operation.split("/", 2)[0];
                if (STORAGE_RESOURCES.contains(resource) || operation.equals("brokenlinks/replace")) storage.requireReady();
            }
            return chain.filter(exchange);
        });
    }
}
