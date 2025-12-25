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
}
