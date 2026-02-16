package com.timxs.storagetoolkit.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.timxs.storagetoolkit.config.*;
import com.timxs.storagetoolkit.model.FontSizeMode;
import com.timxs.storagetoolkit.model.ImageFormat;
import com.timxs.storagetoolkit.model.WatermarkPosition;
import com.timxs.storagetoolkit.model.WatermarkType;
import com.timxs.storagetoolkit.service.SettingsManager;
import static com.timxs.storagetoolkit.service.SettingsManager.AttachmentUploadConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 配置管理器实现
 * 从 Halo 插件设置中读取配置，转换为 ProcessingConfig 对象
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsManagerImpl implements SettingsManager {

    /**
     * Halo 响应式设置获取器，用于读取插件设置
     */
    private final ReactiveSettingFetcher settingFetcher;
    
    /**
     * Halo 响应式扩展客户端，用于读取系统配置
     */
    private final ReactiveExtensionClient extensionClient;
    
    /**
     * 系统配置 ConfigMap 名称
     */
    private static final String SYSTEM_CONFIG_NAME = "system";
    
    /**
     * JSON 解析器
     */
    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER = run.halo.app.infra.utils.JsonUtils.mapper();

    /**
     * 获取当前配置
     * 合并全局设置、图片处理设置和日志设置
     *
     * @return 完整的处理配置
     */
    @Override
    public Mono<ProcessingConfig> getConfig() {
        return buildConfig();
    }

    /**
     * 构建配置对象
     * 并行读取各组设置，合并到一个 ProcessingConfig 对象中
     *
     * @return 配置对象
     */
    private Mono<ProcessingConfig> buildConfig() {
        ProcessingConfig config = new ProcessingConfig();
        
        // 并行读取三组设置
        return Mono.zip(
            getGlobalSettings(config),
            getImageProcessingSettings(config),
            getLogSettings(config)
        ).thenReturn(config)
        .onErrorResume(e -> {
            log.warn("Failed to load settings, using defaults: {}", e.getMessage());
            return Mono.just(new ProcessingConfig());
        });
    }

    /**
     * 从 global 组读取全局设置
     * 设置项嵌套在 basic 子组下
     *
     * @param config 配置对象（会被修改）
     * @return 完成信号
     */
    private Mono<Boolean> getGlobalSettings(ProcessingConfig config) {
        return settingFetcher.get("basic")
            .doOnNext(setting -> {
                JsonNode basic = setting.get("basic");
                if (basic != null) {
                    config.setEnabled(getBoolean(basic, "imageProcessingEnabled", false));
                    config.setProcessEditorImages(getBoolean(basic, "processEditorImages", false));
                    List<String> policies = getStringList(basic, "targetPolicies");
                    if (policies != null && !policies.isEmpty()) {
                        config.setTargetPolicies(policies);
                    }
                    List<String> groups = getStringList(basic, "targetGroups");
                    if (groups != null && !groups.isEmpty()) {
                        config.setTargetGroups(groups);
                    }
                    // 图片处理并发数
                    int concurrency = getInt(basic, "imageProcessingConcurrency", 3);
                    config.setImageProcessingConcurrency(Math.max(1, Math.min(10, concurrency)));
                    // 下载超时时间
                    int timeout = getInt(basic, "downloadTimeoutSeconds", 90);
                    config.setDownloadTimeoutSeconds(Math.max(30, Math.min(300, timeout)));
                }
            })
            .thenReturn(true)
            .onErrorReturn(true);
    }

    /**
     * 从 imageProcessing 组读取图片处理设置
     * 包含文件过滤、格式转换、水印三个子组
     *
     * @param config 配置对象（会被修改）
     * @return 完成信号
     */
    private Mono<Boolean> getImageProcessingSettings(ProcessingConfig config) {
        return settingFetcher.get("imageProcessing")
            .doOnNext(setting -> {
                // 文件过滤（嵌套在 fileFilter 下）
                JsonNode fileFilter = setting.get("fileFilter");
                if (fileFilter != null) {
                    List<String> formats = getStringList(fileFilter, "allowedFormats");
                    // 无论是否为空都设置，空列表表示不处理任何图片
                    config.setAllowedFormats(formats != null ? formats : List.of());
                    // 文件大小单位是 KB，需要转换为字节
                    long minSize = getLong(fileFilter, "minFileSize", 0) * 1024;
                    long maxSize = getLong(fileFilter, "maxFileSize", 0) * 1024;
                    config.setMinFileSize(minSize);
                    config.setMaxFileSize(maxSize);
                    log.debug("文件大小过滤配置 - minFileSize: {} KB, maxFileSize: {} KB", minSize / 1024, maxSize / 1024);
                }
                
                // 格式转换（嵌套在 formatConversion 下）
                JsonNode formatNode = setting.get("formatConversion");
                FormatConversionConfig format = config.getFormatConversion();
                if (formatNode != null) {
                    format.setEnabled(getBoolean(formatNode, "enabled", false));
                    String formatStr = getString(formatNode, "targetFormat", "WEBP");
                    try {
                        format.setTargetFormat(ImageFormat.valueOf(formatStr));
                    } catch (IllegalArgumentException e) {
                        format.setTargetFormat(ImageFormat.WEBP);
                    }
                    format.setOutputQuality(getInt(formatNode, "outputQuality", 75));
                    
                    // 智能跳过配置
                    format.setSkipIfLarger(getBoolean(formatNode, "skipIfLarger", true));
                    // 跳过阈值（0-50%）
                    int threshold = getInt(formatNode, "skipThreshold", 0);
                    format.setSkipThreshold(Math.max(0, Math.min(50, threshold)));
                    
                    // 压缩等级配置
                    int webpEffort = getInt(formatNode, "webpEffort", 4);
                    format.setWebpEffort(Math.max(0, Math.min(6, webpEffort)));
                    int avifEffort = getInt(formatNode, "avifEffort", 4);
                    format.setAvifEffort(Math.max(0, Math.min(10, avifEffort)));
                }
                
                // 水印设置（嵌套在 watermark 下）
                JsonNode watermarkNode = setting.get("watermark");
                WatermarkConfig watermark = config.getWatermark();
                if (watermarkNode != null) {
                    watermark.setEnabled(getBoolean(watermarkNode, "enabled", false));
                    
                    String typeStr = getString(watermarkNode, "type", "TEXT");
                    log.debug("读取水印配置 - enabled: {}, type: {}", watermark.isEnabled(), typeStr);
                    try {
                        watermark.setType(WatermarkType.valueOf(typeStr));
                    } catch (IllegalArgumentException e) {
                        watermark.setType(WatermarkType.TEXT);
                    }
                    
                    String watermarkText = getString(watermarkNode, "text", "");
                    String watermarkImageUrl = getString(watermarkNode, "imageUrl", "");
                    log.debug("读取水印配置 - text: '{}', imageUrl: '{}'", watermarkText, watermarkImageUrl);
                    
                    watermark.setText(watermarkText);
                    watermark.setImageUrl(watermarkImageUrl);
                    // imageScale 是百分比（1-100），需要转换为小数（0.01-1.0）
                    watermark.setImageScale(getDouble(watermarkNode, "imageScale", 20) / 100.0);
                    
                    String posStr = getString(watermarkNode, "position", "BOTTOM_RIGHT");
                    try {
                        watermark.setPosition(WatermarkPosition.valueOf(posStr));
                    } catch (IllegalArgumentException e) {
                        watermark.setPosition(WatermarkPosition.BOTTOM_RIGHT);
                    }
                    
                    watermark.setOpacity(getInt(watermarkNode, "opacity", 50));
                    watermark.setFontSize(getInt(watermarkNode, "fontSize", 25));
                    watermark.setColor(getString(watermarkNode, "color", "#b4b4b4"));
                    // 字体名称：空字符串表示使用内置字体
                    String fontName = getString(watermarkNode, "fontName", "");
                    watermark.setFontName(fontName);
                    log.debug("读取水印配置 - fontName: '{}'", fontName);
                    
                    // 字体大小模式（FIXED 或 ADAPTIVE）
                    String fontSizeModeStr = getString(watermarkNode, "fontSizeMode", "FIXED");
                    try {
                        watermark.setFontSizeMode(FontSizeMode.valueOf(fontSizeModeStr));
                    } catch (IllegalArgumentException e) {
                        watermark.setFontSizeMode(FontSizeMode.FIXED);
                    }
                    // 字体缩放比例（1-10%）
                    int fontScale = getInt(watermarkNode, "fontScale", 4);
                    watermark.setFontScale(Math.max(1, Math.min(10, fontScale)));
                    
                    // 边距是百分比（0-50）
                    watermark.setMarginX(getDouble(watermarkNode, "marginX", 5));
                    watermark.setMarginY(getDouble(watermarkNode, "marginY", 5));
                }
            })
            .thenReturn(true)
            .onErrorReturn(true);
    }

    /**
     * 从 log 组读取日志设置
     *
     * @param config 配置对象（会被修改）
     * @return 完成信号
     */
    private Mono<Boolean> getLogSettings(ProcessingConfig config) {
        return settingFetcher.get("log")
            .doOnNext(setting -> {
                int days = getInt(setting, "logRetentionDays", 30);
                config.setLogRetentionDays(Math.max(1, Math.min(30, days)));
            })
            .thenReturn(true)
            .onErrorReturn(true);
    }

    /**
     * 获取管理端附件上传配置
     * 从 SystemSetting.Attachment.console 读取
     *
     * @return 附件上传配置
     */
    @Override
    public Mono<AttachmentUploadConfig> getConsoleAttachmentConfig() {
        return getAttachmentConfig("console");
    }

    /**
     * 获取个人中心附件上传配置
     * 从 SystemSetting.Attachment.uc 读取
     *
     * @return 附件上传配置
     */
    @Override
    public Mono<AttachmentUploadConfig> getUcAttachmentConfig() {
        return getAttachmentConfig("uc");
    }

    /**
     * 从系统配置读取附件上传配置
     *
     * @param configKey 配置键（console/uc/comment/avatar）
     * @return 附件上传配置
     */
    private Mono<AttachmentUploadConfig> getAttachmentConfig(String configKey) {
        return extensionClient.fetch(ConfigMap.class, SYSTEM_CONFIG_NAME)
            .map(configMap -> {
                Map<String, String> data = configMap.getData();
                if (data == null) {
                    return AttachmentUploadConfig.empty();
                }
                // 读取 attachment 组的配置
                String attachmentConfig = data.get("attachment");
                if (attachmentConfig == null || attachmentConfig.isBlank()) {
                    return AttachmentUploadConfig.empty();
                }
                // 解析 JSON
                try {
                    JsonNode attachmentNode = OBJECT_MAPPER.readTree(attachmentConfig);
                    JsonNode configNode = attachmentNode.get(configKey);
                    if (configNode != null) {
                        String policyName = "";
                        String groupName = "";
                        JsonNode policyNode = configNode.get("policyName");
                        if (policyNode != null && policyNode.isTextual()) {
                            policyName = policyNode.asText();
                        }
                        JsonNode groupNode = configNode.get("groupName");
                        if (groupNode != null && groupNode.isTextual()) {
                            groupName = groupNode.asText();
                        }
                        return new AttachmentUploadConfig(policyName, groupName);
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse attachment config: {}", e.getMessage());
                }
                return AttachmentUploadConfig.empty();
            })
            .defaultIfEmpty(AttachmentUploadConfig.empty());
    }

    /**
     * 获取重复扫描是否支持远程存储
     */
    @Override
    public Mono<Boolean> getRemoteStorageForDuplicateScan() {
        return settingFetcher.get("analysis")
            .map(setting -> {
                JsonNode duplicateScanning = setting.get("duplicateScanning");
                if (duplicateScanning != null) {
                    return getBoolean(duplicateScanning, "enableRemoteStorageForDuplicateScan", false);
                }
                return false;
            })
            .defaultIfEmpty(false)
            .onErrorReturn(false);
    }

    /**
     * 获取批量处理是否支持远程存储
     */
    @Override
    public Mono<Boolean> getRemoteStorageForBatchProcessing() {
        return settingFetcher.get("batchProcessing")
            .map(setting -> getBoolean(setting, "enableRemoteStorageForBatchProcessing", false))
            .defaultIfEmpty(false)
            .onErrorReturn(false);
    }

    /**
     * 获取批量处理是否保留原文件
     */
    @Override
    public Mono<Boolean> getKeepOriginalFile() {
        return settingFetcher.get("batchProcessing")
            .map(setting -> getBoolean(setting, "keepOriginalFile", false))
            .defaultIfEmpty(false)
            .onErrorReturn(false);
    }

    /**
     * 获取下载超时时间（秒）
     * 从基础设置 -> 图片处理组读取
     */
    @Override
    public Mono<Integer> getDownloadTimeoutSeconds() {
        return settingFetcher.get("basic")
            .map(setting -> {
                JsonNode basic = setting.get("basic");
                if (basic != null) {
                    int timeout = getInt(basic, "downloadTimeoutSeconds", 90);
                    return Math.max(30, Math.min(300, timeout));
                }
                return 90;
            })
            .defaultIfEmpty(90)
            .onErrorReturn(90);
    }

    @Override
    public Mono<AnalysisSettings> getAnalysisSettings() {
        return settingFetcher.get("analysis")
            .map(setting -> {
                JsonNode refScanning = setting.get("referenceScanning");
                if (refScanning != null) {
                    return new AnalysisSettings(
                        getBoolean(refScanning, "scanPosts", true),
                        getBoolean(refScanning, "scanPages", true),
                        getBoolean(refScanning, "scanComments", false),
                        getBoolean(refScanning, "scanMoments", false),
                        getBoolean(refScanning, "scanPhotos", false),
                        getBoolean(refScanning, "scanDocs", false)
                    );
                }
                return AnalysisSettings.defaultSettings();
            })
            .defaultIfEmpty(AnalysisSettings.defaultSettings())
            .onErrorReturn(AnalysisSettings.defaultSettings());
    }

    @Override
    public Mono<ExcludeSettings> getExcludeSettings() {
        // 并行读取 basic.analysisExclude 和 basic.duplicateScanning
        return Mono.zip(
            getBasicExcludeSettings(),
            getBasicDuplicateScanSettings()
        ).map(tuple -> {
            ExcludeSettings exclude = tuple.getT1();
            ExcludeSettings duplicate = tuple.getT2();
            return new ExcludeSettings(
                exclude.excludeGroups(),
                exclude.excludePolicies(),
                duplicate.md5TimeoutSeconds(),
                duplicate.duplicateScanConcurrency()
            );
        })
        .defaultIfEmpty(ExcludeSettings.defaultSettings())
        .onErrorReturn(ExcludeSettings.defaultSettings());
    }

    /**
     * 从 basic 组读取排除设置
     */
    private Mono<ExcludeSettings> getBasicExcludeSettings() {
        return settingFetcher.get("basic")
            .map(setting -> {
                java.util.Set<String> excludeGroups = new java.util.HashSet<>();
                java.util.Set<String> excludePolicies = new java.util.HashSet<>();

                JsonNode analysisExclude = setting.get("analysisExclude");
                if (analysisExclude != null) {
                    List<String> groups = getStringList(analysisExclude, "excludeGroups");
                    if (groups != null) excludeGroups.addAll(groups);

                    List<String> policies = getStringList(analysisExclude, "excludePolicies");
                    if (policies != null) excludePolicies.addAll(policies);
                }
                return new ExcludeSettings(excludeGroups, excludePolicies, 90, 4);
            })
            .defaultIfEmpty(new ExcludeSettings(java.util.Set.of(), java.util.Set.of(), 90, 4))
            .onErrorReturn(new ExcludeSettings(java.util.Set.of(), java.util.Set.of(), 90, 4));
    }

    /**
     * 从 basic 组读取重复检测设置
     */
    private Mono<ExcludeSettings> getBasicDuplicateScanSettings() {
        return settingFetcher.get("basic")
            .map(setting -> {
                int md5TimeoutSeconds = 90;
                int duplicateScanConcurrency = 4;

                JsonNode duplicateScanning = setting.get("duplicateScanning");
                if (duplicateScanning != null) {
                    int timeout = getInt(duplicateScanning, "md5TimeoutSeconds", 90);
                    md5TimeoutSeconds = Math.max(30, Math.min(300, timeout));

                    int concurrency = getInt(duplicateScanning, "duplicateScanConcurrency", 4);
                    duplicateScanConcurrency = Math.max(1, Math.min(10, concurrency));
                }

                return new ExcludeSettings(java.util.Set.of(), java.util.Set.of(), md5TimeoutSeconds, duplicateScanConcurrency);
            })
            .defaultIfEmpty(new ExcludeSettings(java.util.Set.of(), java.util.Set.of(), 90, 4))
            .onErrorReturn(new ExcludeSettings(java.util.Set.of(), java.util.Set.of(), 90, 4));
    }

    @Override
    public Mono<BrokenLinkSettings> getBrokenLinkSettings() {
        // 并行读取 basic 和 analysis 中的 brokenLink 配置
        return Mono.zip(
            getBasicBrokenLinkSettings(),
            getAnalysisBrokenLinkSettings()
        ).map(tuple -> {
            BrokenLinkSettings basic = tuple.getT1();
            BrokenLinkSettings analysis = tuple.getT2();
            // 合并两个配置
            return new BrokenLinkSettings(
                analysis.checkExternalLinks(),
                analysis.attachmentUrlPrefixes(),
                basic.checkTimeout(),
                basic.checkConcurrency()
            );
        })
        .defaultIfEmpty(BrokenLinkSettings.defaultSettings())
        .onErrorReturn(BrokenLinkSettings.defaultSettings());
    }

    @Override
    public Mono<ProxySettings> getProxySettings() {
        return settingFetcher.get("proxy")
            .map(setting -> {
                boolean enabled = getBoolean(setting, "proxyEnabled", false);
                String host = getString(setting, "proxyHost", "");
                int port = getInt(setting, "proxyPort", 7890);
                port = Math.max(1, Math.min(65535, port));
                return new ProxySettings(enabled, host, port);
            })
            .defaultIfEmpty(ProxySettings.disabled())
            .onErrorReturn(ProxySettings.disabled());
    }

    /**
     * 从 basic 组读取断链检测基础配置
     */
    private Mono<BrokenLinkSettings> getBasicBrokenLinkSettings() {
        return settingFetcher.get("basic")
            .map(setting -> {
                JsonNode brokenLink = setting.get("brokenLink");
                int checkTimeout = 5;
                int checkConcurrency = 10;

                if (brokenLink != null) {
                    checkTimeout = getInt(brokenLink, "checkTimeout", 5);
                    checkTimeout = Math.max(1, Math.min(30, checkTimeout));
                    checkConcurrency = getInt(brokenLink, "checkConcurrency", 10);
                    checkConcurrency = Math.max(1, Math.min(20, checkConcurrency));
                }
                return new BrokenLinkSettings(
                    true,           // checkExternalLinks 占位
                    List.of(),      // attachmentUrlPrefixes 占位
                    checkTimeout,
                    checkConcurrency
                );
            })
            .defaultIfEmpty(new BrokenLinkSettings(
                true, List.of(), 5, 10
            ))
            .onErrorReturn(new BrokenLinkSettings(
                true, List.of(), 5, 10
            ));
    }

    /**
     * 从 analysis 组读取断链检测业务配置
     */
    private Mono<BrokenLinkSettings> getAnalysisBrokenLinkSettings() {
        return settingFetcher.get("analysis")
            .map(setting -> {
                JsonNode brokenLink = setting.get("brokenLink");
                boolean checkExternalLinks = true;
                List<String> attachmentUrlPrefixes = List.of("/upload/");

                if (brokenLink != null) {
                    checkExternalLinks = getBoolean(brokenLink, "checkExternalLinks", true);
                    // textarea 返回的是多行文本，按行分割
                    String prefixesText = getString(brokenLink, "attachmentUrlPrefixes", "");
                    if (StringUtils.hasText(prefixesText)) {
                        List<String> prefixes = Arrays.stream(prefixesText.split("\\r?\\n"))
                            .map(String::trim)
                            .filter(StringUtils::hasText)
                            .toList();
                        if (!prefixes.isEmpty()) {
                            attachmentUrlPrefixes = prefixes;
                        }
                    }
                }
                return new BrokenLinkSettings(
                    checkExternalLinks,
                    attachmentUrlPrefixes,
                    5,      // checkTimeout 占位
                    10      // checkConcurrency 占位
                );
            })
            .defaultIfEmpty(new BrokenLinkSettings(
                true, List.of("/upload/"), 5, 10
            ))
            .onErrorReturn(new BrokenLinkSettings(
                true, List.of("/upload/"), 5, 10
            ));
    }

    // ========== JsonNode 辅助方法 ==========

    /**
     * 从 JsonNode 获取布尔值
     */
    private boolean getBoolean(JsonNode node, String key, boolean defaultValue) {
        JsonNode value = node.get(key);
        if (value != null && value.isBoolean()) {
            return value.asBoolean();
        }
        return defaultValue;
    }

    /**
     * 从 JsonNode 获取整数值
     * 支持数字类型和字符串类型
     */
    private int getInt(JsonNode node, String key, int defaultValue) {
        JsonNode value = node.get(key);
        if (value != null) {
            if (value.isNumber()) {
                return value.asInt();
            }
            if (value.isTextual()) {
                try {
                    return Integer.parseInt(value.asText());
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }

    /**
     * 从 JsonNode 获取长整数值
     * 支持数字类型和字符串类型
     */
    private long getLong(JsonNode node, String key, long defaultValue) {
        JsonNode value = node.get(key);
        if (value != null) {
            if (value.isNumber()) {
                return value.asLong();
            }
            if (value.isTextual()) {
                try {
                    return Long.parseLong(value.asText());
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }

    /**
     * 从 JsonNode 获取双精度浮点值
     * 支持数字类型和字符串类型
     */
    private double getDouble(JsonNode node, String key, double defaultValue) {
        JsonNode value = node.get(key);
        if (value != null) {
            if (value.isNumber()) {
                return value.asDouble();
            }
            if (value.isTextual()) {
                try {
                    return Double.parseDouble(value.asText());
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }

    /**
     * 从 JsonNode 获取字符串值
     */
    private String getString(JsonNode node, String key, String defaultValue) {
        JsonNode value = node.get(key);
        if (value != null && value.isTextual()) {
            return value.asText();
        }
        return defaultValue;
    }

    /**
     * 从 JsonNode 获取字符串列表
     * 支持数组类型和逗号分隔的字符串类型
     *
     * @return 字符串列表，如果为空则返回 null
     */
    private List<String> getStringList(JsonNode node, String key) {
        return getStringList(node, key, null);
    }

    /**
     * 从 JsonNode 获取字符串列表
     * 支持数组类型、逗号分隔的字符串类型、以及 repeater 返回的对象数组
     *
     * @param fieldName 对象数组中要提取的字段名（如 repeater 的子字段名）
     * @return 字符串列表，如果为空则返回 null
     */
    private List<String> getStringList(JsonNode node, String key, String fieldName) {
        JsonNode value = node.get(key);
        if (value != null) {
            List<String> list = new ArrayList<>();
            if (value.isArray()) {
                // 数组类型
                value.forEach(item -> {
                    if (item.isTextual()) {
                        // 简单字符串数组
                        list.add(item.asText().trim());
                    } else if (item.isObject() && fieldName != null) {
                        // repeater 返回的对象数组，提取指定字段
                        JsonNode fieldValue = item.get(fieldName);
                        if (fieldValue != null && fieldValue.isTextual()) {
                            String text = fieldValue.asText().trim();
                            if (!text.isEmpty()) {
                                list.add(text);
                            }
                        }
                    }
                });
            } else if (value.isTextual()) {
                // 逗号分隔的字符串类型
                String text = value.asText();
                if (text != null && !text.isEmpty()) {
                    for (String item : text.split(",")) {
                        String trimmed = item.trim();
                        if (!trimmed.isEmpty()) {
                            list.add(trimmed);
                        }
                    }
                }
            }
            return list.isEmpty() ? null : list;
        }
        return null;
    }
}
