package com.timxs.storagetoolkit.environment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Uses local runtime facts only; no Docker socket, shell execution or host filesystem access. */
@Component
public class PlatformDetector {
    public RuntimePlatform detect() {
        String os = normalizeOs(System.getProperty("os.name", "unknown"));
        String arch = normalizeArchitecture(System.getProperty("os.arch", "unknown"));
        String libc = "linux".equals(os) ? detectLibc() : "none";
        String evidence = "";
        if (Files.exists(Path.of("/.dockerenv"))) evidence = "/.dockerenv";
        else if (Files.exists(Path.of("/run/.containerenv"))) evidence = "/run/.containerenv";
        else {
            String cgroup = read(Path.of("/proc/1/cgroup"));
            if (cgroup.contains("docker") || cgroup.contains("kubepods") || cgroup.contains("containerd")) evidence = "cgroup";
        }
        return new RuntimePlatform(os, arch, libc, distribution(os), evidence.isEmpty() ? "UNCONFIRMED" : "CONTAINER",
            evidence.isEmpty() ? "未发现明确容器标记，可能为直接运行或受限容器" : "检测到 " + evidence,
            System.getProperty("java.version", "unknown"));
    }

    static String normalizeOs(String raw) {
        String value = raw.toLowerCase(Locale.ROOT);
        if (value.contains("darwin") || value.contains("mac")) return "mac";
        if (value.contains("win")) return "windows";
        if (value.contains("linux")) return "linux";
        if (value.contains("freebsd")) return "freebsd";
        return "unknown";
    }

    static String normalizeArchitecture(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            case "x86", "i386", "i486", "i586", "i686" -> "x86";
            default -> raw.toLowerCase(Locale.ROOT);
        };
    }

    private String detectLibc() {
        for (String directory : new String[]{"/lib", "/lib64", "/usr/lib"}) {
            try (var paths = Files.list(Path.of(directory))) {
                if (paths.anyMatch(path -> path.getFileName().toString().startsWith("ld-musl-"))) return "musl";
            } catch (Exception ignored) { }
        }
        for (String path : new String[]{"/lib64/ld-linux-x86-64.so.2", "/lib/ld-linux-aarch64.so.1",
            "/lib/x86_64-linux-gnu/libc.so.6", "/lib/aarch64-linux-gnu/libc.so.6"}) {
            if (Files.exists(Path.of(path))) return "glibc";
        }
        return "unknown";
    }

    private String distribution(String os) {
        if (!"linux".equals(os)) return System.getProperty("os.name", os);
        return read(Path.of("/etc/os-release")).lines().filter(line -> line.startsWith("PRETTY_NAME="))
            .map(line -> line.substring("PRETTY_NAME=".length()).replace("\"", ""))
            .findFirst().orElse("Linux");
    }

    private String read(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > 65536) return "";
            return Files.readString(path);
        } catch (Exception ignored) { return ""; }
    }
}
