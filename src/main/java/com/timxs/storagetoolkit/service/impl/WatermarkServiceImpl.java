package com.timxs.storagetoolkit.service.impl;

import com.timxs.storagetoolkit.config.ImageWatermarkConfig;
import com.timxs.storagetoolkit.config.TextWatermarkConfig;
import com.timxs.storagetoolkit.model.WatermarkPosition;
import com.timxs.storagetoolkit.service.WatermarkService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * 水印服务实现
 * 使用 Java 2D Graphics API 实现水印渲染
 * 支持文字水印和图片水印两种类型
 */
@Slf4j
@Service
public class WatermarkServiceImpl implements WatermarkService {

    /**
     * 添加文字水印
     * 支持自适应字体大小，当图片太小时会自动缩小字体
     *
     * @param image  原始图片
     * @param config 文字水印配置
     * @return 添加水印后的图片
     * @throws IllegalArgumentException 图片为空时抛出
     */
    @Override
    public BufferedImage addTextWatermark(BufferedImage image, TextWatermarkConfig config) {
        if (image == null) {
            throw new IllegalArgumentException("Image cannot be null");
        }
        // 配置为空或文字为空时，直接返回原图
        if (config == null || config.text() == null || config.text().isBlank()) {
            return image;
        }

        log.debug("开始添加文字水印，原图尺寸: {}x{}, 类型: {}", 
            image.getWidth(), image.getHeight(), image.getType());

        // 创建带 alpha 通道的新图片，用于支持透明度
        BufferedImage result = new BufferedImage(
            image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        
        Graphics2D g2d = result.createGraphics();
        try {
            // 绘制原图
            g2d.drawImage(image, 0, 0, null);
            
            // 设置抗锯齿，提高文字渲染质量
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            
            // 使用百分比计算实际边距
            int marginX = config.calculateMarginX(image.getWidth());
            int marginY = config.calculateMarginY(image.getHeight());
            
            // 计算可用空间（水印最多占用的宽度/高度），防止边距过大导致负数
            int maxTextWidth = Math.max(1, (int) (image.getWidth() * 0.8) - marginX * 2);
            int maxTextHeight = Math.max(1, (int) (image.getHeight() * 0.8) - marginY * 2);
            
            // 使用 calculateFontSize 计算字体大小（支持 FIXED 和 ADAPTIVE 模式）
            int fontSize = config.calculateFontSize(image.getWidth(), image.getHeight());
            Font font = new Font(config.fontName(), Font.BOLD, fontSize);
            g2d.setFont(font);
            FontMetrics metrics = g2d.getFontMetrics();
            int textWidth = metrics.stringWidth(config.text());
            int textHeight = metrics.getHeight();
            
            // 如果水印太大，自动缩小字体（最小 12px）
            int minFontSize = 12;
            while ((textWidth > maxTextWidth || textHeight > maxTextHeight) && fontSize > minFontSize) {
                fontSize -= 2;
                font = new Font(config.fontName(), Font.BOLD, fontSize);
                g2d.setFont(font);
                metrics = g2d.getFontMetrics();
                textWidth = metrics.stringWidth(config.text());
                textHeight = metrics.getHeight();
            }
            
            // 如果字体已经最小但水印仍然太大，记录警告并返回原图
            if ((textWidth > maxTextWidth || textHeight > maxTextHeight) && fontSize <= minFontSize) {
                log.warn("图片太小，跳过水印: 图片尺寸 {}x{}, 水印尺寸 {}x{}",
                    image.getWidth(), image.getHeight(), textWidth, textHeight);
                g2d.dispose();
                return image; // 返回原图，不添加水印
            }
            
            log.debug("最终字体大小: {}, 模式: {}", fontSize, config.fontSizeMode());
            
            // 设置颜色和透明度
            Color color = parseColor(config.color(), config.opacity());
            g2d.setColor(color);
            
            log.debug("水印颜色: R={}, G={}, B={}, A={}", 
                color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha());
            log.debug("水印文字尺寸: {}x{}, 字体大小: {}", textWidth, textHeight, fontSize);
            
            // 计算位置（使用枚举方法 + 边界检查）
            int x = Math.max(0, config.position().calculateX(image.getWidth(), textWidth, marginX));
            int y = Math.max(0, config.position().calculateY(image.getHeight(), textHeight, marginY));
            // 文字 Y 坐标需要加上 ascent（基线到顶部的距离）
            y += metrics.getAscent();
            
            log.debug("水印位置: ({}, {}), 边距: ({}, {})", x, y, marginX, marginY);
            
            // 绘制文字
            g2d.drawString(config.text(), x, y);
            
            log.debug("文字水印绘制完成，结果图片类型: {}", result.getType());
        } finally {
            g2d.dispose();
        }
        
        return result;
    }

    /**
     * 添加图片水印
     * 支持缩放和透明度设置
     *
     * @param image          原始图片
     * @param config         图片水印配置
     * @param watermarkImage 水印图片
     * @return 添加水印后的图片
     * @throws IllegalArgumentException 原始图片为空时抛出
     */
    @Override
    public BufferedImage addImageWatermark(BufferedImage image, ImageWatermarkConfig config, 
                                            BufferedImage watermarkImage) {
        if (image == null) {
            throw new IllegalArgumentException("Image cannot be null");
        }
        // 水印图片为空时，直接返回原图
        if (watermarkImage == null) {
            log.warn("Watermark image is null, returning original image");
            return image;
        }

        // 按原图宽度的百分比计算水印尺寸，保持水印宽高比
        int targetWidth = (int) (image.getWidth() * config.scale());
        double aspectRatio = (double) watermarkImage.getHeight() / watermarkImage.getWidth();
        int scaledWidth = targetWidth;
        int scaledHeight = (int) (targetWidth * aspectRatio);
        
        // 缩放后尺寸无效时，直接返回原图
        if (scaledWidth <= 0 || scaledHeight <= 0) {
            log.warn("Scaled watermark size is invalid, returning original image");
            return image;
        }

        // 创建带 alpha 通道的新图片
        BufferedImage result = new BufferedImage(
            image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        
        Graphics2D g2d = result.createGraphics();
        try {
            // 绘制原图
            g2d.drawImage(image, 0, 0, null);
            
            // 设置抗锯齿和插值算法，提高缩放质量
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            
            // 设置透明度（0-100 转换为 0.0-1.0）
            float alpha = config.opacity() / 100.0f;
            g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            
            // 使用百分比计算实际边距
            int marginX = config.calculateMarginX(image.getWidth());
            int marginY = config.calculateMarginY(image.getHeight());
            
            // 计算位置（使用枚举方法 + 边界检查）
            int x = Math.max(0, config.position().calculateX(image.getWidth(), scaledWidth, marginX));
            int y = Math.max(0, config.position().calculateY(image.getHeight(), scaledHeight, marginY));
            
            // 绘制缩放后的水印图片
            g2d.drawImage(watermarkImage, x, y, scaledWidth, scaledHeight, null);
            
            log.debug("Added image watermark at position ({}, {}) with size {}x{}", 
                x, y, scaledWidth, scaledHeight);
        } finally {
            g2d.dispose();
        }
        
        return result;
    }

    /**
     * 解析颜色字符串
     * 支持十六进制格式（如 #FFFFFF 或 FFFFFF）
     *
     * @param colorStr 颜色字符串
     * @param opacity  透明度（0-100）
     * @return Color 对象
     */
    private Color parseColor(String colorStr, int opacity) {
        // 默认白色
        if (colorStr == null || colorStr.isBlank()) {
            return new Color(255, 255, 255, (int) (opacity * 2.55));
        }
        
        try {
            // 去掉 # 前缀
            String hex = colorStr.startsWith("#") ? colorStr.substring(1) : colorStr;
            int rgb = Integer.parseInt(hex, 16);
            // 提取 RGB 分量
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            // 透明度从 0-100 转换为 0-255
            int a = (int) (opacity * 2.55);
            return new Color(r, g, b, a);
        } catch (NumberFormatException e) {
            log.warn("Invalid color format: {}, using white", colorStr);
            return new Color(255, 255, 255, (int) (opacity * 2.55));
        }
    }
}
