package com.timxs.storagetoolkit.service.impl;

import com.timxs.storagetoolkit.config.ImageWatermarkConfig;
import com.timxs.storagetoolkit.config.TextWatermarkConfig;
import com.timxs.storagetoolkit.service.WatermarkService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Set;

/**
 * 水印服务实现
 * 使用 Java 2D Graphics API 实现水印渲染
 * 支持文字水印和图片水印两种类型
 */
@Slf4j
@Service
public class WatermarkServiceImpl implements WatermarkService {

    /** 内嵌字体缓存 */
    private volatile Font embeddedChineseFont;

    /** 内嵌字体加载锁 */
    private final Object fontLoadLock = new Object();

    /**
     * 可直接叠加水印的标准 RGB 系图像类型
     * 这些类型直接在解码位图上绘制水印，避免整幅复制导致内存翻倍
     */
    private static final Set<Integer> DIRECT_DRAW_TYPES = Set.of(
        BufferedImage.TYPE_INT_RGB,
        BufferedImage.TYPE_INT_ARGB,
        BufferedImage.TYPE_INT_ARGB_PRE,
        BufferedImage.TYPE_INT_BGR,
        BufferedImage.TYPE_3BYTE_BGR,
        BufferedImage.TYPE_4BYTE_ABGR,
        BufferedImage.TYPE_4BYTE_ABGR_PRE);

    /**
     * 准备可绘制水印的画布
     * 标准 RGB 系类型直接返回原图（原地绘制）；
     * 索引色、灰度等特殊类型无法正确叠加彩色半透明水印，转换为标准类型后绘制
     *
     * @param image 解码后的原图
     * @return 可绘制的画布（可能是原图本身）
     */
    private BufferedImage toDrawableCanvas(BufferedImage image) {
        if (DIRECT_DRAW_TYPES.contains(image.getType())) {
            return image;
        }
        int type = image.getColorModel().hasAlpha()
            ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        log.debug("图片类型 {} 不支持直接绘制水印，转换为标准类型 {}", image.getType(), type);
        BufferedImage canvas = new BufferedImage(image.getWidth(), image.getHeight(), type);
        Graphics2D g = canvas.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return canvas;
    }

    /**
     * 归一化水印文字
     * 将视觉上等同普通空格的特殊空格（不间断空格等，对应 Unicode NFKC 的标准映射）替换为普通空格，
     * 并移除零宽字符，避免复制粘贴带入的不可见字符导致字体检查误判或渲染出缺字方框。
     * 全角空格等有可见宽度差异的字符保持原样，不改变用户输入的视觉效果
     *
     * @param text 原始水印文字
     * @return 归一化后的文字
     */
    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text
            .replace('\u00A0', ' ')   // 不间断空格
            .replace('\u2007', ' ')   // 数字空格
            .replace('\u202F', ' ')   // 窄不间断空格
            .replaceAll("[\\u200B-\\u200D\\uFEFF\\u2060]", ""); // 零宽字符
    }

    /**
     * 解析水印使用的基础字体（带多级回退）
     * 优先级：用户指定字体 > 内嵌中文字体 > 系统默认字体
     * 返回基础字体对象，调用方按需 deriveFont 调整样式和大小，
     * 避免在自适应缩小字号的循环中反复扫描系统字体
     *
     * @param preferredFontName 用户指定的字体名称（空字符串表示使用默认）
     * @param sampleText 用于测试显示的文本（通常是水印文字）
     * @return 可用的基础字体
     */
    private Font resolveBaseFont(String preferredFontName, String sampleText) {
        // 1. 如果用户指定了字体名称，先尝试使用用户字体
        if (preferredFontName != null && !preferredFontName.isBlank()) {
            Font userFont = tryLoadUserFont(preferredFontName, sampleText);
            if (userFont != null) {
                log.debug("使用用户指定字体: {}", preferredFontName);
                return userFont;
            }
            log.warn("用户指定字体 '{}' 不可用或不支持水印文字，尝试使用内嵌字体", preferredFontName);
        }

        // 2. 尝试使用内嵌中文字体
        Font embeddedFont = getEmbeddedFont();
        if (embeddedFont != null && canDisplayText(embeddedFont, sampleText)) {
            log.debug("使用内嵌中文字体: 文泉驿微米黑");
            return embeddedFont;
        }

        // 3. 回退到系统默认字体
        log.warn("内嵌字体不可用或不支持水印文字，使用系统默认字体（可能无法显示中文）");
        return new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    }

    /**
     * 尝试加载用户指定的字体
     */
    private Font tryLoadUserFont(String fontName, String sampleText) {
        try {
            // 直接使用字体名称创建字体
            Font font = new Font(fontName, Font.PLAIN, 12);
            if (canDisplayText(font, sampleText)) {
                return font;
            }
            // 尝试所有已注册的字体（按字体名/家族名匹配）
            Font[] allFonts = GraphicsEnvironment.getLocalGraphicsEnvironment().getAllFonts();
            for (Font f : allFonts) {
                if (f.getFontName().equalsIgnoreCase(fontName)
                    || f.getName().equalsIgnoreCase(fontName)
                    || f.getFamily().equalsIgnoreCase(fontName)) {
                    if (canDisplayText(f, sampleText)) {
                        return f;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("加载用户字体失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 获取内嵌中文字体（带缓存）
     */
    private Font getEmbeddedFont() {
        if (embeddedChineseFont == null) {
            synchronized (fontLoadLock) {
                if (embeddedChineseFont == null) {
                    embeddedChineseFont = loadEmbeddedFontFromResource();
                }
            }
        }
        return embeddedChineseFont;
    }

    /**
     * 从资源文件加载内嵌字体
     */
    private Font loadEmbeddedFontFromResource() {
        try (InputStream is = getClass().getResourceAsStream("/fonts/wqy-microhei.ttf")) {
            if (is == null) {
                log.warn("内嵌字体文件未找到: /fonts/wqy-microhei.ttf");
                return null;
            }
            Font baseFont = Font.createFont(Font.TRUETYPE_FONT, is);
            // 注册到图形环境，使其可用于后续创建
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            ge.registerFont(baseFont);
            log.debug("成功加载内嵌中文字体: 文泉驿微米黑");
            return baseFont;
        } catch (Exception e) {
            log.warn("加载内嵌字体失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 检查字体是否能显示指定文本
     * 按码点遍历（char 级检查对超出基本平面的字符恒为 false，会误杀 emoji 等）；
     * 空白字符不需要字形（排版引擎对缺失的空白字形会合成留白），跳过检查，
     * 避免一个特殊空格导致整个字体被误判为不可用、整句回退到无中文的默认字体
     */
    private boolean canDisplayText(Font font, String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        return text.codePoints()
            .filter(cp -> !Character.isWhitespace(cp) && !Character.isSpaceChar(cp))
            .allMatch(font::canDisplay);
    }

    /**
     * 添加文字水印
     * 直接在解码位图上绘制（标准类型），支持自适应字体大小，当图片太小时会自动缩小字体
     *
     * @param image  原始图片
     * @param config 文字水印配置
     * @return 添加水印后的图片（标准类型时与入参为同一对象）
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
        String text = normalizeText(config.text());
        if (text.isBlank()) {
            return image;
        }

        log.debug("开始添加文字水印，原图尺寸: {}x{}, 类型: {}",
            image.getWidth(), image.getHeight(), image.getType());

        BufferedImage canvas = toDrawableCanvas(image);
        Graphics2D g2d = canvas.createGraphics();
        try {
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
            // 基础字体只解析一次，缩小字号时仅派生新字号
            Font baseFont = resolveBaseFont(config.fontName(), text);
            Font font = baseFont.deriveFont(Font.BOLD, (float) fontSize);
            g2d.setFont(font);
            FontMetrics metrics = g2d.getFontMetrics();
            int textWidth = metrics.stringWidth(text);
            int textHeight = metrics.getHeight();

            // 如果水印太大，自动缩小字体（最小 12px）
            int minFontSize = 12;
            while ((textWidth > maxTextWidth || textHeight > maxTextHeight) && fontSize > minFontSize) {
                fontSize -= 2;
                font = baseFont.deriveFont(Font.BOLD, (float) fontSize);
                g2d.setFont(font);
                metrics = g2d.getFontMetrics();
                textWidth = metrics.stringWidth(text);
                textHeight = metrics.getHeight();
            }

            // 如果字体已经最小但水印仍然太大，记录警告并返回原图（此时尚未绘制，画布未被修改）
            if ((textWidth > maxTextWidth || textHeight > maxTextHeight) && fontSize <= minFontSize) {
                log.warn("图片太小，跳过水印: 图片尺寸 {}x{}, 水印尺寸 {}x{}",
                    image.getWidth(), image.getHeight(), textWidth, textHeight);
                return image; // 返回原图，不添加水印
            }

            log.debug("最终字体大小: {}, 模式: {}", fontSize, config.fontSizeMode());

            // 设置颜色和透明度
            Color color = parseColor(config.color(), config.opacity());
            g2d.setColor(color);

            log.debug("水印文字尺寸: {}x{}, 字体大小: {}", textWidth, textHeight, fontSize);

            // 计算位置（使用枚举方法 + 边界检查）
            int x = Math.max(0, config.position().calculateX(image.getWidth(), textWidth, marginX));
            int y = Math.max(0, config.position().calculateY(image.getHeight(), textHeight, marginY));
            // 文字 Y 坐标需要加上 ascent（基线到顶部的距离）
            y += metrics.getAscent();

            log.debug("水印位置: ({}, {}), 边距: ({}, {})", x, y, marginX, marginY);

            // 绘制文字
            g2d.drawString(text, x, y);

            log.debug("文字水印绘制完成，结果图片类型: {}", canvas.getType());
        } finally {
            g2d.dispose();
        }

        return canvas;
    }

    /**
     * 添加图片水印
     * 直接在解码位图上绘制（标准类型），支持缩放和透明度设置
     *
     * @param image          原始图片
     * @param config         图片水印配置
     * @param watermarkImage 水印图片
     * @return 添加水印后的图片（标准类型时与入参为同一对象）
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

        BufferedImage canvas = toDrawableCanvas(image);
        Graphics2D g2d = canvas.createGraphics();
        try {
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

        return canvas;
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
