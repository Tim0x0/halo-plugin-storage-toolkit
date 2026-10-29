package com.timxs.storagetoolkit.environment;

import java.io.InputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import run.halo.app.infra.utils.JsonUtils;

/** Generated from the exact dependency artifacts used by this build. */
@Component
public class NativeDependencyCatalog {
    private final List<Dependency> dependencies;

    public NativeDependencyCatalog() {
        try (InputStream input = getClass().getResourceAsStream("/native-dependencies.json")) {
            if (input == null) throw new IllegalStateException("依赖清单不存在，请重新安装插件");
            dependencies = Arrays.asList(JsonUtils.mapper().readValue(input, Dependency[].class));
        } catch (Exception error) { throw new IllegalStateException("无法读取原生依赖清单", error); }
    }

    public List<Dependency> all() { return dependencies; }
    public Dependency require(String id) {
        return dependencies.stream().filter(dependency -> dependency.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("未知依赖"));
    }

    public Optional<Resource> resource(Dependency dependency, RuntimePlatform platform) {
        String path = resourcePath(dependency.id(), platform);
        return dependency.resources().stream().filter(resource -> resource.path().equals(path)).findFirst();
    }

    static String resourcePath(String id, RuntimePlatform platform) {
        String os = platform.os(), arch = platform.architecture();
        if (id.equals("sqlite")) {
            String folder = switch (os) {
                case "linux" -> platform.libc().equals("musl") ? "Linux-Musl" : "Linux";
                case "mac" -> "Mac";
                case "windows" -> "Windows";
                case "freebsd" -> "FreeBSD";
                default -> "Unknown";
            };
            String filename = os.equals("windows") ? "sqlitejdbc.dll" : os.equals("mac") ? "libsqlitejdbc.dylib" : "libsqlitejdbc.so";
            return "org/sqlite/native/" + folder + "/" + arch + "/" + filename;
        }
        // The bundled image codecs are glibc builds. Do not offer them as an Alpine musl repair.
        if (os.equals("linux") && platform.libc().equals("musl")) return "unsupported";
        String extension = os.equals("windows") ? ".dll" : os.equals("mac") ? ".dylib" : ".so";
        String prefix = os.equals("windows") ? "" : "lib";
        if (id.equals("webp")) {
            String folder = switch (os) { case "linux" -> "Linux"; case "mac" -> "Mac"; case "windows" -> "Windows"; default -> "Unknown"; };
            return "native/" + folder + "/" + arch + "/" + prefix + "webp-imageio" + extension;
        }
        String folder = switch (os) { case "linux" -> "linux"; case "mac" -> "mac"; case "windows" -> "win"; default -> "unknown"; };
        String nativeArch = arch.equals("aarch64") ? "arm64" : arch.equals("x86_64") ? "64" : arch;
        return "native/" + folder + "/" + nativeArch + "/" + prefix + "avif-imageio" + extension;
    }

    public record Dependency(String id, String version, URI url, long artifactSize, String artifactSha256, List<Resource> resources) {
        public String label() { return switch (id) { case "sqlite" -> "SQLite 日志存储"; case "webp" -> "WebP 图片处理"; case "avif" -> "AVIF 图片处理"; default -> id; }; }
    }
    public record Resource(String path, long size, String sha256, boolean bundled) {}
}
