package com.timxs.storagetoolkit.service.support;

import org.springframework.dao.OptimisticLockingFailureException;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * 重试工具类
 * 统一管理乐观锁重试策略
 */
public final class RetryUtils {

    private RetryUtils() {
        // 工具类，禁止实例化
    }

    private static final int MAX_RETRIES = 5;
    private static final Duration RETRY_DELAY = Duration.ofMillis(100);

    /**
     * 创建乐观锁重试策略
     * 仅在遇到乐观锁冲突时重试，最多重试 5 次，指数退避
     *
     * @return Retry 策略
     */
    public static Retry optimisticLockRetry() {
        return Retry.backoff(MAX_RETRIES, RETRY_DELAY)
            .filter(RetryUtils::isOptimisticLockError);
    }

    /**
     * 判断异常是否为乐观锁冲突
     * 使用类型检查而非字符串匹配，与 Halo 官方实现保持一致
     *
     * @param e 异常
     * @return 是否为乐观锁错误
     */
    public static boolean isOptimisticLockError(Throwable e) {
        return e instanceof OptimisticLockingFailureException;
    }
}
