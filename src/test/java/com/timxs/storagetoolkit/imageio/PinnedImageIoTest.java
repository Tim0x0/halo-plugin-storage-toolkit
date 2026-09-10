package com.timxs.storagetoolkit.imageio;

import com.luciad.imageio.webp.CompressionType;
import com.luciad.imageio.webp.WebPWriteParam;
import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 固定 ImageIO 编解码器测试
 * 重点验证 issue #22 的回归契约：即使全局注册表里存在会抛 NoClassDefFoundError 的外来 WebP SPI
 * （模拟其他插件停止后遗留的 SPI 尸体），本插件的 WebP/AVIF 解码仍走固定 SPI 正常完成
 */
class PinnedImageIoTest {

    /** 不注册到全局注册表，仅使用固定 SPI 路径，避免污染同 JVM 的其他测试 */
    private final PinnedImageIo pins = new PinnedImageIo();

    private static BufferedImage stripes() {
        BufferedImage image = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 64, 48);
        g.setColor(Color.GREEN);
        g.fillRect(21, 16, 22, 16);
        g.dispose();
        return image;
    }

    private byte[] encodeWebP(boolean lossless) throws IOException {
        ImageWriter writer = pins.createPinnedWriter("webp");
        assertNotNull(writer, "WebP 固定 Writer 不可用");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (var ios = new MemoryCacheImageOutputStream(bos)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (lossless) {
                WebPWriteParam webpParam = (WebPWriteParam) param;
                webpParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                webpParam.setCompressionType(CompressionType.Lossless);
            }
            writer.write(null, new IIOImage(stripes(), null, null), param);
        } finally {
            writer.dispose();
        }
        return bos.toByteArray();
    }

    @Test
    void webpRoundTrip() throws IOException {
        BufferedImage lossy = pins.read(encodeWebP(false));
        assertNotNull(lossy, "有损 WebP 解码失败");
        assertEquals(64, lossy.getWidth());
        assertEquals(48, lossy.getHeight());

        BufferedImage lossless = pins.read(encodeWebP(true));
        assertNotNull(lossless, "无损 WebP 解码失败");
        assertEquals(64, lossless.getWidth());
    }

    @Test
    void losslessWebpDecodesDespiteHijackingSpiInGlobalRegistry() throws IOException {
        byte[] lossless = encodeWebP(true);
        HijackingWebPReaderSpi hijack = new HijackingWebPReaderSpi();
        IIORegistry registry = IIORegistry.getDefaultInstance();
        registry.registerServiceProvider(hijack);
        try {
            // 全局注册表里躺着会抛 NoClassDefFoundError 的外来 SPI（issue #22 场景），固定解码必须不受影响
            BufferedImage decoded = pins.read(lossless);
            assertNotNull(decoded, "外来 SPI 劫持下无损 WebP 解码失败");
            assertEquals(64, decoded.getWidth());
        } finally {
            registry.deregisterServiceProvider(hijack);
        }
    }

    @Test
    void nonPinnedFormatFallsBackToGlobalLookup() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        boolean written = ImageIO.write(stripes(), "png", bos);
        assumeTrue(written, "JDK PNG writer 不可用，跳过");
        assertNotNull(pins.read(bos.toByteArray()), "PNG 应回退全局查找解码");
    }

    @Test
    void sniffRejectsNonWebpAvif() throws IOException {
        assertNull(pins.read(new byte[]{1, 2, 3}), "过短数据应返回 null 而非抛异常");
    }

    /**
     * 模拟外来 WebP reader SPI：魔数认领 WebP 流，但创建 Reader 时抛 issue #22 的同款错误
     * （对应其他插件停止/升级后类加载器已关闭、SPI 尸体留在 JVM 级全局注册表的状态）
     */
    static class HijackingWebPReaderSpi extends ImageReaderSpi {

        HijackingWebPReaderSpi() {
            super("hijack-vendor", "1.0",
                new String[]{"WebP"}, new String[]{"webp"}, new String[]{"image/webp"},
                HijackingWebPReader.class.getName(),
                new Class[]{ImageInputStream.class},
                null,
                false, null, null, null, null,
                false, null, null, null, null);
        }

        @Override
        public boolean canDecodeInput(Object source) throws IOException {
            if (!(source instanceof ImageInputStream iis)) {
                return false;
            }
            iis.mark();
            try {
                byte[] header = new byte[12];
                if (iis.read(header) != 12) {
                    return false;
                }
                String riff = new String(header, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
                String webp = new String(header, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
                return "RIFF".equals(riff) && "WEBP".equals(webp);
            } finally {
                iis.reset();
            }
        }

        @Override
        public ImageReader createReaderInstance(Object extension) {
            // 模拟尸体 SPI：依赖类所在的类加载器已关闭
            throw new NoClassDefFoundError("com/twelvemonkeys/imageio/plugins/webp/lossless/VP8LDecoder");
        }

        @Override
        public String getDescription(Locale locale) {
            return "Hijacking WebP reader (test double)";
        }
    }

    /** 仅为满足 SPI 构造参数的类名占位，不会被实例化 */
    static class HijackingWebPReader extends ImageReader {
        HijackingWebPReader(ImageReaderSpi originatingProvider) {
            super(originatingProvider);
        }

        @Override
        public int getNumImages(boolean allowSearch) {
            return 1;
        }

        @Override
        public int getWidth(int imageIndex) {
            return 0;
        }

        @Override
        public int getHeight(int imageIndex) {
            return 0;
        }

        @Override
        public BufferedImage read(int imageIndex, ImageReadParam param) {
            throw new NoClassDefFoundError("com/twelvemonkeys/imageio/plugins/webp/lossless/VP8LDecoder");
        }

        @Override
        public java.util.Iterator<javax.imageio.ImageTypeSpecifier> getImageTypes(int imageIndex) {
            return null;
        }

        @Override
        public javax.imageio.metadata.IIOMetadata getImageMetadata(int imageIndex) {
            return null;
        }

        @Override
        public javax.imageio.metadata.IIOMetadata getStreamMetadata() {
            return null;
        }
    }
}
