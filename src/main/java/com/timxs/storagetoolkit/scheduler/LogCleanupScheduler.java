package com.timxs.storagetoolkit.scheduler;

import com.timxs.storagetoolkit.service.ProcessingLogService;
import com.timxs.storagetoolkit.service.SettingsManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
                return processingLogService.deleteExpired(retentionDays);
            })
            .subscribe(
                v -> log.info("Log cleanup completed successfully"),
                error -> log.error("Log cleanup failed", error)
            );
    }
}
