package com.timxs.storagetoolkit.extension;

import lombok.Data;
import lombok.EqualsAndHashCode;

import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;
import java.util.List;

/** 批量处理状态 Extension 实体 存储当前批量处理任务的状态和进度 新任务使用唯一名称，原单例名称仅用于兼容旧版。 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
        group = "storage-toolkit.timxs.com",
        version = "v1alpha1",
        kind = "BatchProcessingStatus",
        plural = "batchprocessingstatuses",
        singular = "batchprocessingstatus")
public class BatchProcessingStatus extends AbstractExtension {

    /** 全局单例的固定名称 */
    public static final String SINGLETON_NAME = "batch-processing-status";

    /** 任务规格 */
    private BatchProcessingStatusSpec spec;

    /** 任务状态 */
    private BatchProcessingStatusStatus status;

    @Data
    public static class BatchProcessingStatusSpec {
        /** 待处理的附件名称列表 */
        private List<String> attachmentNames;

        /** 是否保留原文件 */
        private boolean keepOriginal;

        private boolean replaceReferences;
        private boolean enableRemoteStorage;
        private String parentTaskId;
        private com.timxs.storagetoolkit.config.ProcessingConfig config;
        private List<String> completedAttachmentNames = new java.util.ArrayList<>();

        /** Side effects may have started. Never replay these automatically after interruption. */
        private List<String> reviewAttachmentNames = new java.util.ArrayList<>();
    }

    @Data
    public static class BatchProcessingStatusStatus {
        /** 任务阶段 */
        private Phase phase;

        /** 进度信息 */
        private Progress progress;

        /** 失败项列表 */
        private List<FailedItem> failedItems;

        /** 跳过项列表 */
        private List<SkippedItem> skippedItems;

        /** 开始时间 */
        private Instant startTime;

        /** 结束时间 */
        private Instant endTime;

        /** 节省的空间（字节） */
        private long savedBytes;

        /** 保留的原文件数量 */
        private int keptOriginalCount;

        /** 跳过的数量 */
        private int skippedCount;

        /** 错误信息 */
        private String errorMessage;
    }

    /** 进度信息 */
    @Data
    public static class Progress {
        /** 总数 */
        private int total;

        /** 已处理数 */
        private int processed;

        /** 成功数 */
        private int succeeded;

        /** 失败数 */
        private int failed;
    }

    /** 失败项 */
    @Data
    public static class FailedItem {
        /** 附件名称 */
        private String attachmentName;

        /** 显示名称 */
        private String displayName;

        /** 错误信息 */
        private String error;

        private boolean retryable = true;
    }

    /** 跳过项 */
    @Data
    public static class SkippedItem {
        /** 附件名称 */
        private String attachmentName;

        /** 显示名称 */
        private String displayName;

        /** 跳过原因 */
        private String reason;
    }

    /** 任务阶段枚举 */
    public enum Phase {
        /** 等待中 */
        PENDING,
        /** 处理中 */
        PROCESSING,
        /** 取消中 */
        CANCELLING,
        /** 服务重启中断，可继续尚未执行的附件 */
        INTERRUPTED,
        /** 已完成 */
        COMPLETED,
        /** 已取消 */
        CANCELLED,
        /** 错误 */
        ERROR
    }
}
