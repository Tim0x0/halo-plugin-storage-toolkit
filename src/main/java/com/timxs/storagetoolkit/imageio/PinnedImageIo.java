package com.timxs.storagetoolkit.imageio;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.IIOServiceProvider;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 固定（pinned）ImageIO 编解码器
 * <p>
 * 背景：IIORegistry 是 JVM 级全局单例，所有 Halo 插件共享。其他插件若把自家的 WebP/AVIF SPI
 * 注册进来（停止/升级后 SPI 尸体仍留在注册表），本插件走 {@link ImageIO#read(InputStream)} 全局
 * 查找时可能被外来 SPI 劫持，解码时在对方的类加载器里找依赖类而抛 NoClassDefFoundError（issue #22）。
 * <p>
 * 本组件持有本插件自己的 SPI 实例：WebP/AVIF 的读写直接由实例创建 Reader/Writer，
 * 配合魔数嗅探与 MemoryCache 流，全程不查询全局注册表，外来 SPI 无法劫持；
 * JPEG/PNG 等 JDK 内置格式（其 SPI 为模块内部类，无法显式引用）仍走全局查找，
 * 由调用方的兜底逻辑保证可用性下限。
 *
 * @author Tim0x0
 * @since 1.2.1
 */
@Slf4j
@Component
public class PinnedImageIo {

    private static final String WEBP_READER_SPI = "com.luciad.imageio.webp.WebPImageReaderSpi";
    private static final String WEBP_WRITER_SPI = "com.luciad.imageio.webp.WebPImageWriterSpi";
    private static final String AVIF_READER_SPI = "com.github.avifimageio.AvifImageReaderSpi";
    private static final String AVIF_WRITER_SPI = "com.github.avifimageio.AvifImageWriterSpi";

    /**
     * 魔数嗅探所需的最小头部长度（RIFF....WEBP / ....ftypavif 均为 12 字节）
     */
    private static final int HEADER_LENGTH = 12;

    private volatile ImageReaderSpi webpReaderSpi;
    private volatile ImageWriterSpi webpWriterSpi;
    private volatile ImageReaderSpi avifReaderSpi;
    private volatile ImageWriterSpi avifWriterSpi;
    private volatile boolean webpLoaded;
    private volatile boolean avifLoaded;
    private volatile boolean webpAllowed = true;
    private volatile boolean avifAllowed = true;

    /** Missing resources must not trigger failed static initializers before installation. */
    public synchronized void configureAvailableFormats(boolean webp, boolean avif) {
        if (!webpAllowed && webp) webpLoaded = false;
        if (!avifAllowed && avif) avifLoaded = false;
        webpAllowed = webp;
        avifAllowed = avif;
    }

    public boolean isFormatAllowed(String format) {
        return "avif".equalsIgnoreCase(format) ? avifAllowed : !"webp".equalsIgnoreCase(format) || webpAllowed;
    }

    public void selfTest(String format) throws IOException {
        if (!isFormatAllowed(format)) throw new IOException(format + " 依赖尚未就绪");
        ImageWriter writer = createPinnedWriter(format);
        if (writer == null) throw new IOException(format + " 编码器不可用");
        var sample = new BufferedImage(8, 8, BufferedImage.TYPE_3BYTE_BGR);
        var bytes = new java.io.ByteArrayOutputStream();
        try (var output = new javax.imageio.stream.MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            var options = writer.getDefaultWriteParam();
            if (options.canWriteCompressed()) {
                options.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
                String[] types = options.getCompressionTypes();
                if (types != null && types.length > 0) options.setCompressionType(types[0]);
                options.setCompressionQuality(0.7f);
            }
            writer.write(null, new javax.imageio.IIOImage(sample, null, null), options);
        } finally { writer.dispose(); sample.flush(); }
        var decoded = decodePinned(format, new ByteArrayInputStream(bytes.toByteArray()));
        if (decoded == null || decoded.getWidth() != 8 || decoded.getHeight() != 8) throw new IOException(format + " 编解码自检失败");
        decoded.flush();
    }

    /**
     * 已注册到全局注册表的 SPI，用于插件停止时注销
     */
    private final List<IIOServiceProvider> registeredSpis = new ArrayList<>();

    /**
     * 插件启动时调用：加载 SPI 实例并注册到全局注册表
     * 注册到全局是为了让 Halo 核心等其他走 ImageIO 全局查找的代码也能识别 WebP/AVIF；
     * 本插件自身的读写不依赖全局注册表（见 {@link #read(byte[])} 与 {@link #createPinnedWriter(String)}）
     */
    public void register() {
        registerFormat("webp");
        registerFormat("avif");
    }

    public void registerFormat(String format) {
        ensureFormatLoaded(format);
        IIORegistry registry = IIORegistry.getDefaultInstance();
        IIOServiceProvider[] services = "webp".equals(format)
            ? new IIOServiceProvider[]{webpReaderSpi, webpWriterSpi}
            : new IIOServiceProvider[]{avifReaderSpi, avifWriterSpi};
        synchronized (registeredSpis) {
            for (IIOServiceProvider spi : services) {
                if (spi == null || registeredSpis.contains(spi)) continue;
                registry.registerServiceProvider(spi);
                registeredSpis.add(spi);
            }
        }
    }

    /**
     * 插件停止时调用：从全局注册表注销，避免类加载器泄漏
     */
    public void unregister() {
        synchronized (registeredSpis) {
            if (registeredSpis.isEmpty()) {
                return;
            }
            IIORegistry registry = IIORegistry.getDefaultInstance();
            for (IIOServiceProvider spi : registeredSpis) {
                try {
                    registry.deregisterServiceProvider(spi);
                    log.debug("SPI 注销成功: {}", spi.getClass().getName());
                } catch (Exception | LinkageError e) {
                    log.warn("SPI 注销失败: {} - {}", spi.getClass().getName(), e.getMessage());
                }
            }
            registeredSpis.clear();
        }
    }

    /**
     * 解码图片字节
     * 魔数命中 WebP/AVIF 时用本插件固定 SPI 解码（不查询全局注册表）；
     * 未命中或固定解码失败时回退 {@link ImageIO#read(InputStream)} 全局查找（与历史行为一致）
     *
     * @param data 图片字节
     * @return 解码结果，无法识别时返回 null
     * @throws IOException 全局查找路径的 IO 错误
     */
    public BufferedImage read(byte[] data) throws IOException {
        if (data == null) {
            return null;
        }
        String pinnedFormat = sniffFormat(data);
        if (pinnedFormat != null && !isFormatAllowed(pinnedFormat)) throw new IOException(pinnedFormat + " 依赖未就绪，请前往环境检测");
        if (pinnedFormat != null) {
            try {
                BufferedImage image = decodePinned(pinnedFormat, new ByteArrayInputStream(data));
                if (image != null) {
                    return image;
                }
            } catch (IOException | RuntimeException e) {
                log.warn("固定 SPI 解码 {} 失败，回退全局查找: {}", pinnedFormat, e.getMessage());
            }
        }
        return ImageIO.read(new ByteArrayInputStream(data));
    }

    /**
     * 解码图片流（水印图片等场景）
     * ImageIO.read(InputStream) 内部同样整流缓冲（MemoryCache），此处先读全量字节再复用字节版解码
     *
     * @param in 图片输入流
     * @return 解码结果，无法识别时返回 null
     * @throws IOException 读取失败
     */
    public BufferedImage read(InputStream in) throws IOException {
        if (in == null) {
            return null;
        }
        return read(in.readAllBytes());
    }

    /**
     * 创建指定格式的固定 Writer 实例
     * WebP/AVIF 返回本插件 SPI 创建的实例（不查询全局注册表）；其他格式返回 null，由调用方回退全局查找
     *
     * @param formatName 格式名（webp/avif，大小写不敏感）
     * @return Writer 实例，非固定格式或创建失败时返回 null
     */
    public ImageWriter createPinnedWriter(String formatName) {
        if (!isFormatAllowed(formatName)) return null;
        ensureFormatLoaded(formatName);
        ImageWriterSpi spi = switch (formatName == null ? "" : formatName.toLowerCase()) {
            case "webp" -> webpWriterSpi;
            case "avif" -> avifWriterSpi;
            default -> null;
        };
        if (spi == null) {
            return null;
        }
        try {
            return spi.createWriterInstance();
        } catch (IOException e) {
            log.warn("创建 {} 固定 Writer 失败: {}", formatName, e.getMessage());
            return null;
        }
    }

    /**
     * 魔数嗅探图片格式
     *
     * @param data 图片字节
     * @return "webp" / "avif"，未命中返回 null
     */
    private static String sniffFormat(byte[] data) {
        if (data == null || data.length < HEADER_LENGTH) {
            return null;
        }
        if (asciiAt(data, 0, "RIFF") && asciiAt(data, 8, "WEBP")) {
            return "webp";
        }
        // ISO BMFF 容器：第 4 字节起为 ftyp，第 8 字节起为 brand
        if (asciiAt(data, 4, "ftyp") && (asciiAt(data, 8, "avif") || asciiAt(data, 8, "avis"))) {
            return "avif";
        }
        return null;
    }

    private static boolean asciiAt(byte[] data, int offset, String token) {
        byte[] tokenBytes = token.getBytes(StandardCharsets.US_ASCII);
        return new String(data, offset, tokenBytes.length, StandardCharsets.US_ASCII).equals(token);
    }

    /**
     * 用固定 SPI 解码指定格式的流
     *
     * @return 解码结果，SPI 不可用时返回 null（调用方回退全局查找）
     */
    private BufferedImage decodePinned(String format, InputStream in) throws IOException {
        if (!isFormatAllowed(format)) return null;
        ensureFormatLoaded(format);
        ImageReaderSpi spi = "webp".equals(format) ? webpReaderSpi : avifReaderSpi;
        if (spi == null) {
            return null;
        }
        ImageReader reader = spi.createReaderInstance();
        try (ImageInputStream iis = new MemoryCacheImageInputStream(in)) {
            reader.setInput(iis, true, true);
            return reader.read(0);
        } finally {
            reader.dispose();
        }
    }

    /**
     * 懒加载 SPI 实例（插件启动注册与处理链固定解码共用）
     * 使用本组件自身的类加载器：生产环境为插件类加载器，测试环境为应用类加载器
     */
    private synchronized void ensureFormatLoaded(String format) {
        ClassLoader loader = getClass().getClassLoader();
        if ("webp".equalsIgnoreCase(format) && webpAllowed && !webpLoaded) {
            preloadWebPClasses(loader);
            webpReaderSpi = loadSpi(loader, WEBP_READER_SPI, ImageReaderSpi.class, "WebP");
            webpWriterSpi = loadSpi(loader, WEBP_WRITER_SPI, ImageWriterSpi.class, "WebP");
            webpLoaded = true;
        } else if ("avif".equalsIgnoreCase(format) && avifAllowed && !avifLoaded) {
            preloadAvifClasses(loader);
            avifReaderSpi = loadSpi(loader, AVIF_READER_SPI, ImageReaderSpi.class, "AVIF");
            avifWriterSpi = loadSpi(loader, AVIF_WRITER_SPI, ImageWriterSpi.class, "AVIF");
            avifLoaded = true;
        }
    }

    private <T extends IIOServiceProvider> T loadSpi(ClassLoader classLoader, String className,
                                                     Class<T> spiType, String formatName) {
        try {
            Class<?> spiClass = classLoader.loadClass(className);
            return spiType.cast(spiClass.getDeclaredConstructor().newInstance());
        } catch (Exception | LinkageError e) {
            log.warn("{} {} 加载失败: {}", formatName, spiType.getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * 预加载 WebP 相关类
     * 解决 ImageIO 在创建 Reader/Writer 实例时找不到依赖类的问题
     */
    private void preloadWebPClasses(ClassLoader classLoader) {
        // usefulness/webp-imageio：Reader/Writer 类名为 WebPReader/WebPWriter（非 WebPImage*）
        // 预加载 WebPWriteParam 会触发 companion 中的 native 库加载
        String[] classNames = {
            "com.luciad.imageio.webp.WebPReadParam",
            "com.luciad.imageio.webp.WebPWriteParam",
            "com.luciad.imageio.webp.WebPReader",
            "com.luciad.imageio.webp.WebPWriter",
            "com.luciad.imageio.webp.WebPDecoderOptions",
            "com.luciad.imageio.webp.WebPEncoderOptions",
            "com.luciad.imageio.webp.CompressionType"
        };

        for (String className : classNames) {
            try {
                Class<?> clazz = classLoader.loadClass(className);
                log.debug("预加载 WebP 类成功: {}", clazz.getName());
            } catch (ClassNotFoundException e) {
                log.warn("预加载 WebP 类失败: {}", className);
            }
        }
    }

    /**
     * 预加载 AVIF 相关类并初始化 native 库
     * 必须在正确的 ClassLoader 上下文中触发 native 库加载
     */
    private void preloadAvifClasses(ClassLoader classLoader) {
        // 保存原来的 contextClassLoader
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            // 设置插件的 ClassLoader，让 avif-imageio 能找到 native 库资源
            Thread.currentThread().setContextClassLoader(classLoader);

            // 加载 Avif 类并触发 native 库加载
            Class<?> avifClass = classLoader.loadClass("com.github.avifimageio.Avif");
            // 调用 isAvailable() 触发 native 库加载
            var isAvailableMethod = avifClass.getMethod("isAvailable");
            boolean available = (boolean) isAvailableMethod.invoke(null);

            if (available) {
                log.info("AVIF native 库加载成功");
            } else {
                // 获取加载错误信息
                var getLoadErrorMethod = avifClass.getMethod("getLoadError");
                Throwable error = (Throwable) getLoadErrorMethod.invoke(null);
                log.warn("AVIF native 库加载失败: {}", error != null ? error.getMessage() : "unknown");
            }

            // 预加载其他 AVIF 相关类
            String[] classNames = {
                "com.github.avifimageio.AvifImageReader",
                "com.github.avifimageio.AvifImageWriter",
                "com.github.avifimageio.AvifWriteParam",
                "com.github.avifimageio.AvifEncoderOptions",
                "com.github.avifimageio.AvifDecoderOptions"
            };

            for (String className : classNames) {
                try {
                    classLoader.loadClass(className);
                } catch (ClassNotFoundException e) {
                    log.warn("预加载 AVIF 类失败: {}", className);
                }
            }
        } catch (Exception | LinkageError e) {
            log.warn("AVIF 初始化失败: {}", e.getMessage());
        } finally {
            // 恢复原来的 contextClassLoader
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }
}
