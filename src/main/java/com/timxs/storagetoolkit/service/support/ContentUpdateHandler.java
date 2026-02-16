package com.timxs.storagetoolkit.service.support;

import com.timxs.storagetoolkit.model.ReferenceReplacementResult;
import com.timxs.storagetoolkit.model.ReferenceReplacementTask;
import com.timxs.storagetoolkit.model.ReplaceResult;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * 内容更新处理器接口
 * 用于更新不同类型内容（Post、Comment、ConfigMap 等）中的 URL 引用
 */
public interface ContentUpdateHandler {

    /**
     * 获取处理器支持的源类型
     *
     * @return 源类型，如 "Post", "Comment", "ConfigMap" 等
     */
    String getSourceType();

    /**
     * 执行内容中的 URL 替换
     *
     * @param sourceName 内容源名称（metadata.name）
     * @param task 替换任务，包含 URL 映射
     * @param result 替换结果（用于收集统计和失败记录）
     * @return 替换结果，包含成功替换的 URL 和失败的 URL 及原因
     */
    Mono<ReplaceResult> replaceUrls(String sourceName, ReferenceReplacementTask task, ReferenceReplacementResult result);

    /**
     * 检查处理器是否支持指定的源类型
     *
     * @param sourceType 源类型
     * @return 是否支持
     */
    default boolean supports(String sourceType) {
        return getSourceType().equals(sourceType);
    }

    /**
     * 获取该处理器处理的所有源名称
     * 用于批量替换时获取所有需要处理的内容源
     *
     * @return 源名称集合
     */
    default Mono<Set<String>> getAllSourceNames() {
        return Mono.just(Set.of());
    }
}
