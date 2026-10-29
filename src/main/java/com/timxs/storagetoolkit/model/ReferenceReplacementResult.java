package com.timxs.storagetoolkit.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 引用替换结果 DTO
 */
@Data
@Builder
public class ReferenceReplacementResult {

    /**
     * 处理的附件总数
     */
    @Builder.Default
    private int totalAttachments = 0;

    /**
     * 成功更新的内容源数量
     */
    @Builder.Default
    private int updatedSources = 0;

    /**
     * 各类型更新统计
     */
    @Builder.Default
    private Map<String, Integer> typeStats = new HashMap<>();

    /** Content sources whose references could not be fully migrated. */
    @Builder.Default
    private Map<String, String> errors = new HashMap<>();

    /**
     * 执行时间
     */
    private Instant executedAt;

    /**
     * 耗时（毫秒）
     */
    private long durationMs;

    /**
     * 添加类型统计
     */
    public synchronized void incrementTypeCount(String sourceType) {
        typeStats.merge(sourceType, 1, Integer::sum);
        updatedSources++;
    }

    public synchronized void addFailure(String sourceType, String sourceName, String reason) {
        errors.put(sourceType + "/" + sourceName, reason);
    }

    public void requireSuccess() {
        if (!errors.isEmpty()) {
            throw new IllegalStateException("引用替换未完成，已保留原附件: " + errors);
        }
    }
}
