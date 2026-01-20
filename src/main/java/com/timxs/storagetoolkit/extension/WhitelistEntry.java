package com.timxs.storagetoolkit.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;

/**
 * 断链白名单 Extension 实体
 * 存储需要忽略的断链 URL
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
    group = "storage-toolkit.timxs.com",
    version = "v1alpha1",
    kind = "WhitelistEntry",
    plural = "whitelistentries",
    singular = "whitelistentry"
)
@Schema(description = "断链白名单")
public class WhitelistEntry extends AbstractExtension {

    @Schema(description = "白名单规格")
    private WhitelistEntrySpec spec;

    @Data
    @Schema(description = "白名单规格")
    public static class WhitelistEntrySpec {

        @Schema(description = "需要忽略的 URL 或 URL 前缀", requiredMode = Schema.RequiredMode.REQUIRED)
        private String url;

        @Schema(description = "备注说明")
        private String note;

        @Schema(description = "添加时间")
        private Instant createdAt;

        @Schema(description = "匹配模式：exact 表示精确匹配，prefix 表示前缀匹配", defaultValue = "exact")
        private String matchMode = "exact";
    }
}
