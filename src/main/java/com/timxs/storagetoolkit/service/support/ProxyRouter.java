package com.timxs.storagetoolkit.service.support;

import com.timxs.storagetoolkit.service.SettingsManager;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 代理路由：把"代理排除主机"配置翻译成 Reactor Netty 的 nonProxyHosts 正则（NO_PROXY 语义）。
 * 直连规则完全由该配置决定（默认值为 localhost/环回，与 JVM http.nonProxyHosts 默认值一致），
 * 本站域名由用户按需加入名单。
 * <p>
 * 按 host 的直连判定交给 Reactor Netty 的 ProxyProvider 执行，本类只负责生成规则。
 */
@Component
public class ProxyRouter {

    /**
     * 构建 Reactor Netty {@code ProxyProvider.nonProxyHosts} 所需的 host 匹配正则
     * （所有出站 WebClient 共用，命中的 host 直连）
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
