package com.timxs.storagetoolkit.config;

import com.timxs.storagetoolkit.model.WatermarkPosition;
import com.timxs.storagetoolkit.model.WatermarkType;
import lombok.Data;

/**
 * 水印配置
 * 包含文字水印和图片水印的所有配置项
 */
@Data
public class WatermarkConfig {
    
    /**
     * 是否启用水印
     */
    private boolean enabled = false;
    
    /**
     * 水印类型（TEXT 或 IMAGE）
     */
    private WatermarkType type = WatermarkType.TEXT;
    
    // ========== 文字水印配置 ==========
    
    /**
     * 水印文字
     */
    private String text = "";
    
    /**
     * 字体名称
     */
    private String fontName = "SansSerif";
    
    /**
     * 字体大小（像素）
     */
    private int fontSize = 25;
    
    /**
     * 颜色（十六进制，如 #FFFFFF）
     */
    private String color = "#b4b4b4";
    
    // ========== 图片水印配置 ==========
    
    /**
     * 水印图片URL
     */
    private String imageUrl = "";
    
    /**
     * 水印图片缩放比例（0.0-1.0）
     * 由 SettingsManager 从百分比转换
     */
    private double imageScale = 0.2;
    
    // ========== 通用配置 ==========
    
    /**
     * 水印位置（九宫格）
     */
    private WatermarkPosition position = WatermarkPosition.BOTTOM_RIGHT;
    
    /**
     * 透明度（0-100，0 为完全透明，100 为完全不透明）
     */
    private int opacity = 50;
    
    /**
     * X 方向边距（百分比 0-50）
     */
    private double marginX = 5;
    
    /**
     * Y 方向边距（百分比 0-50）
     */
    private double marginY = 5;
}
