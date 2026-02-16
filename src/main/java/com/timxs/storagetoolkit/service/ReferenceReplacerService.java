package com.timxs.storagetoolkit.service;

import com.timxs.storagetoolkit.model.ReferenceReplacementResult;
import com.timxs.storagetoolkit.model.ReferenceReplacementTask;
import com.timxs.storagetoolkit.model.ReplaceSource;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 引用替换服务接口
 * 用于批量处理后或删除重复附件时自动替换内容中的附件引用
 */
public interface ReferenceReplacerService {

    /**
     * 执行引用替换任务
     *
     * @param task 替换任务，包含旧附件到新附件的映射
     * @return 替换结果
     */
    Mono<ReferenceReplacementResult> replaceReferences(ReferenceReplacementTask task);

    /**
     * 在单个内容源中执行 URL 替换（带引用类型信息）
     * 支持更精确的标题解析，生成 "标题 - 引用类型" 格式的日志标题
     *
     * @param sourceType 内容类型
     * @param sourceName 内容名称
     * @param sourceTitle 原始标题
     * @param settingName Setting 的 metadata.name（ConfigMap 类型用于解析 group label）
     * @param groupKey ConfigMap 类型为 group key，其他类型为 referenceType（cover/content/draft 等），支持逗号分隔多个值
     * @param urlMapping URL 映射 (oldUrl -> newUrl)
     * @param logSource 日志来源标识
     * @return 替换是否成功
     */
    Mono<Boolean> replaceInSingleSource(String sourceType, String sourceName, String sourceTitle,
                                         String settingName, String groupKey,
                                         Map<String, String> urlMapping, ReplaceSource logSource);

    /**
     * 批量处理完成后的引用替换
     * 将旧附件的引用替换为新附件
     *
     * @param oldAttachmentName 旧附件名称
     * @param newAttachmentName 新附件名称
     * @param oldPermalink 旧附件 permalink（用于 URL 替换）
     * @param newPermalink 新附件 permalink（用于 URL 替换）
     * @return 替换结果
     */
    Mono<ReferenceReplacementResult> replaceAfterBatchProcessing(
        String oldAttachmentName,
        String newAttachmentName,
        String oldPermalink,
        String newPermalink
    );

    /**
     * 删除重复附件前的引用合并
     * 将被删除附件的引用转移到保留的附件
     *
     * @param deletedAttachmentName 被删除的附件名称
     * @param keptAttachmentName 保留的附件名称
     * @param deletedPermalink 被删除附件的 permalink
     * @param keptPermalink 保留附件的 permalink
     * @return 替换结果
     */
    Mono<ReferenceReplacementResult> mergeReferencesBeforeDelete(
        String deletedAttachmentName,
        String keptAttachmentName,
        String deletedPermalink,
        String keptPermalink
    );

    /**
     * 检查引用替换功能是否启用
     *
     * @return 是否启用
     */
    Mono<Boolean> isEnabled();
}
