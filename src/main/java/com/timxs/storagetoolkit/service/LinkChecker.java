package com.timxs.storagetoolkit.service;

import reactor.core.publisher.Mono;

/**
 * 链接检查服务接口
 */
public interface LinkChecker {

    /**
     * 链接检测结果
     */
    record CheckResult(
        /** 是否有效 */
        boolean isValid,
        /** 断链原因（isValid 为 false 时有值）
         *  HTTP 错误格式: "HTTP 403", "HTTP 404", "HTTP 405" 等
         *  其他错误: "HTTP_TIMEOUT", "CONNECTION_FAILED", "ATTACHMENT_NOT_FOUND"
         */
        String reason,
        /** HTTP 状态码（如果有） */
        Integer statusCode
    ) {
        /** 创建有效结果 */
        public static CheckResult valid() {
            return new CheckResult(true, "NONE", null);
        }

        /** 创建无效结果 */
        public static CheckResult invalid(String reason) {
            return new CheckResult(false, reason, null);
        }

        /** 创建无效结果（带状态码） */
        public static CheckResult invalid(String reason, int statusCode) {
            return new CheckResult(false, reason, statusCode);
        }
    }

    /**
     * 检查链接是否有效（不使用代理）
     *
     * @param url            链接地址
     * @param userAgent      User-Agent 头
     * @param timeoutSeconds 超时时间（秒）
     * @return 检测结果
     */
    default Mono<CheckResult> check(String url, String userAgent, int timeoutSeconds) {
        return check(url, userAgent, timeoutSeconds, SettingsManager.ProxySettings.disabled());
    }

    /**
     * 检查链接是否有效
     *
     * @param url            链接地址
     * @param userAgent      User-Agent 头
     * @param timeoutSeconds 超时时间（秒）
     * @param proxySettings  代理设置
     * @return 检测结果
     */
    Mono<CheckResult> check(String url, String userAgent, int timeoutSeconds,
                            SettingsManager.ProxySettings proxySettings);
}
