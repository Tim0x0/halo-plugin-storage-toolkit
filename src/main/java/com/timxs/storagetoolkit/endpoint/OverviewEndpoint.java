package com.timxs.storagetoolkit.endpoint;

import com.timxs.storagetoolkit.extension.ProcessingLog;
import com.timxs.storagetoolkit.model.ProcessingLogQuery;
import com.timxs.storagetoolkit.service.ProcessingLogService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ApiVersion;
import org.springframework.data.domain.Sort;

import java.util.List;

/**
 * 概览数据 REST API 端点
 * 提供仪表盘概览所需的统计数据
 */
@ApiVersion("console.api.storage-toolkit.timxs.com/v1alpha1")
@RestController
@RequestMapping("/overview")
@RequiredArgsConstructor
public class OverviewEndpoint {

    private final ReactiveExtensionClient client;
    private final ProcessingLogService processingLogService;

    /**
     * 获取概览数据
     * 包括附件总数、存储总大小、图片数量、最近处理日志
     *
     * @return 概览数据
     */
    @GetMapping
    public Mono<OverviewData> getOverview() {
        // 并行获取各项统计数据
        Mono<AttachmentStats> attachmentStats = getAttachmentStats();
        Mono<List<ProcessingLog>> recentLogs = getRecentLogs();

        return Mono.zip(attachmentStats, recentLogs)
            .map(tuple -> {
                OverviewData data = new OverviewData();
                AttachmentStats stats = tuple.getT1();
                data.setTotalAttachments(stats.getCount());
                data.setTotalSize(stats.getTotalSize());
                data.setImageCount(stats.getImageCount());
                data.setRecentLogs(tuple.getT2());
                return data;
            });
    }

    /**
     * 获取附件统计信息
     */
    private Mono<AttachmentStats> getAttachmentStats() {
        return client.listAll(Attachment.class, null, Sort.unsorted())
            .reduce(new AttachmentStats(), (stats, attachment) -> {
                stats.setCount(stats.getCount() + 1);
                
                if (attachment.getSpec() != null) {
                    // 累加文件大小
                    Long size = attachment.getSpec().getSize();
                    if (size != null) {
                        stats.setTotalSize(stats.getTotalSize() + size);
                    }
                    
                    // 统计图片数量
                    String mediaType = attachment.getSpec().getMediaType();
                    if (mediaType != null && mediaType.startsWith("image/")) {
                        stats.setImageCount(stats.getImageCount() + 1);
                    }
                }
                return stats;
            });
    }

    /**
     * 获取最近5条处理日志
     */
    private Mono<List<ProcessingLog>> getRecentLogs() {
        ProcessingLogQuery query = new ProcessingLogQuery(null, null, null, null, 1, 5);
        return processingLogService.list(query).collectList();
    }

    /**
     * 附件统计信息（内部使用）
     */
    @Data
    private static class AttachmentStats {
        private long count = 0;
        private long totalSize = 0;
        private long imageCount = 0;
    }

    /**
     * 概览数据响应
     */
    @Data
    public static class OverviewData {
        /**
         * 附件总数
         */
        private long totalAttachments;
        
        /**
         * 存储总大小（字节）
         */
        private long totalSize;
        
        /**
         * 图片数量
         */
        private long imageCount;
        
        /**
         * 最近处理日志
         */
        private List<ProcessingLog> recentLogs;
    }
}
