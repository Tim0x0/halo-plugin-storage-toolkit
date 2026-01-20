package com.timxs.storagetoolkit.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * 重复组视图对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DuplicateGroupVo {
    /**
     * MD5 哈希值
     */
    private String md5Hash;

    /**
     * 文件大小（字节）
     */
    private long fileSize;

    /**
     * 组内文件数量
     */
    private int fileCount;

    /**
     * 可节省空间
     */
    private long savableSize;

    /**
     * 推荐保留的附件名称
     */
    private String recommendedKeep;

    /**
     * 预览 URL（第一个文件的 permalink）
     */
    private String previewUrl;

    /**
     * 媒体类型
     */
    private String mediaType;

    /**
     * 组内文件列表
     */
    private List<DuplicateFileVo> files;

    /**
     * 重复文件视图对象
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DuplicateFileVo {
        private String attachmentName;
        private String displayName;
        private String mediaType;
        private String permalink;
        private Instant uploadTime;
        private String groupName;
        private String groupDisplayName;
        private String policyName;
        private int referenceCount;
        private boolean isRecommended;
    }
}
