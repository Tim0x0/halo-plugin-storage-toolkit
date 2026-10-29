package com.timxs.storagetoolkit.service.batch;

import com.timxs.storagetoolkit.extension.BatchProcessingStatus.BatchProcessingStatusSpec;
import com.timxs.storagetoolkit.extension.ProcessingLog;
import com.timxs.storagetoolkit.model.ProcessingResult;
import com.timxs.storagetoolkit.model.ProcessingSource;
import com.timxs.storagetoolkit.model.ProcessingStatus;
import com.timxs.storagetoolkit.model.ReferenceReplacementResult;
import com.timxs.storagetoolkit.service.ImageProcessor;
import com.timxs.storagetoolkit.service.ProcessingLogService;
import com.timxs.storagetoolkit.service.ReferenceReplacerService;
import com.timxs.storagetoolkit.service.support.AttachmentImageLoader;

import lombok.RequiredArgsConstructor;

import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import java.time.Instant;

/** Executes one attachment's download, conversion and guarded replacement. */
@Component
@RequiredArgsConstructor
public class BatchAttachmentProcessor {
    private final ReactiveExtensionClient client;
    private final ImageProcessor processor;
    private final AttachmentService attachments;
    private final ReferenceReplacerService references;
    private final ProcessingLogService logs;
    private final AttachmentImageLoader loader;
    private final BatchItemJournal journal;
    private final DefaultDataBufferFactory buffers = new DefaultDataBufferFactory();

    public Mono<Void> process(
            String taskId,
            String name,
            BatchProcessingStatusSpec options,
            SecurityContext security,
            Mono<Object> cancellation) {
        return client.fetch(Attachment.class, name)
                .filter(attachment -> attachment.getMetadata().getDeletionTimestamp() == null)
                .switchIfEmpty(
                        Mono.defer(
                                () ->
                                        journal.skipped(taskId, name, name, 0, "文件不存在或已删除")
                                                .then(Mono.empty())))
                .flatMap(
                        attachment ->
                                processExisting(
                                        taskId, attachment, options, security, cancellation))
                .onErrorResume(error -> journal.failed(taskId, name, name, error.getMessage()));
    }

    private Mono<Void> processExisting(
            String taskId,
            Attachment attachment,
            BatchProcessingStatusSpec options,
            SecurityContext security,
            Mono<Object> cancellation) {
        String name = attachment.getMetadata().getName();
        var spec = attachment.getSpec();
        String displayName = spec.getDisplayName();
        long size = spec.getSize() == null ? 0 : spec.getSize();
        return isRemote(spec.getPolicyName())
                .flatMap(
                        remote -> {
                            String reason = skipReason(attachment, options, remote);
                            if (reason != null)
                                return journal.skipped(taskId, name, displayName, size, reason);
                            return loader.download(
                                            attachment.getStatus().getPermalink(),
                                            options.getConfig())
                                    .flatMap(
                                            data ->
                                                    processor.process(
                                                            data,
                                                            displayName,
                                                            spec.getMediaType(),
                                                            options.getConfig()))
                                    // Only the side-effect-free stages are cancellable. Once upload
                                    // begins, drain the operation.
                                    .takeUntilOther(cancellation)
                                    .flatMap(
                                            result ->
                                                    applyResult(
                                                            taskId,
                                                            attachment,
                                                            result,
                                                            options,
                                                            security))
                                    .onErrorResume(
                                            DataBufferLimitException.class,
                                            error ->
                                                    journal.skipped(
                                                            taskId,
                                                            name,
                                                            displayName,
                                                            size,
                                                            "实际文件大小超出最大限制（附件记录的大小不准确）"));
                        });
    }

    private String skipReason(
            Attachment attachment, BatchProcessingStatusSpec options, boolean remote) {
        var spec = attachment.getSpec();
        if (remote && !options.isEnableRemoteStorage()) return "远程存储未启用";
        if (!processor.isAllowedFormat(spec.getMediaType(), options.getConfig()))
            return "文件格式不在允许列表中";
        String reason =
                processor.getSkipReason(
                        spec.getMediaType(),
                        spec.getSize() == null ? 0 : spec.getSize(),
                        options.getConfig());
        if (reason != null) return reason;
        if (attachment.getStatus() == null || attachment.getStatus().getPermalink() == null)
            return "附件没有 permalink";
        return null;
    }

    private Mono<Void> applyResult(
            String taskId,
            Attachment original,
            ProcessingResult result,
            BatchProcessingStatusSpec options,
            SecurityContext security) {
        String name = original.getMetadata().getName();
        String displayName = original.getSpec().getDisplayName();
        long size = original.getSpec().getSize() == null ? 0 : original.getSpec().getSize();
        if (result.status() == ProcessingStatus.SKIPPED)
            return journal.skipped(taskId, name, displayName, size, result.message());
        if (result.status() == ProcessingStatus.FAILED)
            return journal.failed(taskId, name, displayName, result.message());
        return uploadResult(taskId, original, result, options, security);
    }

    private Mono<Void> uploadResult(
            String taskId,
            Attachment original,
            ProcessingResult result,
            BatchProcessingStatusSpec options,
            SecurityContext security) {
        String name = original.getMetadata().getName();
        String displayName = original.getSpec().getDisplayName();
        long originalSize = original.getSpec().getSize() == null ? 0 : original.getSpec().getSize();
        return journal.beginSideEffects(taskId, name)
                .then(
                        Mono.defer(
                                () ->
                                        attachments.upload(
                                                original.getSpec().getPolicyName(),
                                                original.getSpec().getGroupName(),
                                                result.filename(),
                                                Flux.just(buffers.wrap(result.data())),
                                                MediaType.parseMediaType(result.contentType()))))
                .flatMap(
                        uploaded ->
                                finishReplacement(
                                        taskId, original, uploaded, result, options, originalSize))
                .onErrorResume(
                        error ->
                                journal.failed(
                                        taskId,
                                        name,
                                        displayName,
                                        "上传或后续处理失败: " + error.getMessage()))
                .contextWrite(
                        ReactiveSecurityContextHolder.withSecurityContext(Mono.just(security)));
    }

    private Mono<Void> finishReplacement(
            String taskId,
            Attachment original,
            Attachment uploaded,
            ProcessingResult result,
            BatchProcessingStatusSpec options,
            long originalSize) {
        String name = original.getMetadata().getName();
        String displayName = original.getSpec().getDisplayName();
        Mono<Void> replacement =
                options.isReplaceReferences()
                        ? replaceReferences(original, uploaded)
                        : Mono.empty();
        return replacement
                .then(
                        options.isKeepOriginal()
                                ? Mono.empty()
                                : Mono.defer(() -> client.delete(original)).then())
                .then(Mono.defer(() -> logResult(displayName, result, originalSize, null)))
                .then(
                        Mono.defer(
                                () ->
                                        journal.succeeded(
                                                taskId,
                                                name,
                                                originalSize - result.data().length,
                                                options.isKeepOriginal())))
                .onErrorResume(
                        error -> {
                            String message = "新文件已上传，后续处理失败: " + error.getMessage();
                            return logResult(displayName, result, originalSize, message)
                                    .then(journal.failed(taskId, name, displayName, message));
                        });
    }

    private Mono<Void> replaceReferences(Attachment original, Attachment uploaded) {
        String oldUrl = original.getStatus() == null ? null : original.getStatus().getPermalink();
        String newUrl = uploaded.getStatus() == null ? null : uploaded.getStatus().getPermalink();
        if (oldUrl == null || oldUrl.isBlank() || newUrl == null || newUrl.isBlank()) {
            return Mono.error(new IllegalStateException("附件链接不完整，无法迁移引用，已保留原附件"));
        }
        return references
                .replaceAfterBatchProcessing(
                        original.getMetadata().getName(),
                        uploaded.getMetadata().getName(),
                        oldUrl,
                        newUrl)
                .switchIfEmpty(Mono.error(new IllegalStateException("引用替换未返回结果")))
                .doOnNext(ReferenceReplacementResult::requireSuccess)
                .onErrorMap(
                        error ->
                                new IllegalStateException(
                                        "引用替换失败，已保留原附件: " + error.getMessage(), error))
                .then();
    }

    private Mono<ProcessingLog> logResult(
            String filename, ProcessingResult result, long originalSize, String error) {
        var entry = new ProcessingLog();
        entry.setMetadata(new Metadata());
        var spec = new ProcessingLog.ProcessingLogSpec();
        spec.setOriginalFilename(filename);
        spec.setResultFilename(result.filename());
        spec.setOriginalSize(originalSize);
        spec.setResultSize(result.data().length);
        spec.setStatus(error == null ? result.status() : ProcessingStatus.FAILED);
        spec.setProcessedAt(Instant.now());
        spec.setErrorMessage(error == null ? result.message() : error);
        spec.setSource(ProcessingSource.BATCH_PROCESSING);
        entry.setSpec(spec);
        return logs.save(entry);
    }

    private Mono<Boolean> isRemote(String policyName) {
        if (policyName == null || policyName.isEmpty()) return Mono.just(false);
        return client.fetch(Policy.class, policyName)
                .map(policy -> !"local".equals(policy.getSpec().getTemplateName()))
                .defaultIfEmpty(false);
    }
}
