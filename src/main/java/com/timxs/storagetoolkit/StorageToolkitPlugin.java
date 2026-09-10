package com.timxs.storagetoolkit;

import com.timxs.storagetoolkit.extension.AttachmentReference;
import com.timxs.storagetoolkit.imageio.PinnedImageIo;
import com.timxs.storagetoolkit.extension.BatchProcessingStatus;
import com.timxs.storagetoolkit.extension.BrokenLink;
import com.timxs.storagetoolkit.extension.BrokenLinkScanStatus;
import com.timxs.storagetoolkit.extension.CleanupLog;
import com.timxs.storagetoolkit.extension.DuplicateGroup;
import com.timxs.storagetoolkit.extension.DuplicateScanStatus;
import com.timxs.storagetoolkit.extension.ProcessingLog;
import com.timxs.storagetoolkit.extension.ReferenceScanStatus;
import com.timxs.storagetoolkit.extension.UrlReplaceLog;
import com.timxs.storagetoolkit.extension.WhitelistEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpec;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

import static run.halo.app.extension.index.IndexAttributeFactory.simpleAttribute;

/**
 * Storage Toolkit 插件主类
 * 管理插件的生命周期，包括启动时注册 Extension 和停止时清理资源
 *
 * @author Tim0x0
 * @since 1.0.0
 */
@Slf4j
@Component
public class StorageToolkitPlugin extends BasePlugin {

    /**
     * Halo 扩展模式管理器，用于注册和取消注册 Extension
     */
    private final SchemeManager schemeManager;

    /**
     * 固定 ImageIO 编解码器，持有本插件的 WebP/AVIF SPI 实例并管理全局注册表注册
     */
    private final PinnedImageIo pinnedImageIo;

    /**
     * 构造函数
     *
     * @param pluginContext 插件上下文
     * @param schemeManager 扩展模式管理器
     * @param pinnedImageIo 固定 ImageIO 编解码器
     */
    public StorageToolkitPlugin(PluginContext pluginContext, SchemeManager schemeManager,
                                PinnedImageIo pinnedImageIo) {
        super(pluginContext);
        this.schemeManager = schemeManager;
        this.pinnedImageIo = pinnedImageIo;
    }

    /**
     * 插件启动时调用
     * 注册 ProcessingLog Extension 和 WebP ImageIO SPI
     */
    @Override
    public void start() {
        log.info("Storage Toolkit 插件启动中...");

        // 注册 ProcessingLog Extension（带索引）
        schemeManager.register(ProcessingLog.class, indexSpecs -> {
            // 时间索引，用于排序和时间范围过滤
            indexSpecs.add(new IndexSpec()
                .setName("spec.processedAt")
                .setIndexFunc(simpleAttribute(ProcessingLog.class,
                    logEntry -> logEntry.getSpec() != null && logEntry.getSpec().getProcessedAt() != null
                        ? logEntry.getSpec().getProcessedAt().toString() : null)));
            // 状态索引，用于状态过滤
            indexSpecs.add(new IndexSpec()
                .setName("spec.status")
                .setIndexFunc(simpleAttribute(ProcessingLog.class,
                    logEntry -> logEntry.getSpec() != null && logEntry.getSpec().getStatus() != null
                        ? logEntry.getSpec().getStatus().name() : null)));
            // 来源索引，用于来源过滤
            indexSpecs.add(new IndexSpec()
                .setName("spec.source")
                .setIndexFunc(simpleAttribute(ProcessingLog.class,
                    logEntry -> logEntry.getSpec() != null && logEntry.getSpec().getSource() != null
                        ? logEntry.getSpec().getSource().name() : null)));
            // 文件名索引，用于文件名搜索
            indexSpecs.add(new IndexSpec()
                .setName("spec.originalFilename")
                .setIndexFunc(simpleAttribute(ProcessingLog.class,
                    logEntry -> logEntry.getSpec() != null ? logEntry.getSpec().getOriginalFilename() : null)));
        });

        // 注册 AttachmentReference Extension（带索引）
        schemeManager.register(AttachmentReference.class, indexSpecs -> {
            indexSpecs.add(new IndexSpec()
                .setName("spec.attachmentName")
                .setIndexFunc(simpleAttribute(AttachmentReference.class,
                    ref -> ref.getSpec() != null ? ref.getSpec().getAttachmentName() : null)));
        });

        // 注册 ReferenceScanStatus Extension
        schemeManager.register(ReferenceScanStatus.class);

        // 注册 DuplicateScanStatus Extension
        schemeManager.register(DuplicateScanStatus.class);

        // 注册 DuplicateGroup Extension
        schemeManager.register(DuplicateGroup.class);

        // 注册 BatchProcessingStatus Extension
        schemeManager.register(BatchProcessingStatus.class);

        // 注册 CleanupLog Extension（带索引）
        schemeManager.register(CleanupLog.class, indexSpecs -> {
            indexSpecs.add(new IndexSpec()
                .setName("spec.attachmentName")
                .setIndexFunc(simpleAttribute(CleanupLog.class,
                    logEntry -> logEntry.getSpec() != null ? logEntry.getSpec().getAttachmentName() : null)));
            indexSpecs.add(new IndexSpec()
                .setName("spec.reason")
                .setIndexFunc(simpleAttribute(CleanupLog.class,
                    logEntry -> logEntry.getSpec() != null && logEntry.getSpec().getReason() != null
                        ? logEntry.getSpec().getReason().name() : null)));
            // 时间索引，用于排序
            indexSpecs.add(new IndexSpec()
                .setName("spec.deletedAt")
                .setIndexFunc(simpleAttribute(CleanupLog.class,
                    logEntry -> logEntry.getSpec() != null && logEntry.getSpec().getDeletedAt() != null
                        ? logEntry.getSpec().getDeletedAt().toString() : null)));
            // 文件名索引，用于文件名搜索
            indexSpecs.add(new IndexSpec()
                .setName("spec.displayName")
                .setIndexFunc(simpleAttribute(CleanupLog.class,
                    logEntry -> logEntry.getSpec() != null ? logEntry.getSpec().getDisplayName() : null)));
        });

        // 注册 BrokenLinkScanStatus Extension
        schemeManager.register(BrokenLinkScanStatus.class);

        // 注册 BrokenLink Extension（带索引）
        schemeManager.register(BrokenLink.class, indexSpecs -> {
            indexSpecs.add(new IndexSpec()
                .setName("spec.url")
                .setIndexFunc(simpleAttribute(BrokenLink.class,
                    link -> link.getSpec() != null ? link.getSpec().getUrl() : null)));
        });

        // 注册 WhitelistEntry Extension
        schemeManager.register(WhitelistEntry.class, indexSpecs -> {
            indexSpecs.add(new IndexSpec()
                .setName("spec.url")
                .setIndexFunc(simpleAttribute(WhitelistEntry.class,
                    entry -> entry.getSpec() != null ? entry.getSpec().getUrl() : null)));
        });

        // 注册 UrlReplaceLog Extension（带索引）
        schemeManager.register(UrlReplaceLog.class, indexSpecs -> {
            // 时间索引，用于排序
            indexSpecs.add(new IndexSpec()
                .setName("spec.replacedAt")
                .setIndexFunc(simpleAttribute(UrlReplaceLog.class,
                    logEntry -> logEntry.getSpec() != null && logEntry.getSpec().getReplacedAt() != null
                        ? logEntry.getSpec().getReplacedAt().toString() : null)));
            // 来源索引，用于来源过滤
            indexSpecs.add(new IndexSpec()
                .setName("spec.source")
                .setIndexFunc(simpleAttribute(UrlReplaceLog.class,
                    logEntry -> logEntry.getSpec() != null && logEntry.getSpec().getSource() != null
                        ? logEntry.getSpec().getSource().name() : null)));
            // 旧 URL 索引，用于 URL 搜索
            indexSpecs.add(new IndexSpec()
                .setName("spec.oldUrl")
                .setIndexFunc(simpleAttribute(UrlReplaceLog.class,
                    logEntry -> logEntry.getSpec() != null ? logEntry.getSpec().getOldUrl() : null)));
            // 内容标题索引，用于标题搜索
            indexSpecs.add(new IndexSpec()
                .setName("spec.sourceTitle")
                .setIndexFunc(simpleAttribute(UrlReplaceLog.class,
                    logEntry -> logEntry.getSpec() != null ? logEntry.getSpec().getSourceTitle() : null)));
        });

        // 注册本插件的 WebP/AVIF SPI（全局注册表 + 固定实例，解决插件类加载器隔离与外来 SPI 劫持问题）
        pinnedImageIo.register();

        log.info("Storage Toolkit 插件启动成功！");
    }

    /**
     * 插件停止时调用
     * 取消注册 Extension 和 SPI
     */
    @Override
    public void stop() {
        log.info("Storage Toolkit 插件停止中...");

        // 注销 ImageIO SPI
        pinnedImageIo.unregister();

        // 取消注册 Extension
        schemeManager.unregister(schemeManager.get(ProcessingLog.class));
        schemeManager.unregister(schemeManager.get(AttachmentReference.class));
        schemeManager.unregister(schemeManager.get(ReferenceScanStatus.class));
        schemeManager.unregister(schemeManager.get(DuplicateScanStatus.class));
        schemeManager.unregister(schemeManager.get(DuplicateGroup.class));
        schemeManager.unregister(schemeManager.get(BatchProcessingStatus.class));
        schemeManager.unregister(schemeManager.get(CleanupLog.class));
        schemeManager.unregister(schemeManager.get(BrokenLinkScanStatus.class));
        schemeManager.unregister(schemeManager.get(BrokenLink.class));
        schemeManager.unregister(schemeManager.get(WhitelistEntry.class));
        schemeManager.unregister(schemeManager.get(UrlReplaceLog.class));

        log.info("Storage Toolkit 插件已停止");
    }
}
