package com.timxs.storagetoolkit.extension;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;

/**
 * 重复检测扫描状态 Extension 实体（全局单例）
 * 存储扫描任务的状态和统计数据
 * metadata.name 固定为 "duplicate-scan-status"
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "storage-toolkit.timxs.com",
     version = "v1alpha1",
     kind = "DuplicateScanStatus",
     plural = "duplicatescanstatuses",
     singular = "duplicatescanstatus")
public class DuplicateScanStatus extends AbstractExtension {

    /**
     * 全局单例的固定名称
     */
    public static final String SINGLETON_NAME = "duplicate-scan-status";

    /**
     * 扫描状态
     */
    private DuplicateScanStatusStatus status;

    @Data
    public static class DuplicateScanStatusStatus {
        /**
         * 扫描阶段：scanning / completed / error
         */
        private String phase;

        /**
         * 扫描开始时间
         */
        private Instant startTime;

        /**
         * 最后扫描完成时间
         */
        private Instant lastScanTime;

        /**
         * 已扫描数量（用于进度显示）
         */
        private int scannedCount;

        /**
         * 总数量（用于进度显示）
         */
        private int totalCount;

        /**
         * 本地附件总数
         */
        private int localAttachmentCount;

        /**
         * 重复组数量
         */
        private int duplicateGroupCount;

        /**
         * 重复文件数量
         */
        private int duplicateFileCount;

        /**
         * 可节省空间（字节）
         */
        private long savableSize;

        /**
         * 错误信息
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
