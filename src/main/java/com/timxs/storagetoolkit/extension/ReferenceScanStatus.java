package com.timxs.storagetoolkit.extension;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;

/**
 * 引用扫描状态 Extension 实体（全局单例）
 * 存储扫描任务的状态和统计数据
 * metadata.name 固定为 "global-scan-status"
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "storage-toolkit.timxs.com",
     version = "v1alpha1",
     kind = "ReferenceScanStatus",
     plural = "referencescanstatuses",
     singular = "referencescanstatus")
public class ReferenceScanStatus extends AbstractExtension {

    /**
     * 全局单例的固定名称
     */
    public static final String SINGLETON_NAME = "global-scan-status";

    /**
     * 扫描状态（系统计算的实际状态）
     * 注意：此 Extension 没有 spec 字段，因为扫描完全由系统触发和计算，
     * 没有用户声明的期望状态。
     */
    private ReferenceScanStatusStatus status;

    /**
     * 扫描状态
     * 包含扫描进度和统计数据
     */
    @Data
    public static class ReferenceScanStatusStatus {
        /**
         * 扫描阶段：scanning / completed / error
         */
        private String phase;

        /**
         * 扫描开始时间（用于超时检测）
         */
        private Instant startTime;

        /**
         * 最后扫描完成时间
         */
        private Instant lastScanTime;

        /**
         * 总附件数
         */
        private int totalAttachments;

        /**
         * 已引用附件数
         */
        private int referencedCount;

        /**
         * 未引用附件数
         */
        private int unreferencedCount;

        /**
         * 未引用附件占用空间（字节）
         */
        private long unreferencedSize;

        /**
         * 错误信息（如有）
         */
        private String errorMessage;
    }

    /**
     * 扫描阶段常量
     */
    public static class Phase {
        public static final String SCANNING = "SCANNING";
        public static final String COMPLETED = "COMPLETED";
        public static final String ERROR = "ERROR";
    }
}
