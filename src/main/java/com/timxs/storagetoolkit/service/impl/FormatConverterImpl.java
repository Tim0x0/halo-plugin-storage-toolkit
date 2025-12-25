package com.timxs.storagetoolkit.service.impl;

import com.timxs.storagetoolkit.model.ImageFormat;
import com.timxs.storagetoolkit.service.FormatConverter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Set;

/**
 * 格式转换器实现
 * 使用 WebP ImageIO 库实现图片格式转换
 */
@Slf4j
@Service
public class FormatConverterImpl implements FormatConverter {

    /**
     * 支持的目标格式集合
     * 目前仅支持 WebP 格式
     */
    private static final Set<ImageFormat> SUPPORTED_FORMATS = Set.of(
        ImageFormat.WEBP
    );

    /**
     * 转换图片格式
     * 将 BufferedImage 转换为指定格式的字节数组
     *
     * @param image        BufferedImage 对象
     * @param targetFormat 目标格式（不能是 ORIGINAL）
     * @param quality      输出质量（0-100）
     * @return 转换后的字节数组
     * @throws IllegalArgumentException      图片为空或目标格式无效
     * @throws UnsupportedOperationException 不支持的格式
     * @throws RuntimeException              转换失败
     */
    @Override
    public byte[] convert(BufferedImage image, ImageFormat targetFormat, int quality) {
        // 参数校验
        if (image == null) {
            throw new IllegalArgumentException("Image cannot be null");
        }
        if (targetFormat == null || targetFormat == ImageFormat.ORIGINAL) {
            throw new IllegalArgumentException("Target format must be specified and not ORIGINAL");
        }
        if (!supportsFormat(targetFormat)) {
            throw new UnsupportedOperationException("Format not supported: " + targetFormat);
        }

        log.debug("开始格式转换，输入图片尺寸: {}x{}, 类型: {}, 目标格式: {}, 质量: {}", 
            image.getWidth(), image.getHeight(), image.getType(), targetFormat, quality);

        // 统一转换为 RGB 格式（去除 Alpha 通道），这是最主流的做法
        BufferedImage rgbImage = convertToRGB(image);

        // 保存当前线程的类加载器，用于后续恢复
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            // 设置插件类加载器，确保 ImageIO 能找到 WebP 的 SPI
            Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader());

            // 诊断：列出所有可用的 ImageWriter 格式
            String[] writerFormats = ImageIO.getWriterFormatNames();
            log.debug("可用的 ImageWriter 格式: {}", String.join(", ", writerFormats));

            // 诊断：检查 WebP writer 是否可用
            Iterator<ImageWriter> webpWriters = ImageIO.getImageWritersByFormatName("webp");
            if (webpWriters.hasNext()) {
                ImageWriter webpWriter = webpWriters.next();
                log.debug("WebP ImageWriter 可用: {}", webpWriter.getClass().getName());
            } else {
                log.warn("WebP ImageWriter 不可用！检查 native 库是否加载成功，系统架构: {} {}",
                    System.getProperty("os.name"), System.getProperty("os.arch"));
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            String formatName = targetFormat.getExtension();

            // 获取对应格式的 ImageWriter
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(formatName);
            if (!writers.hasNext()) {
                throw new RuntimeException("No appropriate writer found for format: " + targetFormat);
            }
            
            ImageWriter writer = writers.next();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(outputStream)) {
                writer.setOutput(ios);
                
                // 配置压缩参数
                ImageWriteParam param = writer.getDefaultWriteParam();
                if (param.canWriteCompressed()) {
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    // WebP 需要先设置压缩类型
                    String[] compressionTypes = param.getCompressionTypes();
                    if (compressionTypes != null && compressionTypes.length > 0) {
                        param.setCompressionType(compressionTypes[0]);
                    }
                    // 质量参数范围 0.0-1.0
                    param.setCompressionQuality(quality / 100.0f);
                }
                
                // 执行写入
                writer.write(null, new IIOImage(rgbImage, null, null), param);
            } finally {
                writer.dispose();
            }
            
            log.debug("Converted image to {} format with quality {}, size: {} KB", 
                targetFormat, quality, String.format("%.2f", outputStream.size() / 1024.0));
            return outputStream.toByteArray();
        } catch (IOException e) {
            log.error("Failed to convert image to {}", targetFormat, e);
            throw new RuntimeException("Failed to convert image to " + targetFormat, e);
        } finally {
            // 恢复原来的类加载器
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    /**
     * 将任意类型的 BufferedImage 转换为 TYPE_INT_RGB
     * 这是处理带 Alpha 通道图片的标准做法，WebP 等格式需要 RGB 输入
     *
     * @param src 源图片
     * @return RGB 格式的图片
     */
    private BufferedImage convertToRGB(BufferedImage src) {
        // 如果已经是 RGB 格式，直接返回
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            log.debug("图片已经是 RGB 格式，无需转换");
            return src;
        }
        
        log.debug("将图片从类型 {} 转换为 RGB", src.getType());
        // 创建新的 RGB 图片
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        // 白色背景填充透明区域
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, src.getWidth(), src.getHeight());
        // 直接绘制源图像，Java 2D 会自动处理 Alpha 合成
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return rgb;
    }

    /**
     * 检查是否支持指定格式
     *
     * @param format 图片格式
     * @return 是否支持
     */
    @Override
    public boolean supportsFormat(ImageFormat format) {
        if (format == null) {
            return false;
        }
        // ORIGINAL 表示保持原格式，始终支持
        if (format == ImageFormat.ORIGINAL) {
            return true;
        }
        
        // 检查是否在支持的格式集合中
        return SUPPORTED_FORMATS.contains(format);
    }

    /**
     * 更新文件名扩展名
     * 将原始文件名的扩展名替换为目标格式的扩展名
     *
     * @param originalFilename 原始文件名
     * @param targetFormat     目标格式
     * @return 更新后的文件名
     */
    @Override
    public String updateFilenameExtension(String originalFilename, ImageFormat targetFormat) {
        // 处理空文件名
        if (originalFilename == null || originalFilename.isBlank()) {
            return "image." + targetFormat.getExtension();
        }
        // ORIGINAL 格式不改变文件名
        if (targetFormat == null || targetFormat == ImageFormat.ORIGINAL) {
            return originalFilename;
        }

        // 提取基础文件名（去掉扩展名）
        int lastDotIndex = originalFilename.lastIndexOf('.');
        String baseName;
        if (lastDotIndex > 0) {
            baseName = originalFilename.substring(0, lastDotIndex);
        } else {
            baseName = originalFilename;
        }

        // 拼接新扩展名
        return baseName + "." + targetFormat.getExtension();
    }

    /**
     * 获取格式对应的 MIME 类型
     *
     * @param format 图片格式
     * @return MIME 类型，如果是 ORIGINAL 则返回 null
     */
    @Override
    public String getMimeType(ImageFormat format) {
        if (format == null || format == ImageFormat.ORIGINAL) {
            return null;
        }
        return format.getMimeType();
    }
}
