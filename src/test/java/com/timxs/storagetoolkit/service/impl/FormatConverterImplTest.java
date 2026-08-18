package com.timxs.storagetoolkit.service.impl;

import com.timxs.storagetoolkit.model.ImageFormat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 格式转换器测试
 * 重点验证：跳过规范化复制、直接交给编码器的图像类型（ENCODER_SAFE_TYPES），
 * 编码后颜色通道顺序是否正确（防止 BGR/RGB 通道错位导致红蓝颠倒）
 */
class FormatConverterImplTest {

    /**
     * 重新扫描注册 ImageIO SPI
     * StorageToolkitPluginTest 的 stop() 会从全局注册表注销 WebP/AVIF SPI，
     * 与本类在同一 JVM 中先后执行时会导致编码器不可用，这里确保测试不受执行顺序影响
     */
    @BeforeAll
    static void ensureImageIoSpiRegistered() {
        ImageIO.scanForPlugins();
    }

    private final FormatConverterImpl converter = new FormatConverterImpl();

    /** 颜色容差：有损编码允许的单通道偏差（通道错位时红蓝差值为 255，远超容差，可明确判别） */
    private static final int TOLERANCE = 40;

    /**
     * 生成指定类型的三色条纹图：左 1/3 纯红、中 1/3 纯绿、右 1/3 纯蓝
     */
    private static BufferedImage stripes(int imageType) {
        BufferedImage image = new BufferedImage(90, 30, imageType);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 30, 30);
        g.setColor(Color.GREEN);
        g.fillRect(30, 0, 30, 30);
        g.setColor(Color.BLUE);
        g.fillRect(60, 0, 30, 30);
        g.dispose();
        return image;
    }

    private static boolean writerAvailable(String formatName) {
        return ImageIO.getImageWritersByFormatName(formatName).hasNext();
    }

    private static boolean readerAvailable(String formatName) {
        return ImageIO.getImageReadersByFormatName(formatName).hasNext();
    }

    private static void assertChannelOrder(byte[] encoded) throws IOException {
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(encoded));
        assertNotNull(decoded, "解码编码结果失败");
        assertColorNear(decoded, 15, 15, Color.RED, "左侧红色区");
        assertColorNear(decoded, 45, 15, Color.GREEN, "中间绿色区");
        assertColorNear(decoded, 75, 15, Color.BLUE, "右侧蓝色区");
    }

    private static void assertColorNear(BufferedImage image, int x, int y, Color expected, String label) {
        Color actual = new Color(image.getRGB(x, y), false);
        assertTrue(
            Math.abs(actual.getRed() - expected.getRed()) <= TOLERANCE
                && Math.abs(actual.getGreen() - expected.getGreen()) <= TOLERANCE
                && Math.abs(actual.getBlue() - expected.getBlue()) <= TOLERANCE,
            String.format("%s 颜色不符，期望 %s，实际 (%d, %d, %d)（红蓝颠倒说明通道顺序错误）",
                label, expected, actual.getRed(), actual.getGreen(), actual.getBlue()));
    }

    @Test
    void webpKeepsColorsForJpegDecodedType() throws IOException {
        assumeTrue(writerAvailable("webp") && readerAvailable("webp"), "WebP 编解码器不可用，跳过");
        // JPEG 解码产物类型
        byte[] encoded = converter.convert(stripes(BufferedImage.TYPE_3BYTE_BGR), ImageFormat.WEBP, 90, 4);
        assertChannelOrder(encoded);
    }

    @Test
    void webpKeepsColorsForPngDecodedType() throws IOException {
        assumeTrue(writerAvailable("webp") && readerAvailable("webp"), "WebP 编解码器不可用，跳过");
        // PNG（带透明通道）解码产物类型
        byte[] encoded = converter.convert(stripes(BufferedImage.TYPE_4BYTE_ABGR), ImageFormat.WEBP, 90, 4);
        assertChannelOrder(encoded);
    }

    @Test
    void webpKeepsColorsForIntTypes() throws IOException {
        assumeTrue(writerAvailable("webp") && readerAvailable("webp"), "WebP 编解码器不可用，跳过");
        assertChannelOrder(converter.convert(stripes(BufferedImage.TYPE_INT_RGB), ImageFormat.WEBP, 90, 4));
        assertChannelOrder(converter.convert(stripes(BufferedImage.TYPE_INT_ARGB), ImageFormat.WEBP, 90, 4));
    }

    @Test
    void webpKeepsColorsForNonSafeTypeViaNormalize() throws IOException {
        assumeTrue(writerAvailable("webp") && readerAvailable("webp"), "WebP 编解码器不可用，跳过");
        // 索引色类型不在直通白名单内，走规范化转换路径
        byte[] encoded = converter.convert(stripes(BufferedImage.TYPE_BYTE_INDEXED), ImageFormat.WEBP, 90, 4);
        assertChannelOrder(encoded);
    }

    @Test
    void avifKeepsColorsForJpegDecodedType() throws IOException {
        assumeTrue(writerAvailable("avif") && readerAvailable("avif"), "AVIF 编解码器不可用，跳过");
        byte[] encoded = converter.convert(stripes(BufferedImage.TYPE_3BYTE_BGR), ImageFormat.AVIF, 90, 4);
        assertChannelOrder(encoded);
    }

    @Test
    void avifKeepsColorsForPngDecodedType() throws IOException {
        assumeTrue(writerAvailable("avif") && readerAvailable("avif"), "AVIF 编解码器不可用，跳过");
        byte[] encoded = converter.convert(stripes(BufferedImage.TYPE_4BYTE_ABGR), ImageFormat.AVIF, 90, 4);
        assertChannelOrder(encoded);
    }
}
