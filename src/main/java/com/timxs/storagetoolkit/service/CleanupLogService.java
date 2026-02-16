package com.timxs.storagetoolkit.service;

import com.timxs.storagetoolkit.extension.CleanupLog;
import com.timxs.storagetoolkit.model.CleanupReason;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 清理日志服务接口
 * 提供清理日志的 CRUD 操作
 */
public interface CleanupLogService {

    /**
     * 保存清理日志
     *
     * @param log 日志对象
     * @return 保存后的日志对象
     */
    Mono<CleanupLog> save(CleanupLog log);

    /**
     * 保存清理日志（内部自动获取当前用户名）
     *
     * @param attachmentName 附件名称
     * @param displayName    附件显示名
     * @param size           文件大小
     * @param reason         删除原因
     * @param errorMessage   错误信息（可选）
     * @return 保存后的日志对象
     */
    Mono<CleanupLog> saveLog(String attachmentName, String displayName,
                              long size, CleanupReason reason, String errorMessage);

    /**
     * 查询清理日志列表
     *
     * @param page     页码
     * @param size     每页大小
     * @param reason   删除原因过滤（可选）
     * @param filename 文件名搜索（可选）
     * @return 日志列表流
     */
    Flux<CleanupLog> list(int page, int size, CleanupReason reason, String filename);

    /**
     * 统计日志数量
     *
     * @param reason   删除原因过滤（可选）
     * @param filename 文件名搜索（可选）
     * @return 日志数量
     */
    Mono<Long> count(CleanupReason reason, String filename);

    /**
     * 获取统计信息
     *
     * @return 统计信息
     */
    Mono<CleanupLogStats> getStats();

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
     * 清理日志统计信息
     */
    record CleanupLogStats(
        long totalCount,
        long duplicateCount,
        long unreferencedCount,
        long freedBytes
    ) {}
}
