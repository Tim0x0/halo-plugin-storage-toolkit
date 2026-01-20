package com.timxs.storagetoolkit.model;

import java.time.Instant;

/**
 * 处理日志查询参数
 * 使用 record 类型，不可变且自动生成 getter、equals、hashCode、toString
 *
 * @param filename  文件名（模糊搜索）
 * @param status    处理状态过滤
 * @param source    来源过滤
 * @param startTime 开始时间（包含）
 * @param endTime   结束时间（包含）
 * @param page      页码（从 1 开始）
 * @param size      每页大小
 */
public record ProcessingLogQuery(
    String filename,
    ProcessingStatus status,
    String source,
    Instant startTime,
    Instant endTime,
    int page,
    int size
) {
    /**
     * 创建默认查询参数
     * 不带任何过滤条件，第 1 页，每页 20 条
     *
     * @return 默认查询参数
     */
    public static ProcessingLogQuery defaultQuery() {
        return new ProcessingLogQuery(null, null, null, null, null, 1, 20);
    }
}
