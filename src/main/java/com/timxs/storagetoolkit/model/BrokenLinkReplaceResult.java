package com.timxs.storagetoolkit.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 断链替换结果 DTO
 */
@Data
@Builder
public class BrokenLinkReplaceResult {

    /**
     * 是否全部成功
     */
    @Builder.Default
    private boolean allSuccess = true;

    /**
     * 总来源数
     */
    @Builder.Default
    private int totalSources = 0;

    /**
     * 成功数
     */
    @Builder.Default
    private int successCount = 0;

    /**
     * 失败数
     */
    @Builder.Default
    private int failedCount = 0;

    /**
     * 断链是否已删除（全部替换成功时删除）
     */
    @Builder.Default
    private boolean brokenLinkDeleted = false;

    /**
     * 失败详情
     */
    @Builder.Default
    private List<FailedSource> failures = new ArrayList<>();

    /**
     * 失败来源详情
     */
    @Data
    @Builder
    public static class FailedSource {
        private String sourceType;
        private String sourceName;
        private String sourceTitle;
        private String errorMessage;
    }

    /**
     * 添加成功计数
     */
    public synchronized void incrementSuccess() {
        successCount++;
    }

    /**
     * 添加失败记录
     */
    public synchronized void addFailure(String sourceType, String sourceName, String sourceTitle, String errorMessage) {
        failedCount++;
        allSuccess = false;
        failures.add(FailedSource.builder()
            .sourceType(sourceType)
            .sourceName(sourceName)
            .sourceTitle(sourceTitle)
            .errorMessage(errorMessage)
            .build());
    }
}
