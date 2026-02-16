package com.timxs.storagetoolkit;

import com.timxs.storagetoolkit.extension.AttachmentReference;
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

import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.IIOServiceProvider;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.ImageWriterSpi;
import java.util.ArrayList;
import java.util.List;

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
     * 已注册的 SPI 列表，用于插件停止时注销
     */
    private final List<IIOServiceProvider> registeredSpis = new ArrayList<>();

    /**
     * 构造函数
     *
     * @param pluginContext 插件上下文
     * @param schemeManager 扩展模式管理器
     */
    public StorageToolkitPlugin(PluginContext pluginContext, SchemeManager schemeManager) {
        super(pluginContext);
        this.schemeManager = schemeManager;
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

        // 注册 WhitelistEntry Extension（带索引）
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

        // 手动注册 ImageIO SPI（解决插件类加载器隔离问题）
        registerImageIOSpi();

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
        unregisterImageIOSpi();

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

    /**
     * 手动注册 ImageIO SPI（WebP 和 AVIF）
     * 由于 Halo 插件使用独立的类加载器，ImageIO 的 SPI 自动发现机制可能失效
     * 需要手动使用插件的类加载器加载并注册 SPI
     */
    private void registerImageIOSpi() {
        ClassLoader pluginClassLoader = this.getClass().getClassLoader();
        IIORegistry registry = IIORegistry.getDefaultInstance();

        // 预加载 WebP 相关类，解决运行时 NoClassDefFoundError
        preloadWebPClasses(pluginClassLoader);
        
        // 预加载 AVIF 相关类
        preloadAvifClasses(pluginClassLoader);

        // 注册 WebP SPI
        registerSpi(registry, pluginClassLoader, "com.luciad.imageio.webp.WebPImageReaderSpi", ImageReaderSpi.class, "WebP");
        registerSpi(registry, pluginClassLoader, "com.luciad.imageio.webp.WebPImageWriterSpi", ImageWriterSpi.class, "WebP");

        // 注册 AVIF SPI
        registerSpi(registry, pluginClassLoader, "com.github.avifimageio.AvifImageReaderSpi", ImageReaderSpi.class, "AVIF");
        registerSpi(registry, pluginClassLoader, "com.github.avifimageio.AvifImageWriterSpi", ImageWriterSpi.class, "AVIF");
    }

    /**
     * 注册单个 SPI
     */
    private <T extends IIOServiceProvider> void registerSpi(IIORegistry registry, ClassLoader classLoader, 
            String className, Class<T> spiType, String formatName) {
        try {
            Class<?> spiClass = classLoader.loadClass(className);
            T spi = spiType.cast(spiClass.getDeclaredConstructor().newInstance());
            registry.registerServiceProvider(spi);
            registeredSpis.add(spi);
            log.debug("{} {} 注册成功", formatName, spiType.getSimpleName());
        } catch (Exception e) {
            log.warn("{} {} 注册失败: {}", formatName, spiType.getSimpleName(), e.getMessage());
        }
    }
    
    /**
     * 注销 ImageIO SPI
     * 插件停止时调用，避免类加载器泄漏
     */
    private void unregisterImageIOSpi() {
        if (registeredSpis.isEmpty()) {
            return;
        }
        
        try {
            IIORegistry registry = IIORegistry.getDefaultInstance();
            for (IIOServiceProvider spi : registeredSpis) {
                try {
                    registry.deregisterServiceProvider(spi);
                    log.debug("SPI 注销成功: {}", spi.getClass().getName());
                } catch (Exception e) {
                    log.warn("SPI 注销失败: {} - {}", spi.getClass().getName(), e.getMessage());
                }
            }
            registeredSpis.clear();
        } catch (Exception e) {
            log.error("ImageIO SPI 注销过程出错", e);
        }
    }
    
    /**
     * 预加载 WebP 相关类
     * 解决 ImageIO 在创建 Reader/Writer 实例时找不到依赖类的问题
     */
    private void preloadWebPClasses(ClassLoader classLoader) {
        String[] classNames = {
            "com.luciad.imageio.webp.WebPReadParam",
            "com.luciad.imageio.webp.WebPWriteParam",
            "com.luciad.imageio.webp.WebPImageReader",
            "com.luciad.imageio.webp.WebPImageWriter",
            "com.luciad.imageio.webp.WebPDecoderOptions",
            "com.luciad.imageio.webp.WebPEncoderOptions"
        };
        
        for (String className : classNames) {
            try {
                Class<?> clazz = classLoader.loadClass(className);
                log.debug("预加载 WebP 类成功: {}", clazz.getName());
            } catch (ClassNotFoundException e) {
                log.warn("预加载 WebP 类失败: {}", className);
            }
        }
    }
    
    /**
     * 预加载 AVIF 相关类并初始化 native 库
     * 必须在正确的 ClassLoader 上下文中触发 native 库加载
     */
    private void preloadAvifClasses(ClassLoader classLoader) {
        // 保存原来的 contextClassLoader
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            // 设置插件的 ClassLoader，让 avif-imageio 能找到 native 库资源
            Thread.currentThread().setContextClassLoader(classLoader);
            
            // 加载 Avif 类并触发 native 库初始化
            Class<?> avifClass = classLoader.loadClass("com.github.avifimageio.Avif");
            // 调用 isAvailable() 触发 native 库加载
            var isAvailableMethod = avifClass.getMethod("isAvailable");
            boolean available = (boolean) isAvailableMethod.invoke(null);
            
            if (available) {
                log.info("AVIF native 库加载成功");
            } else {
                // 获取加载错误信息
                var getLoadErrorMethod = avifClass.getMethod("getLoadError");
                Throwable error = (Throwable) getLoadErrorMethod.invoke(null);
                log.warn("AVIF native 库加载失败: {}", error != null ? error.getMessage() : "unknown");
            }
            
            // 预加载其他 AVIF 相关类
            String[] classNames = {
                "com.github.avifimageio.AvifImageReader",
                "com.github.avifimageio.AvifImageWriter",
                "com.github.avifimageio.AvifWriteParam",
                "com.github.avifimageio.AvifEncoderOptions",
                "com.github.avifimageio.AvifDecoderOptions"
            };
            
            for (String className : classNames) {
                try {
                    classLoader.loadClass(className);
                } catch (ClassNotFoundException e) {
                    log.warn("预加载 AVIF 类失败: {}", className);
                }
            }
        } catch (Exception e) {
            log.warn("AVIF 初始化失败: {}", e.getMessage());
        } finally {
            // 恢复原来的 contextClassLoader
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }
}
