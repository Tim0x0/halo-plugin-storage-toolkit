package com.timxs.storagetoolkit.service;

import com.timxs.storagetoolkit.config.ProcessingConfig;
import reactor.core.publisher.Mono;

/**
 * 配置管理器接口
 * 从 Halo 插件设置中读取配置
 */
public interface SettingsManager {

    /**
     * 获取当前配置
     *
     * @return 处理配置对象
     */
    Mono<ProcessingConfig> getConfig();

    /**
     * 附件上传配置
     */
    record AttachmentUploadConfig(String policyName, String groupName) {
        public static AttachmentUploadConfig empty() {
            return new AttachmentUploadConfig("", "");
        }
    }

    /**
     * 获取管理端附件上传配置
     * 从 SystemSetting.Attachment.console 读取
     *
     * @return 附件上传配置
     */
    Mono<AttachmentUploadConfig> getConsoleAttachmentConfig();

    /**
     * 获取个人中心附件上传配置
     * 从 SystemSetting.Attachment.uc 读取
     *
     * @return 附件上传配置
     */
    Mono<AttachmentUploadConfig> getUcAttachmentConfig();

    /**
     * 获取重复扫描是否支持远程存储
     *
     * @return 是否启用
     */
    Mono<Boolean> getRemoteStorageForDuplicateScan();

    /**
     * 获取批量处理是否支持远程存储
     *
     * @return 是否启用
     */
    Mono<Boolean> getRemoteStorageForBatchProcessing();

    /**
     * 获取批量处理是否保留原文件
     *
     * @return 是否保留
     */
    Mono<Boolean> getKeepOriginalFile();

    /**
     * 获取批量处理下载超时时间（秒）
     *
     * @return 超时秒数
     */
    Mono<Integer> getDownloadTimeoutSeconds();

    /**
     * 分析设置
     */
    record AnalysisSettings(
        boolean scanPosts,
        boolean scanPages,
        boolean scanComments,
        boolean scanMoments,
        boolean scanPhotos,
        boolean scanDocs
    ) {
        public static AnalysisSettings defaultSettings() {
            return new AnalysisSettings(true, true, false, false, false, false);
        }
    }

    /**
     * 获取分析设置
     *
     * @return 分析设置
     */
    Mono<AnalysisSettings> getAnalysisSettings();

    /**
     * 排除设置
     */
    record ExcludeSettings(
        java.util.Set<String> excludeGroups,
        java.util.Set<String> excludePolicies,
        int scanTimeoutMinutes,
        int duplicateScanConcurrency
    ) {
        public static ExcludeSettings defaultSettings() {
            return new ExcludeSettings(java.util.Set.of(), java.util.Set.of(), 5, 4);
        }
    }

    /**
     * 获取排除和高级设置
     * 包括排除分组、排除策略、超时时间和并发数
     *
     * @return 排除设置
     */
    Mono<ExcludeSettings> getExcludeSettings();
}
