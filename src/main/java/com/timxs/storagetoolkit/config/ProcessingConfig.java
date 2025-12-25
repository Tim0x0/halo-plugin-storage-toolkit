package com.timxs.storagetoolkit.config;

import lombok.Data;
import java.util.List;

/**
 * 图片处理配置
 * 包含全局设置、文件过滤、水印、格式转换和日志设置
 */
@Data
public class ProcessingConfig {
    
    // ========== 全局设置 ==========
    
    /**
     * 是否启用图片处理
     */
    private boolean enabled = false;
    
    /**
     * 目标存储策略（为空则处理所有策略）
     */
    private String targetPolicy = "";
    
    /**
     * 是否处理编辑器中上传的图片（UC API）
     * 默认关闭
     */
    private boolean processEditorImages = false;
    
    /**
     * 目标分组列表（为空则处理所有分组）
     */
    private List<String> targetGroups = List.of();
    
    // ========== 文件过滤 ==========
    
    /**
     * 允许的格式（如 jpeg, png, gif, webp）
     */
    private List<String> allowedFormats = List.of("jpeg", "png", "gif", "webp");
    
    /**
     * 最小文件大小（字节），0 表示不限制
     */
    private long minFileSize = 0;
    
    /**
     * 最大文件大小（字节），0 表示不限制
     */
    private long maxFileSize = 0;
    
    // ========== 水印设置 ==========
    
    /**
     * 水印配置
     */
    private WatermarkConfig watermark = new WatermarkConfig();
    
    // ========== 格式转换设置 ==========
    
    /**
     * 格式转换配置
     */
    private FormatConversionConfig formatConversion = new FormatConversionConfig();
    
    // ========== 日志设置 ==========
    
    /**
     * 日志保留天数
     */
    private int logRetentionDays = 30;
}
