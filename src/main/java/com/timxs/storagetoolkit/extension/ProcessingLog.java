package com.timxs.storagetoolkit.extension;

import com.timxs.storagetoolkit.model.ProcessingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;

/**
 * 处理日志 Extension 实体
 * 用于记录图片处理的详细信息，包括原始文件、处理结果、状态等
 * 继承自 Halo 的 AbstractExtension，可通过 ReactiveExtensionClient 进行 CRUD 操作
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "storage-toolkit.timxs.com",
     version = "v1alpha1",
     kind = "ProcessingLog",
     plural = "processinglogs",
     singular = "processinglog")
public class ProcessingLog extends AbstractExtension {

    /**
     * 日志规格，包含所有业务字段
     */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private ProcessingLogSpec spec;

    /**
     * 处理日志规格
     * 包含处理的详细信息
     */
    @Data
    public static class ProcessingLogSpec {
        /**
         * 原始文件名
         */
        private String originalFilename;
        
        /**
         * 结果文件名
         */
        private String resultFilename;
        
        /**
         * 原始大小（字节）
         */
        private long originalSize;
        
        /**
         * 结果大小（字节）
         */
        private long resultSize;
        
        /**
         * 处理状态
         */
        private ProcessingStatus status;
        
        /**
         * 处理时间
         */
        private Instant processedAt;
        
        /**
         * 处理耗时（毫秒）
         */
        private long processingDuration;
        
        /**
         * 错误信息（如果失败）
         */
        private String errorMessage;
        
        /**
         * 上传来源：console-editor（控制台编辑器）、uc-editor（UC编辑器）、attachment-manager（附件管理）
         */
        private String source;
    }
}
