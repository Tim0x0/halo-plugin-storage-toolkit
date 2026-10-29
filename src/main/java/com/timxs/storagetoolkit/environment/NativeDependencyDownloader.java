package com.timxs.storagetoolkit.environment;

import com.timxs.storagetoolkit.service.SettingsManager;
import com.timxs.storagetoolkit.service.support.ProxiedWebClientFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import com.timxs.storagetoolkit.environment.NativeDependencyCatalog.Dependency;
import com.timxs.storagetoolkit.environment.NativeDependencyCatalog.Resource;

/** Downloads only catalog-pinned artifacts; credentials and arbitrary URLs are never accepted. */
@Component
@RequiredArgsConstructor
public class NativeDependencyDownloader {
    private final NativeResourceStore store;
    private final ProxiedWebClientFactory clients;
    private final SettingsManager settings;

    public Mono<Path> download(Dependency dependency, Resource resource) {
        if (!"https".equals(dependency.url().getScheme()) || !Set.of("repo.maven.apache.org", "jitpack.io").contains(dependency.url().getHost())) {
            return Mono.error(new IllegalArgumentException("依赖下载地址不受支持"));
        }
        return Mono.usingWhen(Mono.fromCallable(() -> {
            Files.createDirectories(store.directory());
            return Files.createTempFile(store.directory(), ".download-", ".tmp");
        }).subscribeOn(Schedulers.boundedElastic()), temporary -> transfer(dependency, temporary)
            .then(Mono.fromCallable(() -> store.installArchive(dependency, resource, temporary)).subscribeOn(Schedulers.boundedElastic())),
            this::deleteTemporary, (temporary, error) -> deleteTemporary(temporary), this::deleteTemporary);
    }

    private Mono<Void> transfer(Dependency dependency, Path temporary) {
        return settings.getProxySettings().defaultIfEmpty(SettingsManager.ProxySettings.disabled()).flatMap(proxy -> {
            AtomicLong received = new AtomicLong();
            var content = clients.get(60, proxy, true).get().uri(dependency.url()).retrieve().bodyToFlux(DataBuffer.class)
                .<DataBuffer>handle((buffer, sink) -> {
                    if (received.addAndGet(buffer.readableByteCount()) > dependency.artifactSize()) {
                        DataBufferUtils.release(buffer);
                        sink.error(new IllegalArgumentException("依赖下载超过预期大小"));
                    } else sink.next(buffer);
                }).doOnDiscard(DataBuffer.class, DataBufferUtils::release);
            return DataBufferUtils.write(content, temporary).timeout(Duration.ofMinutes(3));
        });
    }

    private Mono<Void> deleteTemporary(Path path) {
        return Mono.fromRunnable(() -> {
            try { Files.deleteIfExists(path); } catch (Exception ignored) { }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }
}
