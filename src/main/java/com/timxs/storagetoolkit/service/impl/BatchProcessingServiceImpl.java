package com.timxs.storagetoolkit.service.impl;

import com.timxs.storagetoolkit.config.ProcessingConfig;
import com.timxs.storagetoolkit.endpoint.BatchProcessingEndpoint.SettingsResponse;
import com.timxs.storagetoolkit.extension.BatchProcessingStatus;
import com.timxs.storagetoolkit.extension.BatchProcessingStatus.BatchProcessingStatusSpec;
import com.timxs.storagetoolkit.extension.BatchProcessingStatus.BatchProcessingStatusStatus;
import com.timxs.storagetoolkit.extension.BatchProcessingStatus.FailedItem;
import com.timxs.storagetoolkit.extension.BatchProcessingStatus.SkippedItem;
import com.timxs.storagetoolkit.extension.BatchProcessingStatus.Phase;
import com.timxs.storagetoolkit.extension.BatchProcessingStatus.Progress;
import com.timxs.storagetoolkit.extension.ProcessingLog;
import com.timxs.storagetoolkit.model.ProcessingResult;
import com.timxs.storagetoolkit.model.ProcessingSource;
import com.timxs.storagetoolkit.model.ProcessingStatus;
import com.timxs.storagetoolkit.service.BatchProcessingService;
import com.timxs.storagetoolkit.service.ImageProcessor;
import com.timxs.storagetoolkit.service.ProcessingLogService;
import com.timxs.storagetoolkit.service.ReferenceReplacerService;
import com.timxs.storagetoolkit.service.SettingsManager;
import com.timxs.storagetoolkit.service.support.RetryUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.context.Context;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
    private final ReferenceReplacerService referenceReplacerService;
    private final com.timxs.storagetoolkit.service.support.ProxiedWebClientFactory webClientFactory;

    private final DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();

    /** 取消标志（阻止后续任务入队） */
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    /**
     * 取消信号：用于真正取消已在执行的下载/处理 Mono。
     * 仅靠 cancelRequested 只能阻止后续入队，不会触发下游 doOnCancel，
     * 飞行中的请求会一直跑到自然结束。
     */
    private final AtomicReference<Sinks.One<Object>> cancelSinkRef = new AtomicReference<>();

    /** 任务 ID 格式 */
    private static final DateTimeFormatter TASK_ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    // ========== 内存中的进度数据（不持久化，重启后清零）==========
    private final AtomicInteger memTotal = new AtomicInteger(0);
    private final AtomicInteger memProcessed = new AtomicInteger(0);
    private final AtomicInteger memSucceeded = new AtomicInteger(0);
    private final AtomicInteger memFailed = new AtomicInteger(0);
    private final AtomicInteger memSkipped = new AtomicInteger(0);
    private final AtomicLong memSavedBytes = new AtomicLong(0);
    private final AtomicInteger memKeptOriginal = new AtomicInteger(0);
    private final ConcurrentLinkedQueue<FailedItem> memFailedItems = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<SkippedItem> memSkippedItems = new ConcurrentLinkedQueue<>();

    @Override
    public Mono<BatchProcessingStatus> createTask(List<String> attachmentNames, boolean replaceReferences) {
        if (attachmentNames == null || attachmentNames.isEmpty()) {
            return Mono.error(new IllegalArgumentException("附件列表不能为空"));
        }

        // 先检查配置，确保有处理功能启用
        return settingsManager.getConfig()
            .flatMap(config -> {
                // 检查是否有任何处理功能启用
                if (!imageProcessor.hasProcessingEnabled(config)) {
                    return Mono.error(new IllegalStateException("没有启用任何处理功能（水印或格式转换），请先在插件设置中启用"));
                }

                return getStatus()
                    .flatMap(status -> {
                        // 检查是否有正在运行的任务
                        if (status.getStatus() != null) {
                            Phase phase = status.getStatus().getPhase();
                            if (phase == Phase.PENDING || phase == Phase.PROCESSING) {
                                // 检查内存进度：如果都为 0 说明服务重启过，允许重新开始
                                if (memProcessed.get() == 0 && memTotal.get() == 0) {
                                    log.warn("检测到服务重启，上次任务已中断，允许重新开始");
                                } else {
                                    return Mono.error(new IllegalStateException("已有任务正在执行中"));
                                }
                            }
                        }

                        // 重置取消标志、取消信号和内存进度
                        cancelRequested.set(false);
                        cancelSinkRef.set(Sinks.one());
                        resetMemoryProgress(attachmentNames.size());

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
                                        executeTask(taskId, updated, replaceReferences, securityContext)
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
                    .retryWhen(RetryUtils.optimisticLockRetry());
            });
    }


    /**
     * 执行批量处理任务
     */
    private Mono<BatchProcessingStatus> executeTask(String taskId, BatchProcessingStatus status, boolean replaceReferences, SecurityContext securityContext) {
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
                    .flatMap(updated -> processAttachments(taskId, updated, config, keepOriginal, enableRemote, replaceReferences, securityContext));
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
                                                            boolean replaceReferences,
                                                            SecurityContext securityContext) {
        List<String> attachmentNames = status.getSpec().getAttachmentNames();
        int concurrency = config.getImageProcessingConcurrency();

        log.info("开始批量处理任务 {}, 附件数: {}, 并发数: {}, 保留原文件: {}, 替换引用: {}",
            taskId, attachmentNames.size(), concurrency, keepOriginal, replaceReferences);

        return Flux.fromIterable(attachmentNames)
            .flatMap(attachmentName -> {
                // 检查取消标志
                if (cancelRequested.get()) {
                    return Mono.empty();
                }
                return processOneAttachment(taskId, attachmentName, config, keepOriginal, enableRemote, replaceReferences, securityContext);
            }, concurrency)
            .then(Mono.defer(() -> finalizeTask(taskId)));
    }

    /**
     * 取消信号：用户点击取消时发射，用于中断已在飞行的下载。
     * 任务尚未启动时返回 Mono.never()，即不施加取消。
     */
    private Mono<Object> cancelSignalMono() {
        Sinks.One<Object> sink = cancelSinkRef.get();
        if (sink == null) {
            return Mono.never();
        }
        return sink.asMono();
    }

    /**
     * 处理单个附件
     */
    private Mono<Void> processOneAttachment(String taskId,
                                             String attachmentName,
                                             ProcessingConfig config,
                                             boolean keepOriginal,
                                             boolean enableRemote,
                                             boolean replaceReferences,
                                             SecurityContext securityContext) {
        return client.fetch(Attachment.class, attachmentName)
            .filter(attachment -> attachment.getMetadata().getDeletionTimestamp() == null)
            .switchIfEmpty(Mono.defer(() -> recordSkipped(attachmentName, attachmentName, 0, "文件不存在或已删除")
                .then(Mono.empty())))
            .flatMap(attachment -> {
                String displayName = attachment.getSpec().getDisplayName();
                String mediaType = attachment.getSpec().getMediaType();
                Long fileSize = attachment.getSpec().getSize();
                String policyName = attachment.getSpec().getPolicyName();

                // 检查是否为远程存储
                return isRemoteStorage(policyName)
                    .flatMap(isRemote -> {
                        long size = fileSize != null ? fileSize : 0;

                        if (isRemote && !enableRemote) {
                            return recordSkipped(attachmentName, displayName, size, "远程存储未启用");
                        }

                        // 检查文件格式是否在允许列表中
                        if (!imageProcessor.isAllowedFormat(mediaType, config)) {
                            return recordSkipped(attachmentName, displayName, size, "文件格式不在允许列表中");
                        }

                        // 检查文件大小是否满足条件
                        if (!imageProcessor.shouldProcess(mediaType, size, config)) {
                            String reason = imageProcessor.getSkipReason(mediaType, size, config);
                            return recordSkipped(attachmentName, displayName, size, reason);
                        }

                        // 下载并处理图片
                        String permalink = attachment.getStatus() != null ? attachment.getStatus().getPermalink() : null;
                        if (permalink == null) {
                            return recordFailed(attachmentName, displayName, "附件没有 permalink");
                        }

                        return downloadAndProcess(taskId, attachment, config, keepOriginal, replaceReferences, securityContext);
                    });
            })
            .onErrorResume(error -> {
                log.warn("处理附件 {} 失败: {}", attachmentName, error.getMessage());
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
                                           boolean replaceReferences,
                                           SecurityContext securityContext) {
        String attachmentName = attachment.getMetadata().getName();
        String displayName = attachment.getSpec().getDisplayName();
        String mediaType = attachment.getSpec().getMediaType();
        String permalink = attachment.getStatus().getPermalink();
        long originalSize = attachment.getSpec().getSize() != null ? attachment.getSpec().getSize() : 0;

        return downloadFile(permalink, config)
            .flatMap(imageData -> imageProcessor.process(imageData, displayName, mediaType, config))
            // 取消只作用于"下载 + 处理"阶段：此阶段无外部副作用，可安全中断（reactor-netty 会关闭 channel）。
            // 之后的上传 / 引用替换 / 删除原图一旦开始就必须跑完，中途取消会留下半成品。
            .takeUntilOther(cancelSignalMono())
            .flatMap(result -> {
                if (result.status() == ProcessingStatus.SKIPPED) {
                    return recordSkipped(attachmentName, displayName, originalSize, result.message());
                }
                if (result.status() == ProcessingStatus.FAILED) {
                    return recordFailed(attachmentName, displayName, result.message());
                }

                // 处理成功，更新附件
                long newSize = result.data().length;
                long savedBytes = originalSize - newSize;

                return updateAttachmentWithResult(taskId, attachment, result, keepOriginal, savedBytes, replaceReferences, securityContext);
            })
            // 下载体积超过 maxFileSize 上限。能走到这里说明 attachment.spec.size 与实际文件
            // 对不上——对得上的话前面 shouldProcess 就已经拦成 SKIPPED 了。归为跳过而非失败，
            // 并在文案里点出元数据不准，比笼统报一条 DataBufferLimitException 有诊断价值。
            .onErrorResume(DataBufferLimitException.class, error -> {
                log.warn("附件 {} 实际体积超过最大限制，附件记录的大小 {} 字节与实际不符",
                    displayName, originalSize);
                return recordSkipped(attachmentName, displayName, originalSize,
                    "实际文件大小超出最大限制（附件记录的大小不准确）");
            })
            .onErrorResume(error -> {
                log.warn("下载或处理附件 {} 失败: {}", displayName, error.getMessage());
                return recordFailed(attachmentName, displayName, error.getMessage());
            });
    }

    /**
     * 下载文件（代理感知；下游取消时 reactor-netty 会直接关闭 channel）
     *
     * @param config 用于取 maxFileSize 作为响应体聚合上限，防止大文件 OOM
     */
    private Mono<byte[]> downloadFile(String permalink, ProcessingConfig config) {
        return settingsManager.getDownloadTimeoutSeconds()
            .zipWith(settingsManager.getProxySettings()
                .defaultIfEmpty(SettingsManager.ProxySettings.disabled()))
            .flatMap(tuple -> {
                int timeoutSeconds = tuple.getT1();
                SettingsManager.ProxySettings proxySettings = tuple.getT2();
                String fullUrl = externalLinkProcessor.processLink(permalink);
                String siteUrl = externalLinkProcessor.processLink("/");

                // maxFileSize 为 0 表示用户不限制大小
                long maxFileSize = config.getMaxFileSize();
                int maxBytes = (maxFileSize > 0 && maxFileSize < Integer.MAX_VALUE)
                    ? (int) maxFileSize
                    : Integer.MAX_VALUE;

                return webClientFactory.get(timeoutSeconds, proxySettings, true)
                    .get()
                    .uri(com.timxs.storagetoolkit.service.support.ProxiedWebClientFactory.normalizeUri(fullUrl))
                    .header(HttpHeaders.USER_AGENT, SettingsManager.DEFAULT_USER_AGENT)
                    .header(HttpHeaders.REFERER, siteUrl)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(),
                        response -> Mono.error(new RuntimeException("HTTP " + response.statusCode().value())))
                    .bodyToFlux(DataBuffer.class)
                    // as 而非 transform：join 返回 Mono，用 transform 会被重新包成 Flux
                    .as(body -> DataBufferUtils.join(body, maxBytes))
                    .map(buffer -> {
                        try {
                            byte[] bytes = new byte[buffer.readableByteCount()];
                            buffer.read(bytes);
                            return bytes;
                        } finally {
                            DataBufferUtils.release(buffer);
                        }
                    })
                    .timeout(java.time.Duration.ofSeconds(timeoutSeconds),
                        Mono.error(new java.util.concurrent.TimeoutException("下载文件超时: " + permalink)));
            });
    }

    /**
     * 更新附件（处理结果）
     * 上传处理后的新文件，替换原附件或保留原文件
     *
     * 策略：
     * - 保留原图：直接上传新文件，Halo 自动处理重名
     * - 不保留原图：先删除原图，再上传新文件（保持原文件名）
     *
     * @param replaceReferences 是否替换引用（仅在 keepOriginal=false 时生效）
     */
    private Mono<Void> updateAttachmentWithResult(String taskId,
                                                   Attachment attachment,
                                                   ProcessingResult result,
                                                   boolean keepOriginal,
                                                   long savedBytes,
                                                   boolean replaceReferences,
                                                   SecurityContext securityContext) {
        String attachmentName = attachment.getMetadata().getName();
        String displayName = attachment.getSpec().getDisplayName();
        String policyName = attachment.getSpec().getPolicyName();
        String groupName = attachment.getSpec().getGroupName();
        long originalSize = attachment.getSpec().getSize() != null ? attachment.getSpec().getSize() : 0;
        long newSize = result.data().length;

        // 获取原附件的 permalink（用于引用替换）
        String oldPermalink = attachment.getStatus() != null ? attachment.getStatus().getPermalink() : null;

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
                    log.debug("批量处理上传成功（保留原图）: {} -> {}", displayName, newAttachment.getMetadata().getName());
                    String newAttachmentName = newAttachment.getMetadata().getName();
                    // 添加 null 检查
                    String newPermalink = newAttachment.getStatus() != null ? newAttachment.getStatus().getPermalink() : null;

                    // 执行引用替换（如果用户选择替换引用）
                    Mono<Void> replaceReferencesMono = Mono.empty();
                    if (replaceReferences && oldPermalink != null && newPermalink != null) {
                        replaceReferencesMono = referenceReplacerService.replaceAfterBatchProcessing(
                                attachmentName, newAttachmentName, oldPermalink, newPermalink)
                            .doOnSuccess(replaceResult -> {
                                if (replaceResult.getUpdatedSources() > 0) {
                                    log.debug("批量处理后引用替换完成（保留原图）：{} 个内容源已更新", replaceResult.getUpdatedSources());
                                }
                            })
                            .doOnError(e -> log.warn("批量处理后引用替换失败: {}", e.getMessage()))
                            .onErrorResume(e -> Mono.empty()) // 引用替换失败不应影响主流程
                            .then();
                    }

                    // 先替换引用，再记录成功
                    return replaceReferencesMono
                        .then(createProcessingLog(taskId, displayName, result, originalSize, newSize, null))
                        .then(recordSucceeded(attachmentName, displayName, savedBytes, true));
                })
                .onErrorResume(error -> {
                    log.warn("上传处理后的文件失败: {}, 错误: {}", displayName, error.getMessage());
                    return recordFailed(attachmentName, displayName, "上传失败: " + error.getMessage());
                });
        } else {
            // 不保留原文件：先上传新文件，成功后替换引用，再删除原图
            uploadOperation = attachmentService.upload(policyName, groupName, result.filename(), content, mediaType)
                .flatMap(newAttachment -> {
                    log.debug("批量处理上传成功: {} -> {}", displayName, newAttachment.getMetadata().getName());
                    String newAttachmentName = newAttachment.getMetadata().getName();
                    String newPermalink = newAttachment.getStatus() != null ? newAttachment.getStatus().getPermalink() : null;

                    // 执行引用替换（在删除原附件之前）
                    Mono<Void> replaceReferencesMono = Mono.empty();
                    if (replaceReferences && oldPermalink != null && newPermalink != null) {
                        replaceReferencesMono = referenceReplacerService.replaceAfterBatchProcessing(
                                attachmentName, newAttachmentName, oldPermalink, newPermalink)
                            .doOnSuccess(replaceResult -> {
                                if (replaceResult.getUpdatedSources() > 0) {
                                    log.debug("批量处理后引用替换完成：{} 个内容源已更新", replaceResult.getUpdatedSources());
                                }
                            })
                            .doOnError(e -> log.warn("批量处理后引用替换失败: {}", e.getMessage()))
                            .onErrorResume(e -> Mono.empty()) // 引用替换失败不应影响主流程
                            .then();
                    }

                    // 上传成功后先替换引用，再删除原附件
                    return replaceReferencesMono
                        .then(client.delete(attachment))
                        .doOnSuccess(v -> log.debug("已删除原附件: {}", displayName))
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
                    log.warn("上传处理后的文件失败: {}, 错误: {}", displayName, error.getMessage());
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
        logSpec.setSource(ProcessingSource.BATCH_PROCESSING);
        logEntry.setSpec(logSpec);

        return processingLogService.save(logEntry);
    }


    /**
     * 记录成功（只更新内存）
     */
    private Mono<Void> recordSucceeded(String attachmentName, String displayName, long savedBytes, boolean keepOriginal) {
        memProcessed.incrementAndGet();
        memSucceeded.incrementAndGet();
        memSavedBytes.addAndGet(savedBytes);
        if (keepOriginal) {
            memKeptOriginal.incrementAndGet();
        }
        return Mono.empty();
    }

    /**
     * 记录失败（只更新内存）
     */
    private Mono<Void> recordFailed(String attachmentName, String displayName, String error) {
        memProcessed.incrementAndGet();
        memFailed.incrementAndGet();

        FailedItem failedItem = new FailedItem();
        failedItem.setAttachmentName(attachmentName);
        failedItem.setDisplayName(displayName);
        failedItem.setError(error);
        memFailedItems.add(failedItem);
        return Mono.empty();
    }

    /**
     * 记录跳过（更新内存并写入日志）
     */
    private Mono<Void> recordSkipped(String attachmentName, String displayName, long fileSize, String reason) {
        log.debug("跳过附件 {}: {}", displayName, reason);
        memProcessed.incrementAndGet();
        memSkipped.incrementAndGet();

        SkippedItem skippedItem = new SkippedItem();
        skippedItem.setAttachmentName(attachmentName);
        skippedItem.setDisplayName(displayName);
        skippedItem.setReason(reason);
        memSkippedItems.add(skippedItem);

        // 写入 ProcessingLog
        return processingLogService.saveSkippedLog(displayName, null, fileSize, Instant.now(), reason, ProcessingSource.BATCH_PROCESSING)
            .then();
    }

    /**
     * 重置内存进度
     */
    private void resetMemoryProgress(int total) {
        memTotal.set(total);
        memProcessed.set(0);
        memSucceeded.set(0);
        memFailed.set(0);
        memSkipped.set(0);
        memSavedBytes.set(0);
        memKeptOriginal.set(0);
        memFailedItems.clear();
        memSkippedItems.clear();
    }

    /**
     * 清空内存进度
     */
    private void clearMemoryProgress() {
        memTotal.set(0);
        memProcessed.set(0);
        memSucceeded.set(0);
        memFailed.set(0);
        memSkipped.set(0);
        memSavedBytes.set(0);
        memKeptOriginal.set(0);
        memFailedItems.clear();
        memSkippedItems.clear();
    }

    /**
     * 完成任务（持久化内存数据到数据库）
     */
    private Mono<BatchProcessingStatus> finalizeTask(String taskId) {
        return getStatus()
            .flatMap(status -> {
                // 将内存数据持久化到状态对象
                Progress progress = status.getStatus().getProgress();
                progress.setTotal(memTotal.get());
                progress.setProcessed(memProcessed.get());
                progress.setSucceeded(memSucceeded.get());
                progress.setFailed(memFailed.get());
                status.getStatus().setSkippedCount(memSkipped.get());
                status.getStatus().setSavedBytes(memSavedBytes.get());
                status.getStatus().setKeptOriginalCount(memKeptOriginal.get());

                // 持久化失败和跳过项列表
                status.getStatus().getFailedItems().addAll(memFailedItems);
                status.getStatus().getSkippedItems().addAll(memSkippedItems);

                if (cancelRequested.get()) {
                    status.getStatus().setPhase(Phase.CANCELLED);
                } else {
                    status.getStatus().setPhase(Phase.COMPLETED);
                }
                status.getStatus().setEndTime(Instant.now());

                log.info("批量处理任务 {} 完成, 状态: {}, 进度: {}/{}",
                    taskId,
                    status.getStatus().getPhase(),
                    memProcessed.get(),
                    memTotal.get());

                // 清空内存进度（在数据库更新完成后执行，避免竞态条件）
                return client.update(status)
                    .doOnSuccess(updated -> clearMemoryProgress());
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

                // 设置取消标志（阻止后续入队）+ 发射取消信号（中断飞行中请求）
                cancelRequested.set(true);
                Sinks.One<Object> sink = cancelSinkRef.get();
                if (sink != null) {
                    Sinks.EmitResult result = sink.tryEmitValue(Boolean.TRUE);
                    log.info("收到取消请求，已发射取消信号: {}", result);
                } else {
                    log.info("收到取消请求，但取消信号尚未初始化");
                }

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
            }))
            .map(status -> {
                // 如果任务正在处理中，注入内存中的实时进度
                if (status.getStatus() != null &&
                    (status.getStatus().getPhase() == Phase.PROCESSING ||
                     status.getStatus().getPhase() == Phase.PENDING)) {
                    Progress progress = status.getStatus().getProgress();
                    if (progress != null) {
                        progress.setTotal(memTotal.get());
                        progress.setProcessed(memProcessed.get());
                        progress.setSucceeded(memSucceeded.get());
                        progress.setFailed(memFailed.get());
                    }
                    status.getStatus().setSkippedCount(memSkipped.get());
                    status.getStatus().setSavedBytes(memSavedBytes.get());
                    status.getStatus().setKeptOriginalCount(memKeptOriginal.get());
                }
                return status;
            });
    }

    @Override
    public Mono<SettingsResponse> getSettings() {
        return settingsManager.getKeepOriginalFile()
            .zipWith(settingsManager.getRemoteStorageForBatchProcessing())
            .map(tuple -> new SettingsResponse(tuple.getT1(), tuple.getT2()));
    }
}
