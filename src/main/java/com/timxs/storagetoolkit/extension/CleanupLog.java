package com.timxs.storagetoolkit.extension;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;

/**
 * 清理日志 Extension 实体
 * 记录删除操作的详细信息，每个文件一条记录
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "storage-toolkit.timxs.com",
     version = "v1alpha1",
     kind = "CleanupLog",
     plural = "cleanuplogs",
     singular = "cleanuplog")
public class CleanupLog extends AbstractExtension {

    /**
     * 日志规格
     */
    private CleanupLogSpec spec;

    @Data
    public static class CleanupLogSpec {
        /**
         * 删除的附件名称
         */
        private String attachmentName;

        /**
         * 附件显示名
         */
        private String displayName;

        /**
         * 文件大小（字节）
         */
        private long size;

        /**
         * 删除原因
         */
        private Reason reason;

        /**
         * 操作用户名
         */
        private String operator;

        /**
         * 删除时间
         */
        private Instant deletedAt;

        /**
         * 错误信息（如果删除失败）
         */
        private String errorMessage;
    }

    /**
     * 删除原因枚举
     */
    public enum Reason {
        /** 重复文件 */
        DUPLICATE,
        /** 未引用文件 */
        UNREFERENCED
    }
}
