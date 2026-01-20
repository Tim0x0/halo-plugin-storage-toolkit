package com.timxs.storagetoolkit.extension;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;

/**
 * 断链扫描状态 Extension 实体（全局单例）
 * 存储断链扫描任务的状态和统计数据
 * metadata.name 固定为 "broken-link-scan-status"
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "storage-toolkit.timxs.com",
     version = "v1alpha1",
     kind = "BrokenLinkScanStatus",
     plural = "brokenlinkstatuses",
     singular = "brokenlinkstatus")
public class BrokenLinkScanStatus extends AbstractExtension {

    /**
     * 全局单例的固定名称
     */
    public static final String SINGLETON_NAME = "broken-link-scan-status";

    /**
     * 扫描状态
     */
    private BrokenLinkScanStatusStatus status;

    @Data
    public static class BrokenLinkScanStatusStatus {
        /**
         * 扫描阶段
         */
        private Phase phase;

        /**
         * 开始时间
         */
        private Instant startTime;

        /**
         * 最后扫描时间
         */
        private Instant lastScanTime;

        /**
         * 扫描的内容数
         */
        private int scannedContentCount;

        /**
         * 检查的链接数
         */
        private int checkedLinkCount;

        /**
         * 发现的断链数
         */
        private int brokenLinkCount;

        /**
         * 错误信息
         */
        private String errorMessage;
    }

    /**
     * 扫描阶段枚举
     */
    public enum Phase {
        /** 扫描中 */
        SCANNING,
        /** 已完成 */
        COMPLETED,
        /** 错误 */
        ERROR
    }
}
