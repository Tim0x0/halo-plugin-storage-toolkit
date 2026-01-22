package com.timxs.storagetoolkit.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.timxs.storagetoolkit.config.ImageWatermarkConfig;
import com.timxs.storagetoolkit.config.ProcessingConfig;
import com.timxs.storagetoolkit.config.TextWatermarkConfig;
import com.timxs.storagetoolkit.config.WatermarkConfig;
import com.timxs.storagetoolkit.model.ProcessingResult;
import com.timxs.storagetoolkit.model.WatermarkType;
import com.timxs.storagetoolkit.service.FormatConverter;
import com.timxs.storagetoolkit.service.ImageProcessor;
import com.timxs.storagetoolkit.service.WatermarkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.infra.ExternalLinkProcessor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * 图片处理器实现
 * 支持水印添加和格式转换功能
 * 处理顺序：水印 -> 格式转换
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageProcessorImpl implements ImageProcessor {

    /**
     * 水印服务，用于添加文字/图片水印
     */
    private final WatermarkService watermarkService;
    
    /**
     * 格式转换器，用于转换图片格式
     */
    private final FormatConverter formatConverter;
    
    /**
     * 外部链接处理器，用于将相对路径转为完整 URL
     */
    private final ExternalLinkProcessor externalLinkProcessor;

    /**
     * 水印图片缓存
     * Key: 图片 URL，Value: BufferedImage
     * 30 分钟过期，最多缓存 5 个水印图片
     */
    private static final Cache<String, BufferedImage> watermarkImageCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(30))
        .maximumSize(5)
        .build();

    /**
     * 处理图片
     * 在独立线程池中执行，避免阻塞主线程
     *
     * @param imageData        原始图片数据
     * @param originalFilename 原始文件名
     * @param contentType      原始 MIME 类型
     * @param config           处理配置
     * @return 处理结果（异步）
     */
    @Override
    public Mono<ProcessingResult> process(byte[] imageData, String originalFilename,
                                          String contentType, ProcessingConfig config) {
        return Mono.fromCallable(() -> {
                try {
                    return doProcess(imageData, originalFilename, contentType, config);
                } catch (Throwable t) {
                    // 捕获所有异常包括 Error（如 NoClassDefFoundError），确保不会阻塞上传流程
                    log.error("图片处理发生严重错误: {}", t.getMessage(), t);
                    return ProcessingResult.failed(imageData, originalFilename, contentType, 
                        "处理错误: " + t.getClass().getSimpleName() + " - " + t.getMessage());
                }
            })
            .subscribeOn(Schedulers.boundedElastic())  // 在弹性线程池中执行
            .onErrorResume(e -> {
                log.error("图片处理失败: {}", e.getMessage(), e);
                return Mono.just(ProcessingResult.failed(imageData, originalFilename, contentType, e.getMessage()));
            });
    }

    /**
     * 检查文件是否应该被处理
     *
     * @param contentType 文件 MIME 类型
     * @param fileSize    文件大小
     * @param config      处理配置
     * @return 是否应该处理
     */
    @Override
    public boolean shouldProcess(String contentType, long fileSize, ProcessingConfig config) {
        return getSkipReason(contentType, fileSize, config) == null;
    }

    /**
     * 获取跳过处理的原因
     * 注意：格式、是否启用、是否有处理功能等检查已在 WebFilter 中提前完成
     * 此方法仅检查文件大小相关条件
     *
     * @param contentType 文件 MIME 类型
     * @param fileSize    文件大小
     * @param config      处理配置
     * @return 跳过原因，如果不需要跳过则返回 null
     */
    @Override
    public String getSkipReason(String contentType, long fileSize, ProcessingConfig config) {
        // 检查最小文件大小
        long minFileSize = config.getMinFileSize();
        if (minFileSize > 0 && fileSize < minFileSize) {
            return "文件大小 " + formatFileSize(fileSize) + " 小于最小限制 " + formatFileSize(minFileSize);
        }

        // 检查最大文件大小（兜底，Content-Length 可能为 -1）
        long maxFileSize = config.getMaxFileSize();
        if (maxFileSize > 0 && fileSize > maxFileSize) {
            return "文件大小 " + formatFileSize(fileSize) + " 大于最大限制 " + formatFileSize(maxFileSize);
        }

        return null; // 不需要跳过
    }

    /**
     * 格式化文件大小显示
     * 将字节数转换为人类可读的格式
     *
     * @param bytes 字节数
     * @return 格式化后的字符串（如 1.5 MB）
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /**
     * 从 MIME 类型提取格式
     * 例如：image/jpeg -> jpeg
     *
     * @param contentType MIME 类型
     * @return 格式名称
     */
    private String extractFormat(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "";
        }
        // 处理 image/jpeg; charset=xxx 这种情况
        String mimeType = contentType.split(";")[0].trim();
        if (mimeType.startsWith("image/")) {
            return mimeType.substring(6);
        }
        return mimeType;
    }

    /**
     * 检查文件格式是否在允许处理的列表中
     * 用于提前判断，避免不需要处理的文件读入内存
     *
     * @param contentType 文件 MIME 类型
     * @param config      处理配置
     * @return 是否是允许处理的格式
     */
    @Override
    public boolean isAllowedFormat(String contentType, ProcessingConfig config) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }

        List<String> allowedFormats = config.getAllowedFormats();
        if (allowedFormats == null || allowedFormats.isEmpty()) {
            return false;
        }

        String format = extractFormat(contentType);
        return allowedFormats.stream()
            .anyMatch(allowed -> {
                String normalizedAllowed = allowed.trim().toLowerCase();
                String normalizedFormat = format.toLowerCase();
                return normalizedAllowed.equals(normalizedFormat)
                    || normalizedAllowed.equals("image/" + normalizedFormat)
                    || ("image/" + normalizedAllowed).equals(contentType.toLowerCase());
            });
    }

    @Override
    public boolean hasProcessingEnabled(ProcessingConfig config) {
        return config.getWatermark().isEnabled()
            || config.getFormatConversion().isEnabled();
    }

    /**
     * 执行图片处理（同步方法）
     * 处理顺序：水印 -> 格式转换
     *
     * @param imageData        原始图片数据
     * @param originalFilename 原始文件名
     * @param contentType      原始 MIME 类型
     * @param config           处理配置
     * @return 处理结果
     */
    private ProcessingResult doProcess(byte[] imageData, String originalFilename,
                                       String contentType, ProcessingConfig config) {
        // 保存当前线程的类加载器
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            // 设置插件类加载器为上下文类加载器，确保 ImageIO 能找到 WebP 等格式的 SPI
            Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader());
            
            // 读取图片
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
            if (image == null) {
                return ProcessingResult.failed(imageData, originalFilename, contentType, "无法读取图片数据");
            }

            String currentFilename = originalFilename;
            String currentContentType = contentType;
            boolean processed = false;
            StringBuilder errorMessages = new StringBuilder();
            
            // 新增：智能跳过相关变量
            boolean formatConversionSkipped = false;
            String skipReason = null;
            boolean watermarkApplied = false;

            // 步骤1：添加水印
            WatermarkConfig watermarkConfig = config.getWatermark();
            if (watermarkConfig.isEnabled()) {
                try {
                    image = applyWatermark(image, watermarkConfig);
                    processed = true;
                    watermarkApplied = true;
                    log.debug("水印添加成功: {}", originalFilename);
                } catch (Exception e) {
                    log.warn("水印添加失败: {}", e.getMessage());
                    errorMessages.append("水印失败: ").append(e.getMessage()).append("; ");
                }
            }

            // 步骤2：格式转换
            byte[] resultData;
            if (config.getFormatConversion().isEnabled()) {
                try {
                    var formatConfig = config.getFormatConversion();
                    // 根据目标格式选择对应的 effort 值
                    int effort = formatConfig.getTargetFormat() == com.timxs.storagetoolkit.model.ImageFormat.AVIF 
                        ? formatConfig.getAvifEffort() 
                        : formatConfig.getWebpEffort();
                    byte[] convertedData = formatConverter.convert(image, formatConfig.getTargetFormat(), 
                        formatConfig.getOutputQuality(), effort);
                    
                    // 计算体积增加比例
                    double increaseRatio = (double)(convertedData.length - imageData.length) / imageData.length * 100;
                    int threshold = formatConfig.getSkipThreshold();
                    
                    // 智能跳过逻辑：比较转换后体积与原始上传体积，考虑容错比例
                    if (formatConfig.isSkipIfLarger() && increaseRatio > threshold) {
                        // 转换后体积增加超过阈值，保留原格式
                        log.debug("智能跳过格式转换: {} 体积 ({}) > 原始体积 ({})，增加 {}% 超过阈值 {}%", 
                            formatConfig.getTargetFormat(),
                            formatFileSize(convertedData.length), 
                            formatFileSize(imageData.length),
                            String.format("%.1f", increaseRatio),
                            threshold);
                        
                        // 如果有水印，需要重新编码为原格式；否则直接使用原始数据避免二次压缩损失
                        if (watermarkApplied) {
                            resultData = imageToBytes(image, contentType);
                        } else {
                            resultData = imageData;
                        }
                        // 保持原文件名和 contentType（不修改 currentFilename 和 currentContentType）
                        
                        // 标记格式转换被跳过
                        formatConversionSkipped = true;
                        skipReason = String.format("格式转换跳过: %s 体积 (%s) > 原始体积 (%s)，增加 %.1f%% 超过阈值 %d%%",
                            formatConfig.getTargetFormat(),
                            formatFileSize(convertedData.length),
                            formatFileSize(imageData.length),
                            increaseRatio,
                            threshold);
                    } else {
                        // 使用转换后的数据
                        resultData = convertedData;
                        currentFilename = formatConverter.updateFilenameExtension(currentFilename, 
                            formatConfig.getTargetFormat());
                        currentContentType = formatConverter.getMimeType(formatConfig.getTargetFormat());
                        processed = true;
                        
                        // 记录压缩效果
                        if (convertedData.length <= imageData.length) {
                            if (convertedData.length < imageData.length) {
                                double reduction = (1.0 - (double)convertedData.length / imageData.length) * 100;
                                log.debug("格式转换成功: {} -> {}, 体积减少 {}%", 
                                    originalFilename, currentFilename, String.format("%.1f", reduction));
                            } else {
                                // 体积相等
                                log.debug("格式转换成功: {} -> {}, 体积不变", 
                                    originalFilename, currentFilename);
                            }
                        } else if (increaseRatio > 0) {
                            // 体积增加但在阈值内，记录 DEBUG 日志
                            log.debug("格式转换成功: {} -> {}, 体积增加 {}% (在阈值 {}% 内)", 
                                originalFilename, currentFilename, 
                                String.format("%.1f", increaseRatio), threshold);
                        }
                        
                        // 强制转换模式下体积增加的警告
                        if (!formatConfig.isSkipIfLarger() && increaseRatio > 0) {
                            log.warn("格式转换完成，但体积增加: {} → {} (+{}%)",
                                formatFileSize(imageData.length),
                                formatFileSize(convertedData.length),
                                String.format("%.1f", increaseRatio));
                        }
                    }
                } catch (Exception e) {
                    log.warn("格式转换失败: {}", e.getMessage());
                    errorMessages.append("格式转换失败: ").append(e.getMessage()).append("; ");
                    // 格式转换失败时，输出原格式
                    resultData = imageToBytes(image, contentType);
                }
            } else {
                // 没有格式转换，输出原格式
                resultData = imageToBytes(image, contentType);
            }

            // 返回结果
            // 有错误但没有任何成功的处理 → FAILED
            if (!processed && !formatConversionSkipped && errorMessages.length() > 0) {
                return ProcessingResult.failed(imageData, originalFilename, contentType, 
                    errorMessages.toString());
            }
            
            if (!processed && !formatConversionSkipped) {
                return ProcessingResult.skipped(imageData, originalFilename, contentType, "没有执行任何处理");
            }
            
            // 智能跳过 + 无水印处理 + 有错误 → FAILED（水印失败+转换跳过的情况）
            if (formatConversionSkipped && !watermarkApplied && errorMessages.length() > 0) {
                return ProcessingResult.failed(imageData, originalFilename, contentType, 
                    errorMessages.toString());
            }
            
            // 智能跳过 + 无其他处理 → SKIPPED，直接返回原始数据（避免重新编码）
            if (formatConversionSkipped && !watermarkApplied) {
                return ProcessingResult.skipped(imageData, originalFilename, contentType, skipReason);
            }
            
            // 智能跳过 + 有水印 → PARTIAL，返回水印后的原格式数据
            if (formatConversionSkipped && watermarkApplied) {
                return ProcessingResult.partial(resultData, currentFilename, currentContentType, skipReason);
            }

            // 有错误信息则返回 PARTIAL 状态
            if (errorMessages.length() > 0) {
                return ProcessingResult.partial(resultData, currentFilename, currentContentType, 
                    errorMessages.toString());
            }
            return ProcessingResult.success(resultData, currentFilename, currentContentType);

        } catch (IOException e) {
            log.error("图片处理IO错误: {}", e.getMessage(), e);
            return ProcessingResult.failed(imageData, originalFilename, contentType, "IO错误: " + e.getMessage());
        } finally {
            // 恢复原来的类加载器
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    /**
     * 应用水印
     * 根据配置类型选择文字水印或图片水印
     *
     * @param image  原始图片
     * @param config 水印配置
     * @return 添加水印后的图片
     * @throws IllegalStateException 配置无效时抛出
     */
    private BufferedImage applyWatermark(BufferedImage image, WatermarkConfig config) {
        log.debug("应用水印 - 类型: {}, 文字: '{}', 图片URL: '{}'", 
            config.getType(), config.getText(), config.getImageUrl());
        
        if (config.getType() == WatermarkType.TEXT) {
            // 文字水印
            if (config.getText() == null || config.getText().isBlank()) {
                throw new IllegalStateException("文字水印已启用但未设置水印文字");
            }
            TextWatermarkConfig textConfig = TextWatermarkConfig.from(config);
            log.debug("文字水印配置 - 文字: '{}', 字体大小: {}, 颜色: {}, 位置: {}, 透明度: {}",
                textConfig.text(), textConfig.fontSize(), textConfig.color(), 
                textConfig.position(), textConfig.opacity());
            return watermarkService.addTextWatermark(image, textConfig);
        } else {
            // 图片水印
            if (config.getImageUrl() == null || config.getImageUrl().isBlank()) {
                throw new IllegalStateException("图片水印已启用但未设置水印图片URL");
            }
            
            ImageWatermarkConfig imageConfig = ImageWatermarkConfig.from(config);
            log.debug("图片水印配置 - URL: '{}', 缩放: {}, 位置: {}, 透明度: {}",
                imageConfig.imageUrl(), imageConfig.scale(), 
                imageConfig.position(), imageConfig.opacity());
            BufferedImage watermarkImage = loadWatermarkImageFromUrl(config.getImageUrl());
            if (watermarkImage == null) {
                throw new IllegalStateException("无法加载水印图片: " + config.getImageUrl());
            }
            log.debug("水印图片加载成功，尺寸: {}x{}", watermarkImage.getWidth(), watermarkImage.getHeight());
            return watermarkService.addImageWatermark(image, imageConfig, watermarkImage);
        }
    }

    /**
     * 从URL加载水印图片（带缓存）
     * 支持相对路径（Halo 附件）和完整 HTTP URL
     * 注意：不支持 WebP 格式的水印图片，请使用 PNG 或 JPEG
     *
     * @param imageUrl 图片URL
     * @return 水印图片，加载失败返回 null
     */
    private BufferedImage loadWatermarkImageFromUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            log.warn("水印图片 URL 为空");
            return null;
        }

        // 使用 ExternalLinkProcessor 处理链接，自动将相对路径转为完整 URL
        String fullUrl = externalLinkProcessor.processLink(imageUrl);

        // 尝试从缓存获取
        BufferedImage cached = watermarkImageCache.getIfPresent(fullUrl);
        if (cached != null) {
            log.debug("水印图片命中缓存: {}", fullUrl);
            return cached;
        }

        log.debug("水印图片未命中缓存，开始下载: {} -> {}", imageUrl, fullUrl);
        BufferedImage image = doLoadWatermarkImage(fullUrl);

        // 加载成功则放入缓存
        if (image != null) {
            watermarkImageCache.put(fullUrl, image);
            log.debug("水印图片已缓存: {}", fullUrl);
        }

        return image;
    }

    /**
     * 实际从 URL 下载水印图片
     *
     * @param fullUrl 完整 URL
     * @return 水印图片，加载失败返回 null
     */
    private BufferedImage doLoadWatermarkImage(String fullUrl) {
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL url = java.net.URI.create(fullUrl).toURL();
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);  // 连接超时 15 秒
            conn.setReadTimeout(20000);     // 读取超时 20 秒
            conn.setRequestMethod("GET");

            try (java.io.InputStream is = conn.getInputStream()) {
                BufferedImage img = ImageIO.read(is);
                if (img != null) {
                    log.debug("水印图片加载成功: {}x{}, 类型: {}", img.getWidth(), img.getHeight(), img.getType());
                } else {
                    log.error("水印图片加载失败，ImageIO 返回 null。URL: {}", fullUrl);
                }
                return img;
            }
        } catch (java.net.SocketTimeoutException e) {
            log.error("加载水印图片超时: {} - {}", fullUrl, e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("从URL加载水印图片失败: {} - {}", fullUrl, e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 将 BufferedImage 转换为字节数组
     * 注意：JPEG 不支持 Alpha 通道，需要先转换为 RGB
     *
     * @param image       图片对象
     * @param contentType MIME 类型
     * @return 字节数组
     * @throws IOException 写入失败时抛出
     */
    private byte[] imageToBytes(BufferedImage image, String contentType) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        String formatName = getFormatName(contentType);
        
        // JPEG 不支持 Alpha 通道，需要转换为 RGB
        BufferedImage imageToWrite = image;
        if (("jpg".equals(formatName) || "jpeg".equals(formatName)) && 
            (image.getType() == BufferedImage.TYPE_INT_ARGB || 
             image.getType() == BufferedImage.TYPE_INT_ARGB_PRE ||
             image.getType() == BufferedImage.TYPE_4BYTE_ABGR ||
             image.getType() == BufferedImage.TYPE_4BYTE_ABGR_PRE)) {
            log.debug("JPEG 格式不支持 Alpha 通道，转换为 RGB");
            imageToWrite = convertToRGB(image);
        }
        
        boolean success = ImageIO.write(imageToWrite, formatName, outputStream);
        if (!success) {
            throw new IOException("无法写入图片格式: " + formatName);
        }
        return outputStream.toByteArray();
    }
    
    /**
     * 将带 Alpha 通道的图片转换为 RGB
     * 透明区域填充为白色
     *
     * @param src 源图片
     * @return RGB 格式的图片
     */
    private BufferedImage convertToRGB(BufferedImage src) {
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = rgb.createGraphics();
        // 白色背景填充透明区域
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, src.getWidth(), src.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return rgb;
    }

    /**
     * 从 MIME 类型获取格式名称
     * 用于 ImageIO.write() 方法
     *
     * @param contentType MIME 类型
     * @return 格式名称
     */
    private String getFormatName(String contentType) {
        if (contentType == null) {
            return "png";
        }
        return switch (contentType.toLowerCase()) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/bmp" -> "bmp";
            default -> "png";
        };
    }
}
