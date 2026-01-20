package com.timxs.storagetoolkit.scheduler;

import com.timxs.storagetoolkit.extension.CleanupLog;
import com.timxs.storagetoolkit.service.ProcessingLogService;
import com.timxs.storagetoolkit.service.SettingsManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 日志清理定时任务
 * 根据配置的保留天数自动清理过期日志
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class LogCleanupScheduler {

    /**
     * 处理日志服务
     */
    private final ProcessingLogService processingLogService;
    
    /**
     * 配置管理器
     */
    private final SettingsManager settingsManager;

    /**
     * Extension 客户端
     */
    private final ReactiveExtensionClient client;

    /**
     * 每天凌晨 2 点执行清理任务
     * cron 表达式：秒 分 时 日 月 周
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupExpiredLogs() {
        log.info("Starting scheduled log cleanup task");
        
        // 获取配置并执行清理
        settingsManager.getConfig()
            .flatMap(config -> {
                int retentionDays = config.getLogRetentionDays();
                log.info("Cleaning up logs older than {} days", retentionDays);
                
                // 清理 ProcessingLog 和 CleanupLog
                return Mono.when(
                    processingLogService.deleteExpired(retentionDays),
                    deleteExpiredCleanupLogs(retentionDays)
                );
            })
            .subscribe(
                v -> log.info("Log cleanup completed successfully"),
                error -> log.error("Log cleanup failed", error)
            );
    }

    /**
     * 删除过期的 CleanupLog
     */
    private Mono<Void> deleteExpiredCleanupLogs(int retentionDays) {
        Instant cutoffTime = Instant.now().minus(retentionDays, ChronoUnit.DAYS);

        return client.listAll(CleanupLog.class, ListOptions.builder().build(), Sort.unsorted())
            .filter(cleanupLog -> {
                if (cleanupLog.getSpec() == null || cleanupLog.getSpec().getDeletedAt() == null) {
                    return false;
                }
                return cleanupLog.getSpec().getDeletedAt().isBefore(cutoffTime);
            })
            .flatMap(cleanupLog -> client.delete(cleanupLog))
            .count()
            .doOnNext(count -> {
                if (count > 0) {
                    log.info("Deleted {} expired CleanupLog entries", count);
                }
            })
            .then();
    }
}
