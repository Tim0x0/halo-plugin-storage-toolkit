package com.timxs.storagetoolkit.model;

import java.util.List;

/**
 * 清理操作结果
 * 用于重复文件删除和未引用附件删除等清理操作的返回结果
 */
public record CleanupResult(
    int deletedCount,
    int failedCount,
    long freedSize,
    List<String> errors
) {}
