package com.timxs.storagetoolkit.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * URL 替换来源枚举
 * 标识 URL 替换操作的触发来源
 */
public enum ReplaceSource {
    /** 断链替换 */
    BROKEN_LINK("broken-link"),
    /** 重复扫描删除 */
    DUPLICATE("duplicate"),
    /** 批量处理 */
    BATCH_PROCESSING("batch-processing");

    private final String value;

    ReplaceSource(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ReplaceSource fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (ReplaceSource source : values()) {
            // 同时支持小写值（JSON）和大写枚举名（向后兼容）
            if (source.value.equals(value) || source.name().equals(value)) {
                return source;
            }
        }
        return null;
    }
}
