package com.timxs.storagetoolkit.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 引用替换任务 DTO
 */
@Data
@Builder
public class ReferenceReplacementTask {

    /**
     * 附件映射：oldAttachmentName -> newAttachmentName
     */
    private Map<String, String> attachmentMapping;

    /**
     * URL 映射：oldPermalink -> newPermalink
     */
    private Map<String, String> urlMapping;

    /**
     * 任务来源
     */
    private ReplaceSource source;

    /**
     * 是否 Dry Run（仅预览，不实际执行）
     */
    @Builder.Default
    private boolean dryRun = false;
}
