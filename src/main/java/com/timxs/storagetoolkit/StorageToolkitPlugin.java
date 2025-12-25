package com.timxs.storagetoolkit;

import com.timxs.storagetoolkit.extension.ProcessingLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.IIOServiceProvider;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.ImageWriterSpi;
import java.util.ArrayList;
import java.util.List;

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

        // 注册 ProcessingLog Extension
        schemeManager.register(ProcessingLog.class);

        // 手动注册 WebP ImageIO SPI（解决插件类加载器隔离问题）
        registerWebPImageIO();

        log.info("Storage Toolkit 插件启动成功！");
    }

    /**
     * 插件停止时调用
     * 取消注册 Extension 和 SPI
     */
    @Override
    public void stop() {
        log.info("Storage Toolkit 插件停止中...");

        // 注销 WebP ImageIO SPI
        unregisterWebPImageIO();

        // 取消注册 Extension
        schemeManager.unregister(schemeManager.get(ProcessingLog.class));

        log.info("Storage Toolkit 插件已停止");
    }

    /**
     * 手动注册 WebP ImageIO SPI
     * 由于 Halo 插件使用独立的类加载器，ImageIO 的 SPI 自动发现机制可能失效
     * 需要手动使用插件的类加载器加载并注册 SPI
     */
    private void registerWebPImageIO() {
        try {
            ClassLoader pluginClassLoader = this.getClass().getClassLoader();
            IIORegistry registry = IIORegistry.getDefaultInstance();

            // 预加载 WebP 相关类，解决运行时 NoClassDefFoundError
            preloadWebPClasses(pluginClassLoader);

            // 注册 WebP ImageReaderSpi
            try {
                Class<?> readerSpiClass = pluginClassLoader.loadClass("com.luciad.imageio.webp.WebPImageReaderSpi");
                ImageReaderSpi readerSpi = (ImageReaderSpi) readerSpiClass.getDeclaredConstructor().newInstance();
                registry.registerServiceProvider(readerSpi);
                registeredSpis.add(readerSpi);
                log.info("WebP ImageReaderSpi 注册成功: {}", readerSpiClass.getName());
            } catch (Exception e) {
                log.warn("WebP ImageReaderSpi 注册失败: {}", e.getMessage());
            }

            // 注册 WebP ImageWriterSpi
            try {
                Class<?> writerSpiClass = pluginClassLoader.loadClass("com.luciad.imageio.webp.WebPImageWriterSpi");
                ImageWriterSpi writerSpi = (ImageWriterSpi) writerSpiClass.getDeclaredConstructor().newInstance();
                registry.registerServiceProvider(writerSpi);
                registeredSpis.add(writerSpi);
                log.info("WebP ImageWriterSpi 注册成功: {}", writerSpiClass.getName());
            } catch (Exception e) {
                log.warn("WebP ImageWriterSpi 注册失败: {}", e.getMessage());
            }

        } catch (Exception e) {
            log.error("WebP ImageIO SPI 注册过程出错", e);
        }
    }
    
    /**
     * 注销 WebP ImageIO SPI
     * 插件停止时调用，避免类加载器泄漏
     */
    private void unregisterWebPImageIO() {
        if (registeredSpis.isEmpty()) {
            return;
        }
        
        try {
            IIORegistry registry = IIORegistry.getDefaultInstance();
            for (IIOServiceProvider spi : registeredSpis) {
                try {
                    registry.deregisterServiceProvider(spi);
                    log.info("SPI 注销成功: {}", spi.getClass().getName());
                } catch (Exception e) {
                    log.warn("SPI 注销失败: {} - {}", spi.getClass().getName(), e.getMessage());
                }
            }
            registeredSpis.clear();
        } catch (Exception e) {
            log.error("WebP ImageIO SPI 注销过程出错", e);
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
}
