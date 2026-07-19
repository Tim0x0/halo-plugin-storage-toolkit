package com.timxs.storagetoolkit.service.support;

import com.timxs.storagetoolkit.service.SettingsManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 代理路由：按目标 host 决定"直连"还是"走代理"（NO_PROXY 语义）。
 * 直连规则完全由"代理排除主机"配置决定（默认值为 localhost/环回，
 * 与 JVM http.nonProxyHosts 默认值一致），本站域名由用户按需加入名单。
 */
@Slf4j
@Component
public class ProxyRouter {

    /**
     * 判断给定完整 URL 是否应绕过代理直连
     *
     * @param fullUrl       完整 URL
     * @param excludedHosts 用户配置的排除主机（可含 * 通配）
     */
    public boolean shouldBypass(String fullUrl, List<String> excludedHosts) {
        String host = extractHost(fullUrl);
        if (!StringUtils.hasText(host)) {
            // 解析不出 host（相对路径等），按本站处理，直连
            return true;
        }
        return Pattern.compile(buildBypassRegex(excludedHosts), Pattern.CASE_INSENSITIVE)
            .matcher(host)
            .matches();
    }

    /**
     * 为附件下载选择代理
     *
     * @return java.net.Proxy，直连时返回 Proxy.NO_PROXY
     */
    public Proxy selectForDownload(String fullUrl, SettingsManager.ProxySettings settings) {
        if (settings == null || !settings.isEffective()) {
            return Proxy.NO_PROXY;
        }
        if (shouldBypass(fullUrl, settings.excludedHosts())) {
            return Proxy.NO_PROXY;
        }
        log.debug("附件下载走代理: {} -> {}:{}", fullUrl, settings.host(), settings.port());
        return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(settings.host(), settings.port()));
    }

    /**
     * 构建 Reactor Netty ProxyProvider.nonProxyHosts 所需的 host 匹配正则
     * （断链检测的 WebClient 用，命中的 host 直连）
     */
    public String nonProxyHostsRegex(SettingsManager.ProxySettings settings) {
        List<String> excluded = settings == null ? List.of() : settings.excludedHosts();
        return buildBypassRegex(excluded);
    }

    /**
     * 拼装直连正则：完全来自用户排除名单
     */
    private String buildBypassRegex(List<String> excludedHosts) {
        List<String> parts = new ArrayList<>();

        if (excludedHosts != null) {
            excludedHosts.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(ProxyRouter::wildcardToRegex)
                .forEach(parts::add);
        }

        if (parts.isEmpty()) {
            // 空规则：匹配不到任何 host（避免空正则匹配一切）
            return "(?!)";
        }
        return "(" + String.join("|", parts) + ")";
    }

    /**
     * 从 URL 中提取 host（解析失败返回 null）
     */
    private static String extractHost(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        try {
            return URI.create(url.trim()).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 通配模式转正则：* 匹配任意字符，其余按字面量处理
     */
    private static String wildcardToRegex(String pattern) {
        String[] segments = pattern.split("\\*", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append(".*");
            }
            if (!segments[i].isEmpty()) {
                sb.append(Pattern.quote(segments[i]));
            }
        }
        return sb.toString();
    }
}
