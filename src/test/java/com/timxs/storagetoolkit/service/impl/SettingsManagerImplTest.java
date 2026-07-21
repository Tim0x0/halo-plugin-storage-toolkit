package com.timxs.storagetoolkit.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.timxs.storagetoolkit.service.SettingsManager.AttachmentUploadConfig;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.SystemSetting;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * 附件上传配置两层合并解析的单元测试
 * 验证与 Halo 核心（AttachmentUcEndpoint / AttachmentConsoleEndpoint）一致的
 * system-default + system 合并语义及旧版「用户设置」回退链
 */
class SettingsManagerImplTest {

    /**
     * 与 Halo 出厂 system-default（system-configurable-configmap.yaml）一致的默认层数据
     */
    private static final String DEFAULT_ATTACHMENT_JSON = """
        {
          "console": {"policyName": "default-policy"},
          "uc": {"policyName": "default-policy"},
          "avatar": {"policyName": "default-policy"}
        }""";

    private static final String DEFAULT_USER_JSON = """
        {
          "allowRegistration": false,
          "defaultRole": "guest",
          "avatarPolicy": "default-policy",
          "ucAttachmentPolicy": "default-policy"
        }""";

    private ReactiveExtensionClient extensionClient;
    private SettingsManagerImpl settingsManager;

    @BeforeEach
    void setUp() {
        extensionClient = mock(ReactiveExtensionClient.class);
        settingsManager = new SettingsManagerImpl(mock(ReactiveSettingFetcher.class), extensionClient);
    }

    /**
     * 场景：全新站点，管理员从未保存过「附件设置」（system ConfigMap 不存在）
     * 期望：读到默认层的 default-policy —— 本次修复的目标场景
     */
    @Test
    void shouldFallBackToDefaultLayerWhenOverrideAbsent() {
        mockConfigMaps(factoryDefaultData(), null);

        assertConfig(settingsManager.getUcAttachmentConfig(), "default-policy", "");
        assertConfig(settingsManager.getConsoleAttachmentConfig(), "default-policy", "");
    }

    /**
     * 场景：管理员显式配置了策略和分组
     * 期望：覆盖层优先，读到显式配置的值（修复前后行为不变）
     */
    @Test
    void shouldPreferOverrideLayerWhenExplicitlyConfigured() {
        Map<String, String> override = Map.of("attachment", """
            {
              "console": {"policyName": "console-policy", "groupName": "group-a"},
              "uc": {"policyName": "uc-policy", "groupName": "group-b"}
            }""");
        mockConfigMaps(factoryDefaultData(), override);

        assertConfig(settingsManager.getConsoleAttachmentConfig(), "console-policy", "group-a");
        assertConfig(settingsManager.getUcAttachmentConfig(), "uc-policy", "group-b");
    }

    /**
     * 场景：覆盖层只配置了 console，未配置 uc（字段级合并）
     * 期望：console 用覆盖层，uc 回退默认层
     */
    @Test
    void shouldMergePerEndpointWhenOverridePartial() {
        Map<String, String> override = Map.of("attachment", """
            {"console": {"policyName": "console-policy"}}""");
        mockConfigMaps(factoryDefaultData(), override);

        assertConfig(settingsManager.getConsoleAttachmentConfig(), "console-policy", "");
        assertConfig(settingsManager.getUcAttachmentConfig(), "default-policy", "");
    }

    /**
     * 场景：覆盖层把策略显式清为空串（deepMerge 语义下空串覆盖默认值）
     * 期望：与核心一致 —— UC 回退旧版「用户设置」的 ucAttachmentPolicy；
     * Console 无回退返回空（此时核心端点会以 400 拒绝上传）
     */
    @Test
    void shouldTreatBlankOverrideAsUnsetAndFallBack() {
        Map<String, String> override = Map.of("attachment", """
            {
              "console": {"policyName": ""},
              "uc": {"policyName": "", "groupName": "group-ignored"}
            }""");
        mockConfigMaps(factoryDefaultData(), override);

        // UC 走 user 组回退（默认层 ucAttachmentPolicy=default-policy），分组与核心一致被丢弃
        assertConfig(settingsManager.getUcAttachmentConfig(), "default-policy", "");
        assertConfig(settingsManager.getConsoleAttachmentConfig(), "", "");
    }

    /**
     * 场景：老站点只配过旧版「用户设置 → UC 附件策略」，且默认层缺失 attachment 组
     * 期望：UC 回退读到旧版策略
     */
    @Test
    void shouldFallBackToLegacyUserPolicyForUc() {
        Map<String, String> defaults = Map.of("user", DEFAULT_USER_JSON);
        Map<String, String> override = Map.of("user", """
            {"ucAttachmentPolicy": "legacy-policy"}""");
        mockConfigMaps(defaults, override);

        assertConfig(settingsManager.getUcAttachmentConfig(), "legacy-policy", "");
        // Console 没有旧版回退
        assertConfig(settingsManager.getConsoleAttachmentConfig(), "", "");
    }

    /**
     * 场景：两个 ConfigMap 都不存在（异常环境）
     * 期望：返回空配置，不抛异常、不阻塞上传链路
     */
    @Test
    void shouldReturnEmptyWhenBothConfigMapsAbsent() {
        mockConfigMaps(null, null);

        assertConfig(settingsManager.getUcAttachmentConfig(), "", "");
        assertConfig(settingsManager.getConsoleAttachmentConfig(), "", "");
    }

    /**
     * 场景：覆盖层 attachment 组是非法 JSON
     * 期望：优雅降级读默认层，不让配置解析失败影响上传
     */
    @Test
    void shouldDegradeToDefaultLayerOnMalformedOverride() {
        Map<String, String> override = Map.of("attachment", "not-a-json{{{");
        mockConfigMaps(factoryDefaultData(), override);

        assertConfig(settingsManager.getUcAttachmentConfig(), "default-policy", "");
        assertConfig(settingsManager.getConsoleAttachmentConfig(), "default-policy", "");
    }

    /**
     * 场景：读取 ConfigMap 时底层抛异常
     * 期望：返回空配置兜底
     */
    @Test
    void shouldReturnEmptyOnFetchError() {
        when(extensionClient.fetch(eq(ConfigMap.class), eq(SystemSetting.SYSTEM_CONFIG_DEFAULT)))
            .thenReturn(Mono.error(new RuntimeException("boom")));
        when(extensionClient.fetch(eq(ConfigMap.class), eq(SystemSetting.SYSTEM_CONFIG)))
            .thenReturn(Mono.empty());

        assertConfig(settingsManager.getUcAttachmentConfig(), "", "");
    }

    private Map<String, String> factoryDefaultData() {
        Map<String, String> data = new HashMap<>();
        data.put("attachment", DEFAULT_ATTACHMENT_JSON);
        data.put("user", DEFAULT_USER_JSON);
        return data;
    }

    private void mockConfigMaps(Map<String, String> defaultData, Map<String, String> overrideData) {
        when(extensionClient.fetch(eq(ConfigMap.class), eq(SystemSetting.SYSTEM_CONFIG_DEFAULT)))
            .thenReturn(defaultData == null ? Mono.empty() : Mono.just(configMapOf(defaultData)));
        when(extensionClient.fetch(eq(ConfigMap.class), eq(SystemSetting.SYSTEM_CONFIG)))
            .thenReturn(overrideData == null ? Mono.empty() : Mono.just(configMapOf(overrideData)));
    }

    private ConfigMap configMapOf(Map<String, String> data) {
        var configMap = new ConfigMap();
        configMap.setData(new HashMap<>(data));
        return configMap;
    }

    private void assertConfig(Mono<AttachmentUploadConfig> mono, String expectedPolicy, String expectedGroup) {
        AttachmentUploadConfig config = mono.block();
        assertThat(config).isNotNull();
        assertThat(config.policyName()).isEqualTo(expectedPolicy);
        assertThat(config.groupName()).isEqualTo(expectedGroup);
    }
}
