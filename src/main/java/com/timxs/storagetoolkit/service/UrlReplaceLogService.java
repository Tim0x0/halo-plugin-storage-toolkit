package com.timxs.storagetoolkit.service;

import com.timxs.storagetoolkit.extension.UrlReplaceLog;
import com.timxs.storagetoolkit.model.ReplaceSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * URL 替换日志服务接口
 * 提供 URL 替换日志的 CRUD 操作
 */
public interface UrlReplaceLogService {

    /**
     * 保存替换日志
     *
     * @param log 日志对象
     * @return 保存后的日志对象
     */
    Mono<UrlReplaceLog> save(UrlReplaceLog log);

    /**
     * 保存成功的替换日志
     *
     * @param oldUrl 旧 URL
     * @param newUrl 新 URL
     * @param sourceType 内容类型
     * @param sourceName 内容名称
     * @param sourceTitle 内容标题
     * @param referenceType 引用位置（cover/content/draft/group key 等）
     * @param source 替换来源
     * @return 保存后的日志对象
     */
    Mono<UrlReplaceLog> saveSuccessLog(String oldUrl, String newUrl, String sourceType,
                                        String sourceName, String sourceTitle,
                                        String referenceType, ReplaceSource source);

    /**
     * 保存失败的替换日志
     *
     * @param oldUrl 旧 URL
     * @param newUrl 新 URL
     * @param sourceType 内容类型
     * @param sourceName 内容名称
     * @param sourceTitle 内容标题
     * @param referenceType 引用位置（cover/content/draft/group key 等）
     * @param source 替换来源
     * @param errorMessage 错误信息
     * @return 保存后的日志对象
     */
    Mono<UrlReplaceLog> saveFailedLog(String oldUrl, String newUrl, String sourceType,
                                       String sourceName, String sourceTitle,
                                       String referenceType, ReplaceSource source,
                                       String errorMessage);

    /**
     * 查询替换日志列表
     *
     * @param page 页码
     * @param size 每页大小
     * @param source 替换来源过滤（可选）
     * @param keyword 关键词搜索（可选）
     * @return 日志列表流
     */
    Flux<UrlReplaceLog> list(int page, int size, ReplaceSource source, String keyword);

    /**
     * 统计日志数量
     *
     * @param source 替换来源过滤（可选）
     * @param keyword 关键词搜索（可选）
     * @return 日志数量
     */
    Mono<Long> count(ReplaceSource source, String keyword);

    /**
     * 获取统计信息
     *
     * @return 统计信息
     */
    Mono<UrlReplaceLogStats> getStats();

    /**
     * 删除过期日志
     *
     * @param retentionDays 保留天数
     * @return 完成信号
     */
    Mono<Void> deleteExpired(int retentionDays);

    /**
     * 清空所有日志
     *
     * @return 删除的日志数量
     */
    Mono<Long> deleteAll();

    /**
     * 统计信息
     */
    record UrlReplaceLogStats(
        long totalCount,
        long successCount,
        long failedCount,
        long brokenLinkCount,
        long duplicateCount,
        long batchProcessingCount
    ) {}
}
