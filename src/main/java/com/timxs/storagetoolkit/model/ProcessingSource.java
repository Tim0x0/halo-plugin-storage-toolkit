package com.timxs.storagetoolkit.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 图片处理来源枚举
 * 标识图片处理操作的触发来源
 */
public enum ProcessingSource {
    /** 控制台编辑器 */
    CONSOLE_EDITOR("console-editor"),
    /** UC 编辑器 */
    UC_EDITOR("uc-editor"),
    /** 附件管理器 */
    ATTACHMENT_MANAGER("attachment-manager"),
    /** 批量处理 */
    BATCH_PROCESSING("batch-processing");

    private final String value;

    ProcessingSource(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ProcessingSource fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (ProcessingSource source : values()) {
            // 同时支持小写值（JSON）和大写枚举名（向后兼容）
            if (source.value.equals(value) || source.name().equals(value)) {
                return source;
            }
        }
        return null;
    }
}
