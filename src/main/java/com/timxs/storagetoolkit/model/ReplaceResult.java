package com.timxs.storagetoolkit.model;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * URL 替换结果
 * 用于记录单次内容替换操作中每个 URL 的处理状态
 */
@Data
@Builder
public class ReplaceResult {

    /**
     * 成功替换的 URL 集合
     */
    @Builder.Default
    private Set<String> replaced = new HashSet<>();

    /**
     * 失败的 URL -> 错误原因
     */
    @Builder.Default
    private Map<String, String> errors = new HashMap<>();

    /**
     * 创建空结果
     */
    public static ReplaceResult empty() {
        return ReplaceResult.builder().build();
    }

    /**
     * 添加成功替换的 URL
     */
    public void addReplaced(String url) {
        replaced.add(url);
    }

    /**
     * 添加失败的 URL 及原因
     */
    public void addError(String url, String reason) {
        errors.put(url, reason);
    }

    /**
     * 是否有任何替换发生
     */
    public boolean hasReplaced() {
        return !replaced.isEmpty();
    }

}
