package com.timxs.storagetoolkit.environment;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;
import org.pf4j.PluginClassLoader;
import org.springframework.stereotype.Component;
import run.halo.app.plugin.PluginsRootGetter;
import com.timxs.storagetoolkit.environment.NativeDependencyCatalog.Dependency;
import com.timxs.storagetoolkit.environment.NativeDependencyCatalog.Resource;

/** Persists verified, resource-only JARs under Halo's plugin data directory. */
@Component
public class NativeResourceStore {
    private final Path directory;
    private final Set<Path> attached = new HashSet<>();
    private final ClassLoader loader = NativeResourceStore.class.getClassLoader();

    public NativeResourceStore(PluginsRootGetter roots) {
        directory = roots.get().resolve("storage-toolkit").resolve("native-dependencies");
    }

    public Path directory() { return directory; }

    public boolean bundled(Resource resource) throws Exception {
        try (InputStream input = loader.getResourceAsStream(resource.path())) {
            return input != null && sha256(input, resource.size()).equals(resource.sha256());
        }
    }

    public boolean installed(Dependency dependency, Resource resource) throws Exception {
        Path jar = destination(dependency, resource);
        if (!Files.isRegularFile(jar) || Files.isSymbolicLink(jar)) return false;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry(resource.path());
            if (entry == null || zip.size() != 1) return false;
            try (InputStream input = zip.getInputStream(entry)) {
                return sha256(input, resource.size()).equals(resource.sha256());
            }
        }
    }

    public synchronized boolean attach(Dependency dependency, Resource resource) throws Exception {
        if (bundled(resource)) return true;
        Path jar = destination(dependency, resource);
        if (!installed(dependency, resource)) return false;
        if (!(loader instanceof PluginClassLoader pluginLoader)) {
            throw new IllegalStateException("当前类加载环境不支持在线激活，请重启 Halo 后重试");
        }
        if (attached.add(jar)) pluginLoader.addURL(jar.toUri().toURL());
        return true;
    }

    public Path installArchive(Dependency dependency, Resource resource, Path archive) throws Exception {
        if (Files.size(archive) != dependency.artifactSize()) throw new IllegalArgumentException("依赖下载大小不符，未安装");
        try (InputStream input = Files.newInputStream(archive)) {
            if (!sha256(input, dependency.artifactSize()).equals(dependency.artifactSha256())) {
                throw new IllegalArgumentException("依赖下载校验失败，未安装，请重试或检查下载代理");
            }
        }
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".install-", ".tmp");
        try {
            try (ZipFile zip = new ZipFile(archive.toFile()); JarOutputStream output = new JarOutputStream(Files.newOutputStream(temporary))) {
                var entry = zip.getEntry(resource.path());
                if (entry == null || entry.getSize() != resource.size()) throw new IllegalArgumentException("下载包缺少匹配的平台依赖");
                byte[] bytes;
                try (InputStream input = zip.getInputStream(entry)) { bytes = input.readNBytes(Math.toIntExact(resource.size()) + 1); }
                if (bytes.length != resource.size() || !digest(bytes).equals(resource.sha256())) throw new IllegalArgumentException("原生库校验失败，未安装");
                output.putNextEntry(new JarEntry(resource.path()));
                output.write(bytes);
                output.closeEntry();
            }
            Path destination = destination(dependency, resource);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return destination;
        } finally { Files.deleteIfExists(temporary); }
    }

    private Path destination(Dependency dependency, Resource resource) {
        return directory.resolve(dependency.id() + "-" + dependency.version() + "-" + resource.sha256().substring(0, 16) + ".jar");
    }

    static String sha256(InputStream input, long limit) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[16384];
        long count = 0;
        int size;
        while ((size = input.read(buffer)) != -1) {
            count += size;
            if (count > limit) throw new IllegalArgumentException("依赖文件超过预期大小");
            digest.update(buffer, 0, size);
        }
        if (count != limit) throw new IllegalArgumentException("依赖文件不完整");
        return HexFormat.of().formatHex(digest.digest());
    }

    private String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
