package com.timxs.storagetoolkit.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 清理原因枚举
 * 标识附件被清理删除的原因
 */
public enum CleanupReason {
    /** 重复文件 */
    DUPLICATE("duplicate"),
    /** 未引用文件 */
    UNREFERENCED("unreferenced");

    private final String value;

    CleanupReason(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static CleanupReason fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (CleanupReason reason : values()) {
            // 同时支持小写值（JSON）和大写枚举名（向后兼容）
            if (reason.value.equals(value) || reason.name().equals(value)) {
                return reason;
            }
        }
        return null;
    }
}
