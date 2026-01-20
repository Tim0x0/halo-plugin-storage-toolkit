package com.timxs.storagetoolkit.service.impl;

import com.timxs.storagetoolkit.extension.BatchProcessingStatus;
import com.timxs.storagetoolkit.extension.BrokenLinkScanStatus;
import com.timxs.storagetoolkit.extension.DuplicateScanStatus;
import com.timxs.storagetoolkit.extension.ReferenceScanStatus;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import run.halo.app.extension.ReactiveExtensionClient;

import java.time.Duration;

/**
 * 扫描状态初始化器
 * 在插件启动时检查并重置卡住的扫描状态
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScanStatusInitializer {

    private final ReactiveExtensionClient client;

    /**
     * 插件启动后执行
     * 延迟 3 秒后检查扫描状态，如果是 SCANNING 则重置为 ERROR（表示上次扫描被中断）
     */
    @PostConstruct
    public void init() {
        // 延迟执行，确保 Extension 已注册完成
        Mono.delay(Duration.ofSeconds(3))
            .then(Mono.defer(() -> {
                log.info("开始检查并重置卡住的扫描状态...");
                return resetStuckDuplicateScanStatus()
                    .then(resetStuckReferenceScanStatus())
                    .then(resetStuckBrokenLinkScanStatus())
                    .then(resetStuckBatchProcessingStatus());
            }))
            .subscribe(
                v -> log.info("扫描状态检查完成"),
                error -> log.warn("扫描状态检查失败: {}", error.getMessage())
            );
    }

    /**
     * 重置卡住的重复检测扫描状态
     */
    private Mono<Void> resetStuckDuplicateScanStatus() {
        return client.fetch(DuplicateScanStatus.class, DuplicateScanStatus.SINGLETON_NAME)
            .filter(status -> status.getStatus() != null
                && DuplicateScanStatus.Phase.SCANNING.equals(status.getStatus().getPhase()))
            .flatMap(status -> {
                log.warn("检测到重复检测扫描状态为 SCANNING，重置为 ERROR（上次扫描被中断）");
                status.getStatus().setPhase(DuplicateScanStatus.Phase.ERROR);
                status.getStatus().setErrorMessage("扫描被中断（服务重启）");
                return client.update(status);
            })
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                .filter(e -> e.getMessage() != null && e.getMessage().contains("optimistic")))
            .doOnSuccess(v -> {
                if (v != null) {
                    log.info("重复检测扫描状态已重置");
                }
            })
            .onErrorResume(error -> {
                log.debug("重置重复检测扫描状态: {}", error.getMessage());
                return Mono.empty();
            })
            .then();
    }

    /**
     * 重置卡住的引用扫描状态
     */
    private Mono<Void> resetStuckReferenceScanStatus() {
        return client.fetch(ReferenceScanStatus.class, ReferenceScanStatus.SINGLETON_NAME)
            .filter(status -> status.getStatus() != null
                && ReferenceScanStatus.Phase.SCANNING.equals(status.getStatus().getPhase()))
            .flatMap(status -> {
                log.warn("检测到引用扫描状态为 SCANNING，重置为 ERROR（上次扫描被中断）");
                status.getStatus().setPhase(ReferenceScanStatus.Phase.ERROR);
                status.getStatus().setErrorMessage("扫描被中断（服务重启）");
                return client.update(status);
            })
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                .filter(e -> e.getMessage() != null && e.getMessage().contains("optimistic")))
            .doOnSuccess(v -> {
                if (v != null) {
                    log.info("引用扫描状态已重置");
                }
            })
            .onErrorResume(error -> {
                log.debug("重置引用扫描状态: {}", error.getMessage());
                return Mono.empty();
            })
            .then();
    }

    /**
     * 重置卡住的断链扫描状态
     */
    private Mono<Void> resetStuckBrokenLinkScanStatus() {
        return client.fetch(BrokenLinkScanStatus.class, BrokenLinkScanStatus.SINGLETON_NAME)
            .filter(status -> status.getStatus() != null
                && BrokenLinkScanStatus.Phase.SCANNING.equals(status.getStatus().getPhase()))
            .flatMap(status -> {
                log.warn("检测到断链扫描状态为 SCANNING，重置为 ERROR（上次扫描被中断）");
                status.getStatus().setPhase(BrokenLinkScanStatus.Phase.ERROR);
                status.getStatus().setErrorMessage("扫描被中断（服务重启）");
                return client.update(status);
            })
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                .filter(e -> e.getMessage() != null && e.getMessage().contains("optimistic")))
            .doOnSuccess(v -> {
                if (v != null) {
                    log.info("断链扫描状态已重置");
                }
            })
            .onErrorResume(error -> {
                log.debug("重置断链扫描状态: {}", error.getMessage());
                return Mono.empty();
            })
            .then();
    }

    /**
     * 重置卡住的批量处理状态
     */
    private Mono<Void> resetStuckBatchProcessingStatus() {
        return client.fetch(BatchProcessingStatus.class, BatchProcessingStatus.SINGLETON_NAME)
            .filter(status -> status.getStatus() != null
                && BatchProcessingStatus.Phase.PROCESSING.equals(status.getStatus().getPhase()))
            .flatMap(status -> {
                log.warn("检测到批量处理状态为 PROCESSING，重置为 ERROR（上次处理被中断）");
                status.getStatus().setPhase(BatchProcessingStatus.Phase.ERROR);
                status.getStatus().setErrorMessage("处理被中断（服务重启）");
                return client.update(status);
            })
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                .filter(e -> e.getMessage() != null && e.getMessage().contains("optimistic")))
            .doOnSuccess(v -> {
                if (v != null) {
                    log.info("批量处理状态已重置");
                }
            })
            .onErrorResume(error -> {
                log.debug("重置批量处理状态: {}", error.getMessage());
                return Mono.empty();
            })
            .then();
    }
}
