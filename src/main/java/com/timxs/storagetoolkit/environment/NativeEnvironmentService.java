package com.timxs.storagetoolkit.environment;

import com.timxs.storagetoolkit.environment.EnvironmentStatus.DependencyStatus;
import com.timxs.storagetoolkit.environment.NativeDependencyCatalog.Dependency;
import com.timxs.storagetoolkit.imageio.PinnedImageIo;
import com.timxs.storagetoolkit.storage.StorageRuntime;
import com.timxs.storagetoolkit.storage.ToolkitDatabase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Orchestrates dependency availability without requiring SQLite to serve diagnostics. */
@Slf4j
@Service
@RequiredArgsConstructor
public class NativeEnvironmentService {
    private final PlatformDetector platforms;
    private final NativeDependencyCatalog catalog;
    private final NativeResourceStore resources;
    private final NativeDependencyDownloader downloader;
    private final PinnedImageIo images;
    private final StorageRuntime storage;
    private final ToolkitDatabase database;
    private final AtomicBoolean installing = new AtomicBoolean();
    private final Map<String, DependencyStatus> statuses = new LinkedHashMap<>();
    private final Set<String> tested = new HashSet<>();
    private volatile Instant checkedAt;
    private volatile boolean stopping;

    public void start() {
        stopping = false;
        refreshSync();
    }

    public void stop() { stopping = true; }

    public synchronized EnvironmentStatus status() {
        return new EnvironmentStatus(platforms.detect(), storage.isReady(), storage.error(), checkedAt, new ArrayList<>(statuses.values()));
    }

    public Mono<EnvironmentStatus> refresh() {
        return Mono.fromCallable(this::refreshSync).subscribeOn(Schedulers.boundedElastic());
    }

    private synchronized EnvironmentStatus refreshSync() {
        if (stopping) return status();
        RuntimePlatform platform = platforms.detect();
        Set<String> present = new HashSet<>();
        for (Dependency dependency : catalog.all()) {
            DependencyStatus previous = statuses.get(dependency.id());
            if (previous != null && previous.state().equals("INSTALLING")) continue;
            var candidate = catalog.resource(dependency, platform);
            if (candidate.isEmpty()) {
                update(dependency, "UNSUPPORTED", "NONE", "当前系统或架构没有匹配的原生库，可继续使用其他可用功能", false);
                continue;
            }
            try {
                var resource = candidate.get();
                boolean attached = resources.attach(dependency, resource);
                if (attached) {
                    present.add(dependency.id());
                    String source = resources.installed(dependency, resource) ? "DOWNLOADED" : "BUNDLED";
                    if (previous == null || previous.state().equals("MISSING") || previous.state().equals("DOWNLOAD_FAILED")) {
                        update(dependency, "AVAILABLE", source, "依赖已找到，正在检查可用性", false);
                    } else update(dependency, previous.state(), source, previous.message(), previous.canDownload());
                } else update(dependency, "MISSING", "NONE", "当前平台依赖未安装，请下载后激活", true);
            } catch (Exception | LinkageError error) {
                update(dependency, "LOAD_FAILED", "NONE", concise(error), true);
            }
        }
        images.configureAvailableFormats(present.contains("webp"), present.contains("avif"));
        for (Dependency dependency : catalog.all()) {
            if (!present.contains(dependency.id())) continue;
            DependencyStatus previous = statuses.get(dependency.id());
            if (tested.contains(dependency.id()) && previous != null && !previous.state().equals("READY")) continue;
            try {
                if (dependency.id().equals("sqlite")) {
                    database.allowNativeAccess();
                    storage.initialize();
                } else if (!tested.contains(dependency.id())) {
                    images.selfTest(dependency.id());
                    images.registerFormat(dependency.id());
                }
                tested.add(dependency.id());
                update(dependency, "READY", previous == null ? "BUNDLED" : previous.source(), "已通过可用性检查", false);
            } catch (Exception | LinkageError error) {
                tested.add(dependency.id());
                update(dependency, "RESTART_REQUIRED", previous == null ? "NONE" : previous.source(),
                    "依赖已找到但激活失败：" + concise(error) + "。请检查兼容性与目录权限，重启 Halo 后重新检测", false);
                if (dependency.id().equals("sqlite")) storage.unavailable("日志存储激活失败，请前往环境检测");
            }
        }
        images.configureAvailableFormats(isReady("webp"), isReady("avif"));
        if (!storage.isReady()) {
            String message = "SQLite 日志存储未就绪，请前往环境检测安装依赖或查看失败原因";
            database.suspend(message);
            storage.unavailable(message);
        }
        checkedAt = Instant.now();
        return status();
    }

    public Mono<EnvironmentStatus> install(String id) {
        return Mono.defer(() -> {
            if (stopping) return Mono.error(new IllegalStateException("插件正在停止，请稍后重试"));
            Dependency dependency = catalog.require(id);
            var resource = catalog.resource(dependency, platforms.detect())
                .orElseThrow(() -> new IllegalArgumentException("当前平台没有可下载的匹配依赖"));
            synchronized (this) {
                var status = statuses.get(id);
                if (status != null && !status.canDownload()) return Mono.error(new IllegalStateException("该依赖无需下载，请重新检测或按提示重启 Halo"));
            }
            if (!installing.compareAndSet(false, true)) return Mono.error(new IllegalStateException("已有依赖正在安装，请稍后重试"));
            synchronized (this) { update(dependency, "INSTALLING", "NONE", "正在下载并校验依赖，请稍候", false); }
            return downloader.download(dependency, resource)
                .then(Mono.fromCallable(() -> {
                    synchronized (this) {
                        if (stopping) return status();
                        update(dependency, tested.contains(id) ? "RESTART_REQUIRED" : "MISSING", "DOWNLOADED",
                            tested.contains(id) ? "依赖已安装，重启 Halo 后生效" : "依赖已安装，正在激活", false);
                        return refreshSync();
                    }
                }).subscribeOn(Schedulers.boundedElastic()))
                .onErrorResume(error -> Mono.fromCallable(() -> {
                    synchronized (this) { update(dependency, "DOWNLOAD_FAILED", "NONE", concise(error), true); return status(); }
                }))
                .doFinally(signal -> installing.set(false));
        }).cache();
    }

    private boolean isReady(String id) {
        var status = statuses.get(id);
        return status != null && status.state().equals("READY");
    }

    private void update(Dependency dependency, String state, String source, String message, boolean downloadable) {
        statuses.put(dependency.id(), new DependencyStatus(dependency.id(), dependency.label(), dependency.version(),
            state, source, message, downloadable, dependency.artifactSize()));
    }

    private String concise(Throwable error) {
        Throwable cause = error;
        for (int i = 0; i < 8 && cause.getCause() != null; i++) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message.substring(0, Math.min(message.length(), 500));
    }
}
