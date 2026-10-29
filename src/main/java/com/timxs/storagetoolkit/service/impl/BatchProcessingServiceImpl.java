package com.timxs.storagetoolkit.service.impl;

import com.timxs.storagetoolkit.endpoint.BatchProcessingEndpoint.SettingsResponse;
import com.timxs.storagetoolkit.extension.BatchProcessingStatus;
import com.timxs.storagetoolkit.extension.BatchProcessingStatus.BatchProcessingStatusSpec;
import com.timxs.storagetoolkit.extension.BatchProcessingStatus.BatchProcessingStatusStatus;
import com.timxs.storagetoolkit.extension.BatchProcessingStatus.FailedItem;
import com.timxs.storagetoolkit.extension.BatchProcessingStatus.Phase;
import com.timxs.storagetoolkit.extension.BatchProcessingStatus.Progress;
import com.timxs.storagetoolkit.service.BatchProcessingService;
import com.timxs.storagetoolkit.service.ImageProcessor;
import com.timxs.storagetoolkit.service.SettingsManager;
import com.timxs.storagetoolkit.service.batch.BatchAttachmentProcessor;
import com.timxs.storagetoolkit.storage.BatchTaskStore;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Manages batch task creation, lifecycle and recovery; attachment work is delegated explicitly. */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchProcessingServiceImpl implements BatchProcessingService {
    private final SettingsManager settings;
    private final ImageProcessor images;
    private final BatchAttachmentProcessor processor;
    private final BatchTaskStore tasks;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private final AtomicReference<Sinks.One<Object>> cancelSink = new AtomicReference<>();
    private volatile String activeTaskId;

    @Override
    public Mono<BatchProcessingStatus> createTask(List<String> names, boolean replaceReferences) {
        return currentOptions(replaceReferences)
                .flatMap(options -> createTask(names, options, null));
    }

    private Mono<BatchProcessingStatusSpec> currentOptions(boolean replaceReferences) {
        return Mono.zip(
                        settings.getConfig(),
                        settings.getKeepOriginalFile(),
                        settings.getRemoteStorageForBatchProcessing())
                .map(
                        tuple -> {
                            var spec = new BatchProcessingStatusSpec();
                            spec.setConfig(tuple.getT1());
                            spec.setKeepOriginal(tuple.getT2());
                            spec.setEnableRemoteStorage(tuple.getT3());
                            spec.setReplaceReferences(replaceReferences);
                            return spec;
                        });
    }

    private Mono<BatchProcessingStatus> createTask(
            List<String> names, BatchProcessingStatusSpec options, String parent) {
        if (names == null
                || names.isEmpty()
                || names.size() > 2000
                || names.stream().anyMatch(n -> n == null || n.isBlank())) {
            return Mono.error(new IllegalArgumentException("请选择 1 至 2000 个有效附件"));
        }
        if (!images.hasProcessingEnabled(options.getConfig())) {
            return Mono.error(new IllegalStateException("没有启用任何处理功能（水印或格式转换）"));
        }
        var task = new BatchProcessingStatus();
        task.setMetadata(new Metadata());
        task.getMetadata().setName(UUID.randomUUID().toString());
        task.getMetadata().setCreationTimestamp(Instant.now());
        options.setAttachmentNames(new ArrayList<>(names.stream().distinct().toList()));
        options.setParentTaskId(parent);
        task.setSpec(options);
        var state = new BatchProcessingStatusStatus();
        state.setPhase(Phase.PENDING);
        var progress = new Progress();
        progress.setTotal(options.getAttachmentNames().size());
        state.setProgress(progress);
        state.setFailedItems(new ArrayList<>());
        state.setSkippedItems(new ArrayList<>());
        state.setStartTime(Instant.now());
        task.setStatus(state);
        return launch(task);
    }

    private Mono<BatchProcessingStatus> launch(BatchProcessingStatus task) {
        return ReactiveSecurityContextHolder.getContext()
                .switchIfEmpty(Mono.error(new IllegalStateException("缺少登录上下文，无法执行附件操作")))
                .flatMap(security -> startWithContext(task, security))
                // Once accepted, a closed HTTP request must not cancel persistence or release the
                // running lock.
                .cache();
    }

    private Mono<BatchProcessingStatus> startWithContext(
            BatchProcessingStatus task, SecurityContext security) {
        return Mono.defer(
                () -> {
                    if (stopping.get())
                        return Mono.error(new IllegalStateException("插件正在停止，请稍后再试"));
                    if (!running.compareAndSet(false, true))
                        return Mono.error(new IllegalStateException("已有任务正在执行中"));
                    cancelRequested.set(false);
                    cancelSink.set(Sinks.one());
                    activeTaskId = task.getMetadata().getName();
                    task.getStatus().setPhase(Phase.PENDING);
                    task.getStatus().setEndTime(null);
                    task.getStatus().setErrorMessage(null);
                    var launched = new AtomicBoolean();
                    return tasks.save(task)
                            .doOnSuccess(
                                    saved -> {
                                        launched.set(true);
                                        subscribeToExecution(saved, security);
                                    })
                            .doFinally(
                                    signal -> {
                                        if (!launched.get()) running.set(false);
                                    });
                });
    }

    private void subscribeToExecution(BatchProcessingStatus task, SecurityContext security) {
        String id = task.getMetadata().getName();
        executeTask(task, security)
                .onErrorResume(
                        error ->
                                tasks.update(
                                        id,
                                        current -> {
                                            current.getStatus().setPhase(Phase.ERROR);
                                            current.getStatus().setErrorMessage(error.getMessage());
                                            current.getStatus().setEndTime(Instant.now());
                                        }))
                .doFinally(signal -> running.set(false))
                .subscribe(
                        result -> log.info("批量任务 {}: {}", id, result.getStatus().getPhase()),
                        error -> log.error("无法保存批量任务状态: {}", id, error));
    }

    private Mono<BatchProcessingStatus> executeTask(
            BatchProcessingStatus task, SecurityContext security) {
        String id = task.getMetadata().getName();
        var spec = task.getSpec();
        List<String> remaining =
                spec.getAttachmentNames().stream().filter(name -> pending(spec, name)).toList();
        int concurrency =
                Math.max(1, Math.min(spec.getConfig().getImageProcessingConcurrency(), 10));
        Mono<Object> cancellation = cancelSink.get().asMono();
        return tasks.update(id, current -> current.getStatus().setPhase(Phase.PROCESSING))
                .thenMany(
                        Flux.fromIterable(remaining)
                                .flatMap(
                                        name ->
                                                cancelRequested.get()
                                                        ? Mono.empty()
                                                        : processor.process(
                                                                id,
                                                                name,
                                                                spec,
                                                                security,
                                                                cancellation),
                                        concurrency))
                .then(tasks.update(id, this::finishTask));
    }

    private void finishTask(BatchProcessingStatus task) {
        Phase phase = Phase.COMPLETED;
        if (cancelRequested.get()) phase = Phase.CANCELLED;
        else if (!task.getSpec().getReviewAttachmentNames().isEmpty()) phase = Phase.INTERRUPTED;
        task.getStatus().setPhase(phase);
        task.getStatus().setEndTime(Instant.now());
    }

    private boolean pending(BatchProcessingStatusSpec spec, String name) {
        return !spec.getCompletedAttachmentNames().contains(name)
                && !spec.getReviewAttachmentNames().contains(name);
    }

    @Override
    public Mono<BatchProcessingStatus> resumeTask(String name) {
        return requireTask(name)
                .flatMap(
                        task -> {
                            Phase phase = task.getStatus().getPhase();
                            if (phase != Phase.INTERRUPTED
                                    && phase != Phase.CANCELLED
                                    && phase != Phase.ERROR) {
                                return Mono.error(new IllegalStateException("此任务不能继续"));
                            }
                            var spec = task.getSpec();
                            if (spec.getConfig() == null
                                    || spec.getAttachmentNames().stream()
                                            .noneMatch(n -> pending(spec, n))) {
                                return Mono.error(
                                        new IllegalStateException("没有可继续的未执行项，请查看失败项或需核查项"));
                            }
                            return launch(task);
                        });
    }

    @Override
    public Mono<BatchProcessingStatus> retryFailed(String name) {
        return requireTask(name)
                .flatMap(
                        task -> {
                            var previous = task.getSpec();
                            if (previous.getConfig() == null)
                                return Mono.error(
                                        new IllegalStateException("旧版任务缺少配置快照，请重新选择附件处理"));
                            var options = new BatchProcessingStatusSpec();
                            options.setConfig(previous.getConfig());
                            options.setKeepOriginal(previous.isKeepOriginal());
                            options.setEnableRemoteStorage(previous.isEnableRemoteStorage());
                            options.setReplaceReferences(previous.isReplaceReferences());
                            var names =
                                    task.getStatus().getFailedItems().stream()
                                            .filter(FailedItem::isRetryable)
                                            .map(FailedItem::getAttachmentName)
                                            .toList();
                            return createTask(names, options, name);
                        });
    }

    @Override
    public Mono<BatchProcessingStatus> requireTask(String name) {
        return tasks.get(name).switchIfEmpty(Mono.error(new IllegalArgumentException("任务不存在")));
    }

    @Override
    public Mono<ListResult<BatchProcessingStatus>> listTasks(int page, int size) {
        return tasks.list(page, size);
    }

    @Override
    public Mono<BatchProcessingStatus> cancelTask() {
        return Mono.defer(
                () -> {
                    if (!running.get()) return Mono.error(new IllegalStateException("没有正在执行的任务"));
                    String id = activeTaskId;
                    signalCancellation();
                    return tasks.update(
                            id,
                            task -> {
                                Phase phase = task.getStatus().getPhase();
                                if (phase == Phase.PROCESSING || phase == Phase.PENDING)
                                    task.getStatus().setPhase(Phase.CANCELLING);
                            });
                });
    }

    @Override
    public Mono<BatchProcessingStatus> getStatus() {
        return tasks.latest().defaultIfEmpty(new BatchProcessingStatus());
    }

    @Override
    public Mono<SettingsResponse> getSettings() {
        return settings.getKeepOriginalFile()
                .zipWith(settings.getRemoteStorageForBatchProcessing())
                .map(tuple -> new SettingsResponse(tuple.getT1(), tuple.getT2()));
    }

    public void prepareStart() {
        stopping.set(false);
    }

    public void stopAndDrain() {
        stopping.set(true);
        signalCancellation();
        Flux.interval(Duration.ofMillis(100))
                .filter(tick -> !running.get())
                .next()
                .timeout(Duration.ofSeconds(30))
                .block();
    }

    private void signalCancellation() {
        cancelRequested.set(true);
        var sink = cancelSink.get();
        if (sink != null) sink.tryEmitValue(true);
    }
}
