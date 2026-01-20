package com.timxs.storagetoolkit.extension;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;
import java.util.List;

/**
 * 批量处理状态 Extension 实体（全局单例）
 * 存储当前批量处理任务的状态和进度
 * metadata.name 固定为 "batch-processing-status"
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "storage-toolkit.timxs.com",
     version = "v1alpha1",
     kind = "BatchProcessingStatus",
     plural = "batchprocessingstatuses",
     singular = "batchprocessingstatus")
public class BatchProcessingStatus extends AbstractExtension {

    /**
     * 全局单例的固定名称
     */
    public static final String SINGLETON_NAME = "batch-processing-status";

    /**
     * 任务规格
     */
    private BatchProcessingStatusSpec spec;

    /**
     * 任务状态
     */
    private BatchProcessingStatusStatus status;

    @Data
    public static class BatchProcessingStatusSpec {
        /**
         * 待处理的附件名称列表
         */
        private List<String> attachmentNames;

        /**
         * 是否保留原文件
         */
        private boolean keepOriginal;
    }

    @Data
    public static class BatchProcessingStatusStatus {
        /**
         * 任务阶段
         */
        private Phase phase;

        /**
         * 进度信息
         */
        private Progress progress;

        /**
         * 失败项列表
         */
        private List<FailedItem> failedItems;

        /**
         * 跳过项列表
         */
        private List<SkippedItem> skippedItems;

        /**
         * 开始时间
         */
        private Instant startTime;

        /**
         * 结束时间
         */
        private Instant endTime;

        /**
         * 节省的空间（字节）
         */
        private long savedBytes;

        /**
         * 保留的原文件数量
         */
        private int keptOriginalCount;

        /**
         * 跳过的数量
         */
        private int skippedCount;

        /**
         * 错误信息
         */
        private String errorMessage;
    }

    /**
     * 进度信息
     */
    @Data
    public static class Progress {
        /**
         * 总数
         */
        private int total;

        /**
         * 已处理数
         */
        private int processed;

        /**
         * 成功数
         */
        private int succeeded;

        /**
         * 失败数
         */
        private int failed;
    }

    /**
     * 失败项
     */
    @Data
    public static class FailedItem {
        /**
         * 附件名称
         */
        private String attachmentName;

        /**
         * 显示名称
         */
        private String displayName;

        /**
         * 错误信息
         */
        private String error;
    }

    /**
     * 跳过项
     */
    @Data
    public static class SkippedItem {
        /**
         * 附件名称
         */
        private String attachmentName;

        /**
         * 显示名称
         */
        private String displayName;

        /**
         * 跳过原因
         */
        private String reason;
    }

    /**
     * 任务阶段枚举
     */
    public enum Phase {
        /** 等待中 */
        PENDING,
        /** 处理中 */
        PROCESSING,
        /** 取消中 */
        CANCELLING,
        /** 已完成 */
        COMPLETED,
        /** 已取消 */
        CANCELLED,
        /** 错误 */
        ERROR
    }
}
