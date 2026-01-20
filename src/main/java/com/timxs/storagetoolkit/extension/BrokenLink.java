package com.timxs.storagetoolkit.extension;

import java.time.Instant;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 断链记录 Extension 实体
 * 存储扫描发现的断链信息
 * 参考 AttachmentReference 的设计：每个 URL 一条记录，包含所有引用源
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "storage-toolkit.timxs.com",
     version = "v1alpha1",
     kind = "BrokenLink",
     plural = "brokenlinks",
     singular = "brokenlink")
public class BrokenLink extends AbstractExtension {

    /**
     * 断链规格
     */
    private BrokenLinkSpec spec;

    /**
     * 断链状态
     */
    private BrokenLinkStatus status;

    @Data
    public static class BrokenLinkSpec {
        /**
         * 断链 URL
         */
        private String url;
    }

    @Data
    public static class BrokenLinkStatus {
        /**
         * 引用次数
         */
        private int sourceCount;

        /**
         * 发现时间
         */
        private Instant discoveredAt;

        /**
         * 引用源列表
         */
        private List<BrokenLinkSource> sources;

        /**
         * 待删除标识（扫描时标记旧记录）
         */
        private Boolean pendingDelete;
    }

    /**
     * 断链引用源信息
     */
    @Data
    public static class BrokenLinkSource {
        /**
         * 引用源类型：Post、SinglePage、Comment、Reply、ConfigMap、Moment、Photo
         */
        private String sourceType;

        /**
         * 引用源名称（metadata.name）
         */
        private String sourceName;

        /**
         * 引用源标题（用于显示）
         */
        private String sourceTitle;

        /**
         * 引用源链接（用于跳转）
         */
        private String sourceUrl;

        /**
         * 是否已删除（在回收站中）
         */
        private Boolean deleted;

        /**
         * 引用类型：cover（封面图）、content（内容）、media（媒体）
         * 对于 ConfigMap 类型，存储 groupKey（如 basic、image）
         */
        private String referenceType;

        /**
         * Setting 名称（仅 ConfigMap 类型使用）
         */
        private String settingName;
    }
}
