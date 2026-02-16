package com.timxs.storagetoolkit.extension;

import com.timxs.storagetoolkit.model.ReplaceSource;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * URL 替换日志 Extension 实体
 * 记录所有 URL 替换操作（断链替换、重复扫描替换、批量处理替换）
 * 一条记录 = 一处修改
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "storage-toolkit.timxs.com",
     version = "v1alpha1",
     kind = "UrlReplaceLog",
     plural = "urlreplacelogs",
     singular = "urlreplacelog")
public class UrlReplaceLog extends AbstractExtension {

    /**
     * 日志规格
     */
    private UrlReplaceLogSpec spec;

    @Data
    public static class UrlReplaceLogSpec {
        /**
         * 旧 URL
         */
        private String oldUrl;

        /**
         * 新 URL
         */
        private String newUrl;

        /**
         * 内容类型（Post/SinglePage/Comment/Reply/ConfigMap 等）
         */
        private String sourceType;

        /**
         * 内容名称（metadata.name）
         */
        private String sourceName;

        /**
         * 内容标题（显示用）
         */
        private String sourceTitle;

        /**
         * 引用位置（cover/content/draft/avatar/group key 等）
         */
        private String referenceType;

        /**
         * 替换来源
         */
        private ReplaceSource source;

        /**
         * 替换时间
         */
        private Instant replacedAt;

        /**
         * 是否成功
         */
        private boolean success;

        /**
         * 错误信息
         */
        private String errorMessage;
    }
}
