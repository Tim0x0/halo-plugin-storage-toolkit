package com.timxs.storagetoolkit.service.impl;

import com.timxs.storagetoolkit.config.ProcessingConfig;
import com.timxs.storagetoolkit.endpoint.BatchProcessingEndpoint.SettingsResponse;
import com.timxs.storagetoolkit.extension.AttachmentReference;
import com.timxs.storagetoolkit.extension.BatchProcessingStatus;
import com.timxs.storagetoolkit.extension.BatchProcessingStatus.BatchProcessingStatusSpec;
import com.timxs.storagetoolkit.extension.BatchProcessingStatus.BatchProcessingStatusStatus;
import com.timxs.storagetoolkit.extension.BatchProcessingStatus.FailedItem;
import com.timxs.storagetoolkit.extension.BatchProcessingStatus.SkippedItem;
import com.timxs.storagetoolkit.extension.BatchProcessingStatus.Phase;
import com.timxs.storagetoolkit.extension.BatchProcessingStatus.Progress;
import com.timxs.storagetoolkit.extension.ProcessingLog;
import com.timxs.storagetoolkit.model.ProcessingResult;
import com.timxs.storagetoolkit.model.ProcessingStatus;
import com.timxs.storagetoolkit.service.BatchProcessingService;
import com.timxs.storagetoolkit.service.ImageProcessor;
import com.timxs.storagetoolkit.service.ProcessingLogService;
import com.timxs.storagetoolkit.service.SettingsManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 批量处理服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchProcessingServiceImpl implements BatchProcessingService {

    private final ReactiveExtensionClient client;
    private final SettingsManager settingsManager;
    private final ImageProcessor imageProcessor;
    private final ProcessingLogService processingLogService;
    private final AttachmentService attachmentService;
    private final run.halo.app.infra.ExternalLinkProcessor externalLinkProcessor;

    private final DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();

    /** 取消标志 */
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    /** 任务 ID 格式 */
    private static final DateTimeFormatter TASK_ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    @Override
    public Mono<BatchProcessingStatus> createTask(List<String> attachmentNames) {
        if (attachmentNames == null || attachmentNames.isEmpty()) {
            return Mono.error(new IllegalArgumentException("附件列表不能为空"));
        }

        // 先检查配置，确保有处理功能启用
        return settingsManager.getConfig()
            .flatMap(config -> {
                // 检查是否有任何处理功能启用
                boolean hasProcessing = config.getWatermark().isEnabled()
                    || config.getFormatConversion().isEnabled();
                if (!hasProcessing) {
                    return Mono.error(new IllegalStateException("没有启用任何处理功能（水印或格式转换），请先在插件设置中启用"));
                }

                return getStatus()
                    .flatMap(status -> {
                        // 检查是否有正在运行的任务
                        if (status.getStatus() != null) {
                            Phase phase = status.getStatus().getPhase();
                            if (phase == Phase.PENDING || phase == Phase.PROCESSING) {
                                return Mono.error(new IllegalStateException("已有任务正在执行中"));
                            }
                        }

                        // 重置取消标志
                        cancelRequested.set(false);

                        // 生成任务 ID
                        String taskId = LocalDateTime.now().format(TASK_ID_FORMAT);

                        // 初始化任务状态
                        BatchProcessingStatusSpec spec = new BatchProcessingStatusSpec();
                        spec.setAttachmentNames(new ArrayList<>(attachmentNames));

                        BatchProcessingStatusStatus newStatus = new BatchProcessingStatusStatus();
                        newStatus.setPhase(Phase.PENDING);
                        Progress progress = new Progress();
                        progress.setTotal(attachmentNames.size());
                        progress.setProcessed(0);
                        progress.setSucceeded(0);
                        progress.setFailed(0);
                        newStatus.setProgress(progress);
                        newStatus.setFailedItems(new ArrayList<>());
                        newStatus.setSkippedItems(new ArrayList<>());
                        newStatus.setStartTime(Instant.now());
                        newStatus.setSavedBytes(0);
                        newStatus.setKeptOriginalCount(0);

                        status.setSpec(spec);
                        status.setStatus(newStatus);

                        return client.update(status)
                            .flatMap(updated ->
                                // 捕获当前安全上下文
                                ReactiveSecurityContextHolder.getContext()
                                    .map(securityContext -> {
                                        // 异步执行处理任务，传递安全上下文
                                        executeTask(taskId, updated, securityContext)
                                            .onErrorResume(error -> {
                                                log.error("批量处理任务失败: {}", error.getMessage(), error);
                                                return recordTaskError(taskId, error.getMessage());
                                            })
                                            .subscribe(
                                                result -> log.info("批量处理任务完成: {}, 状态: {}", taskId, result.getStatus().getPhase()),
                                                error -> log.error("批量处理任务异常: {}", error.getMessage(), error)
                                            );
                                        return updated;
                                    })
                                    .defaultIfEmpty(updated)
                            );
                    });
            });
    }

    /**
     * 记录任务错误到状态
     */
    private Mono<BatchProcessingStatus> recordTaskError(String taskId, String errorMessage) {
        return getStatus()
            .flatMap(status -> {
                status.getStatus().setPhase(Phase.ERROR);
                status.getStatus().setErrorMessage(errorMessage);
                status.getStatus().setEndTime(Instant.now());
                log.warn("批量处理任务 {} 失败: {}", taskId, errorMessage);
                return client.update(status)
                    .retryWhen(reactor.util.retry.Retry.backoff(3, java.time.Duration.ofMillis(100))
                        .filter(e -> e.getMessage() != null && e.getMessage().contains("optimistic")));
            });
    }


    /**
     * 执行批量处理任务
     */
    private Mono<BatchProcessingStatus> executeTask(String taskId, BatchProcessingStatus status, SecurityContext securityContext) {
        return settingsManager.getConfig()
            .zipWith(settingsManager.getKeepOriginalFile())
            .zipWith(settingsManager.getRemoteStorageForBatchProcessing())
            .flatMap(tuple -> {
                ProcessingConfig config = tuple.getT1().getT1();
                boolean keepOriginal = tuple.getT1().getT2();
                boolean enableRemote = tuple.getT2();

                // 更新 spec 中的 keepOriginal
                status.getSpec().setKeepOriginal(keepOriginal);

                // 更新状态为处理中
                status.getStatus().setPhase(Phase.PROCESSING);
                
                return client.update(status)
                    .flatMap(updated -> processAttachments(taskId, updated, config, keepOriginal, enableRemote, securityContext));
            });
    }

    /**
     * 处理附件列表
     */
    private Mono<BatchProcessingStatus> processAttachments(String taskId,
                                                            BatchProcessingStatus status,
                                                            ProcessingConfig config,
                                                            boolean keepOriginal,
                                                            boolean enableRemote,
                                                            SecurityContext securityContext) {
        List<String> attachmentNames = status.getSpec().getAttachmentNames();
        int concurrency = config.getImageProcessingConcurrency();

        log.info("开始批量处理任务 {}, 附件数: {}, 并发数: {}, 保留原文件: {}", 
            taskId, attachmentNames.size(), concurrency, keepOriginal);

        return Flux.fromIterable(attachmentNames)
            .flatMap(attachmentName -> {
                // 检查取消标志
                if (cancelRequested.get()) {
                    return Mono.empty();
                }
                return processOneAttachment(taskId, attachmentName, config, keepOriginal, enableRemote, securityContext);
            }, concurrency)
            .then(Mono.defer(() -> finalizeTask(taskId)));
    }

    /**
     * 处理单个附件
     */
    private Mono<Void> processOneAttachment(String taskId,
                                             String attachmentName,
                                             ProcessingConfig config,
                                             boolean keepOriginal,
                                             boolean enableRemote,
                                             SecurityContext securityContext) {
        return client.fetch(Attachment.class, attachmentName)
            .flatMap(attachment -> {
                String displayName = attachment.getSpec().getDisplayName();
                String mediaType = attachment.getSpec().getMediaType();
                Long fileSize = attachment.getSpec().getSize();
                String policyName = attachment.getSpec().getPolicyName();

                // 检查是否为远程存储
                return isRemoteStorage(policyName)
                    .flatMap(isRemote -> {
                        if (isRemote && !enableRemote) {
                            return recordSkipped(attachmentName, displayName, "远程存储未启用");
                        }

                        // 检查文件格式是否在允许列表中
                        if (!imageProcessor.isAllowedFormat(mediaType, config)) {
                            return recordSkipped(attachmentName, displayName, "文件格式不在允许列表中");
                        }

                        // 检查文件大小是否满足条件
                        if (!imageProcessor.shouldProcess(mediaType, fileSize != null ? fileSize : 0, config)) {
                            String reason = imageProcessor.getSkipReason(mediaType, fileSize != null ? fileSize : 0, config);
                            return recordSkipped(attachmentName, displayName, reason);
                        }

                        // 下载并处理图片
                        String permalink = attachment.getStatus() != null ? attachment.getStatus().getPermalink() : null;
                        if (permalink == null) {
                            return recordFailed(attachmentName, displayName, "附件没有 permalink");
                        }

                        return downloadAndProcess(taskId, attachment, config, keepOriginal, securityContext);
                    });
            })
            .onErrorResume(error -> {
                log.error("处理附件 {} 失败: {}", attachmentName, error.getMessage());
                return recordFailed(attachmentName, attachmentName, error.getMessage());
            });
    }

    /**
     * 下载并处理图片
     */
    private Mono<Void> downloadAndProcess(String taskId,
                                           Attachment attachment,
                                           ProcessingConfig config,
                                           boolean keepOriginal,
                                           SecurityContext securityContext) {
        String attachmentName = attachment.getMetadata().getName();
        String displayName = attachment.getSpec().getDisplayName();
        String mediaType = attachment.getSpec().getMediaType();
        String permalink = attachment.getStatus().getPermalink();
        long originalSize = attachment.getSpec().getSize() != null ? attachment.getSpec().getSize() : 0;

        return downloadFile(permalink)
            .flatMap(imageData -> imageProcessor.process(imageData, displayName, mediaType, config))
            .flatMap(result -> {
                if (result.status() == ProcessingStatus.SKIPPED) {
                    return recordSkipped(attachmentName, displayName, result.message());
                }
                if (result.status() == ProcessingStatus.FAILED) {
                    return recordFailed(attachmentName, displayName, result.message());
                }

                // 处理成功，更新附件
                long newSize = result.data().length;
                long savedBytes = originalSize - newSize;

                return updateAttachmentWithResult(taskId, attachment, result, keepOriginal, savedBytes, securityContext);
            })
            .onErrorResume(error -> {
                log.error("下载或处理附件 {} 失败: {}", displayName, error.getMessage());
                return recordFailed(attachmentName, displayName, error.getMessage());
            });
    }

    /**
     * 下载文件
     */
    private Mono<byte[]> downloadFile(String permalink) {
        return settingsManager.getDownloadTimeoutSeconds()
            .flatMap(timeoutSeconds ->
                Mono.fromCallable(() -> {
                    String fullUrl = externalLinkProcessor.processLink(permalink);
                    HttpURLConnection conn = null;
                    try {
                        URL url = new URL(fullUrl);
                        conn = (HttpURLConnection) url.openConnection();
                        conn.setConnectTimeout(30000);
                        conn.setReadTimeout(60000);
                        conn.setRequestMethod("GET");

                        int responseCode = conn.getResponseCode();
                        if (responseCode != 200) {
                            throw new RuntimeException("HTTP " + responseCode);
                        }

                        try (InputStream is = conn.getInputStream()) {
                            return is.readAllBytes();
                        }
                    } finally {
                        if (conn != null) {
                            conn.disconnect();
                        }
                    }
                }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds),
                    Mono.error(new java.util.concurrent.TimeoutException("下载文件超时: " + permalink)))
            );
    }

    /**
     * 更新附件（处理结果）
     * 上传处理后的新文件，替换原附件或保留原文件
     *
     * 策略：
     * - 保留原图：直接上传新文件，Halo 自动处理重名
     * - 不保留原图：先删除原图，再上传新文件（保持原文件名）
     */
    private Mono<Void> updateAttachmentWithResult(String taskId,
                                                   Attachment attachment,
                                                   ProcessingResult result,
                                                   boolean keepOriginal,
                                                   long savedBytes,
                                                   SecurityContext securityContext) {
        String attachmentName = attachment.getMetadata().getName();
        String displayName = attachment.getSpec().getDisplayName();
        String policyName = attachment.getSpec().getPolicyName();
        String groupName = attachment.getSpec().getGroupName();
        long originalSize = attachment.getSpec().getSize() != null ? attachment.getSpec().getSize() : 0;
        long newSize = result.data().length;

        Flux<DataBuffer> content = Flux.just(bufferFactory.wrap(result.data()));
        MediaType mediaType = MediaType.parseMediaType(result.contentType());

        // 构建带安全上下文的 Context，使用 Spring Security 的标准 key
        Context reactorContext = ReactiveSecurityContextHolder.withSecurityContext(Mono.just(securityContext));

        // 在整个操作链上写入安全上下文，确保 attachmentService.upload 能正确获取用户权限
        Mono<Void> uploadOperation;
        if (keepOriginal) {
            // 保留原文件：直接上传新文件，Halo 自动处理重名
            uploadOperation = attachmentService.upload(policyName, groupName, result.filename(), content, mediaType)
                .flatMap(newAttachment -> {
                    log.info("批量处理上传成功（保留原图）: {} -> {}", displayName, newAttachment.getMetadata().getName());
                    return createProcessingLog(taskId, displayName, result, originalSize, newSize, null)
                        .then(recordSucceeded(attachmentName, displayName, savedBytes, true));
                })
                .onErrorResume(error -> {
                    log.error("上传处理后的文件失败: {}, 错误: {}", displayName, error.getMessage());
                    return recordFailed(attachmentName, displayName, "上传失败: " + error.getMessage());
                });
        } else {
            // 不保留原文件：先上传新文件，成功后再删除原图
            uploadOperation = attachmentService.upload(policyName, groupName, result.filename(), content, mediaType)
                .flatMap(newAttachment -> {
                    log.info("批量处理上传成功: {} -> {}", displayName, newAttachment.getMetadata().getName());
                    // 上传成功后删除原附件
                    return client.delete(attachment)
                        .doOnSuccess(v -> log.info("已删除原附件: {}", displayName))
                        .then(createProcessingLog(taskId, displayName, result, originalSize, newSize, null))
                        .then(recordSucceeded(attachmentName, displayName, savedBytes, false))
                        .onErrorResume(deleteError -> {
                            // 删除失败：新文件已上传，记录为部分失败
                            String errorMsg = "删除原文件失败: " + deleteError.getMessage();
                            log.warn("批量处理部分失败（新文件已上传，原文件删除失败）: {}, 错误: {}", displayName, deleteError.getMessage());
                            return createProcessingLog(taskId, displayName, result, originalSize, newSize, errorMsg)
                                .then(recordFailed(attachmentName, displayName, errorMsg));
                        });
                })
                .onErrorResume(error -> {
                    log.error("上传处理后的文件失败: {}, 错误: {}", displayName, error.getMessage());
                    return recordFailed(attachmentName, displayName, "上传失败: " + error.getMessage());
                });
        }

        // 将安全上下文传递到整个操作链
        return uploadOperation.contextWrite(reactorContext);
    }

    /**
     * 创建处理日志
     */
    private Mono<ProcessingLog> createProcessingLog(String taskId, String displayName,
                                                     ProcessingResult result,
                                                     long originalSize, long newSize,
                                                     String errorMessage) {
        ProcessingLog logEntry = new ProcessingLog();
        logEntry.setMetadata(new Metadata());
        logEntry.getMetadata().setGenerateName("batch-log-");

        ProcessingLog.ProcessingLogSpec logSpec = new ProcessingLog.ProcessingLogSpec();
        logSpec.setOriginalFilename(displayName);
        logSpec.setResultFilename(result.filename());
        logSpec.setOriginalSize(originalSize);
        logSpec.setResultSize(newSize);
        logSpec.setStatus(result.status());
        logSpec.setProcessedAt(Instant.now());
        // 优先使用传入的 errorMessage，否则使用 result.message()
        logSpec.setErrorMessage(errorMessage != null ? errorMessage : result.message());
        logSpec.setSource("batch-processing");
        logEntry.setSpec(logSpec);

        return processingLogService.save(logEntry);
    }


    /**
     * 记录成功（带乐观锁重试）
     */
    private Mono<Void> recordSucceeded(String attachmentName, String displayName, long savedBytes, boolean keepOriginal) {
        return updateStatusWithRetry(status -> {
            Progress progress = status.getStatus().getProgress();
            progress.setProcessed(progress.getProcessed() + 1);
            progress.setSucceeded(progress.getSucceeded() + 1);
            status.getStatus().setSavedBytes(status.getStatus().getSavedBytes() + savedBytes);
            if (keepOriginal) {
                status.getStatus().setKeptOriginalCount(status.getStatus().getKeptOriginalCount() + 1);
            }
        });
    }

    /**
     * 记录失败（带乐观锁重试）
     */
    private Mono<Void> recordFailed(String attachmentName, String displayName, String error) {
        return updateStatusWithRetry(status -> {
            Progress progress = status.getStatus().getProgress();
            progress.setProcessed(progress.getProcessed() + 1);
            progress.setFailed(progress.getFailed() + 1);
            
            FailedItem failedItem = new FailedItem();
            failedItem.setAttachmentName(attachmentName);
            failedItem.setDisplayName(displayName);
            failedItem.setError(error);
            status.getStatus().getFailedItems().add(failedItem);
        });
    }

    /**
     * 记录跳过（带乐观锁重试）
     */
    private Mono<Void> recordSkipped(String attachmentName, String displayName, String reason) {
        log.debug("跳过附件 {}: {}", displayName, reason);
        return updateStatusWithRetry(status -> {
            Progress progress = status.getStatus().getProgress();
            progress.setProcessed(progress.getProcessed() + 1);
            // 跳过不计入成功，单独计数
            status.getStatus().setSkippedCount(status.getStatus().getSkippedCount() + 1);

            // 添加到跳过项列表
            SkippedItem skippedItem = new SkippedItem();
            skippedItem.setAttachmentName(attachmentName);
            skippedItem.setDisplayName(displayName);
            skippedItem.setReason(reason);
            status.getStatus().getSkippedItems().add(skippedItem);
        });
    }

    /**
     * 带乐观锁重试的状态更新
     * 解决并发更新时的竞态条件问题
     */
    private Mono<Void> updateStatusWithRetry(java.util.function.Consumer<BatchProcessingStatus> updater) {
        return Mono.defer(() -> getStatus()
            .flatMap(status -> {
                updater.accept(status);
                return client.update(status);
            }))
            .retryWhen(reactor.util.retry.Retry.backoff(5, java.time.Duration.ofMillis(50))
                .maxBackoff(java.time.Duration.ofMillis(500))
                .filter(e -> e.getMessage() != null && e.getMessage().contains("optimistic")))
            .onErrorResume(e -> {
                log.warn("状态更新最终失败（已重试5次）: {}", e.getMessage());
                return Mono.empty();
            })
            .then();
    }

    /**
     * 完成任务
     */
    private Mono<BatchProcessingStatus> finalizeTask(String taskId) {
        return getStatus()
            .flatMap(status -> {
                if (cancelRequested.get()) {
                    status.getStatus().setPhase(Phase.CANCELLED);
                } else {
                    status.getStatus().setPhase(Phase.COMPLETED);
                }
                status.getStatus().setEndTime(Instant.now());
                
                log.info("批量处理任务 {} 完成, 状态: {}, 进度: {}/{}", 
                    taskId, 
                    status.getStatus().getPhase(),
                    status.getStatus().getProgress().getProcessed(),
                    status.getStatus().getProgress().getTotal());
                
                return client.update(status);
            });
    }

    /**
     * 检查是否为远程存储
     */
    private Mono<Boolean> isRemoteStorage(String policyName) {
        if (policyName == null || policyName.isEmpty()) {
            return Mono.just(false);
        }
        return client.fetch(Policy.class, policyName)
            .map(policy -> !"local".equals(policy.getSpec().getTemplateName()))
            .defaultIfEmpty(false);
    }

    @Override
    public Mono<BatchProcessingStatus> cancelTask() {
        return getStatus()
            .flatMap(status -> {
                if (status.getStatus() == null) {
                    return Mono.error(new IllegalStateException("没有正在执行的任务"));
                }

                Phase phase = status.getStatus().getPhase();
                if (phase != Phase.PENDING && phase != Phase.PROCESSING) {
                    return Mono.error(new IllegalStateException("任务不在可取消状态"));
                }

                // 设置取消标志
                cancelRequested.set(true);

                // 立即更新状态为 CANCELLING，让用户知道取消请求已接收
                status.getStatus().setPhase(Phase.CANCELLING);
                log.info("收到取消请求，更新状态为 CANCELLING");
                return client.update(status);
            });
    }

    @Override
    public Mono<BatchProcessingStatus> getStatus() {
        return client.fetch(BatchProcessingStatus.class, BatchProcessingStatus.SINGLETON_NAME)
            .switchIfEmpty(Mono.defer(() -> {
                BatchProcessingStatus status = new BatchProcessingStatus();
                status.setMetadata(new Metadata());
                status.getMetadata().setName(BatchProcessingStatus.SINGLETON_NAME);
                status.setSpec(new BatchProcessingStatusSpec());
                status.setStatus(new BatchProcessingStatusStatus());
                return client.create(status);
            }));
    }

    @Override
    public Mono<Integer> countReferencedAttachments(List<String> attachmentNames) {
        if (attachmentNames == null || attachmentNames.isEmpty()) {
            return Mono.just(0);
        }

        Set<String> nameSet = Set.copyOf(attachmentNames);
        
        return client.listAll(AttachmentReference.class, ListOptions.builder().build(), Sort.unsorted())
            .filter(ref -> ref.getSpec() != null 
                && ref.getSpec().getAttachmentName() != null
                && nameSet.contains(ref.getSpec().getAttachmentName())
                && ref.getStatus() != null 
                && ref.getStatus().getReferenceCount() > 0)
            .map(ref -> ref.getSpec().getAttachmentName())
            .distinct()
            .count()
            .map(Long::intValue);
    }

    @Override
    public Mono<SettingsResponse> getSettings() {
        return settingsManager.getKeepOriginalFile()
            .zipWith(settingsManager.getRemoteStorageForBatchProcessing())
            .map(tuple -> new SettingsResponse(tuple.getT1(), tuple.getT2()));
    }
}
