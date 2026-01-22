package com.timxs.storagetoolkit.service;

import com.timxs.storagetoolkit.config.ProcessingConfig;
import com.timxs.storagetoolkit.model.ProcessingResult;
import reactor.core.publisher.Mono;

/**
 * 图片处理器接口
 * 定义图片处理的核心方法，包括水印添加和格式转换
 */
public interface ImageProcessor {

    /**
     * 处理图片
     * 根据配置执行水印添加和格式转换
     *
     * @param imageData        原始图片数据
     * @param originalFilename 原始文件名
     * @param contentType      原始 MIME 类型
     * @param config           处理配置
     * @return 处理结果（异步）
     */
    Mono<ProcessingResult> process(byte[] imageData, String originalFilename, 
                                    String contentType, ProcessingConfig config);

    /**
     * 检查文件是否应该被处理
     *
     * @param contentType 文件 MIME 类型
     * @param fileSize    文件大小（字节）
     * @param config      处理配置
     * @return 是否应该处理
     */
    boolean shouldProcess(String contentType, long fileSize, ProcessingConfig config);

    /**
     * 获取跳过处理的原因
     *
     * @param contentType 文件 MIME 类型
     * @param fileSize    文件大小（字节）
     * @param config      处理配置
     * @return 跳过原因，如果不需要跳过则返回 null
     */
    String getSkipReason(String contentType, long fileSize, ProcessingConfig config);

    /**
     * 检查文件格式是否在允许处理的列表中
     * 用于提前判断，避免不需要处理的文件读入内存
     *
     * @param contentType 文件 MIME 类型
     * @param config      处理配置
     * @return 是否是允许处理的格式
     */
    boolean isAllowedFormat(String contentType, ProcessingConfig config);

    /**
     * 检查是否有任何处理功能启用（水印或格式转换）
     *
     * @param config 处理配置
     * @return 是否有处理功能启用
     */
    boolean hasProcessingEnabled(ProcessingConfig config);
}
