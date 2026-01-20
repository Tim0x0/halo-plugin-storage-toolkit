package com.timxs.storagetoolkit.model;

import java.time.Instant;
import java.util.List;

/**
 * 断链视图对象（按 URL 聚合）
 */
public record BrokenLinkVo(
    String url,
    List<BrokenLinkSource> sources,
    int sourceCount,
    Instant discoveredAt
) {
    /**
     * 断链来源
     */
    public record BrokenLinkSource(
        String name,
        String sourceType,
        String sourceName,
        String sourceTitle,
        String sourceUrl,
        Boolean deleted,
        String referenceType,
        String settingName
    ) {}
}
