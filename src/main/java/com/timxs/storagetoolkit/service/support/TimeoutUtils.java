package com.timxs.storagetoolkit.service.support;

/**
 * 超时时间计算工具类
 * 提供统一的 HTTP 超时计算逻辑
 */
public final class TimeoutUtils {

    private TimeoutUtils() {
        // 工具类不允许实例化
    }

    /**
     * 计算 HTTP 连接超时时间（毫秒）
     * 规则：总超时的 1/3，但不超过 30 秒
     *
     * @param totalTimeoutSeconds 总超时时间（秒）
     * @return 连接超时时间（毫秒）
     */
    public static int connectTimeoutMillis(int totalTimeoutSeconds) {
        return Math.min(totalTimeoutSeconds * 1000 / 3, 30000);
    }

    /**
     * 计算 HTTP 读取超时时间（毫秒）
     * 规则：等于总超时时间
     *
     * @param totalTimeoutSeconds 总超时时间（秒）
     * @return 读取超时时间（毫秒）
     */
    public static int readTimeoutMillis(int totalTimeoutSeconds) {
        return totalTimeoutSeconds * 1000;
    }
}
