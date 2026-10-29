package com.timxs.storagetoolkit.service.batch;

import com.timxs.storagetoolkit.extension.BatchProcessingStatus;
import com.timxs.storagetoolkit.extension.BatchProcessingStatus.FailedItem;
import com.timxs.storagetoolkit.extension.BatchProcessingStatus.SkippedItem;
import com.timxs.storagetoolkit.model.ProcessingSource;
import com.timxs.storagetoolkit.service.ProcessingLogService;
import com.timxs.storagetoolkit.storage.BatchTaskStore;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.function.Consumer;

/** Persists each item's outcome and the point beyond which replay needs manual review. */
@Component
@RequiredArgsConstructor
public class BatchItemJournal {
    private final BatchTaskStore tasks;
    private final ProcessingLogService logs;

    public Mono<Void> beginSideEffects(String taskId, String name) {
        return tasks.update(
                        taskId,
                        task -> {
                            if (!task.getSpec().getReviewAttachmentNames().contains(name)) {
                                task.getSpec().getReviewAttachmentNames().add(name);
                            }
                        })
                .then();
    }

    public Mono<Void> succeeded(String taskId, String name, long savedBytes, boolean keepOriginal) {
        return complete(
                taskId,
                name,
                task -> {
                    var state = task.getStatus();
                    state.getProgress().setSucceeded(state.getProgress().getSucceeded() + 1);
                    state.setSavedBytes(state.getSavedBytes() + savedBytes);
                    if (keepOriginal) state.setKeptOriginalCount(state.getKeptOriginalCount() + 1);
                    task.getSpec().getReviewAttachmentNames().remove(name);
                });
    }

    public Mono<Void> failed(String taskId, String name, String displayName, String message) {
        return complete(
                taskId,
                name,
                task -> {
                    var state = task.getStatus();
                    state.getProgress().setFailed(state.getProgress().getFailed() + 1);
                    var failed = new FailedItem();
                    failed.setAttachmentName(name);
                    failed.setDisplayName(displayName);
                    failed.setError(message);
                    failed.setRetryable(!task.getSpec().getReviewAttachmentNames().contains(name));
                    state.getFailedItems().add(failed);
                });
    }

    public Mono<Void> skipped(
            String taskId, String name, String displayName, long size, String reason) {
        return logs.saveSkippedLog(
                        displayName,
                        null,
                        size,
                        Instant.now(),
                        reason,
                        ProcessingSource.BATCH_PROCESSING)
                .then(
                        complete(
                                taskId,
                                name,
                                task -> {
                                    task.getStatus()
                                            .setSkippedCount(
                                                    task.getStatus().getSkippedCount() + 1);
                                    var skipped = new SkippedItem();
                                    skipped.setAttachmentName(name);
                                    skipped.setDisplayName(displayName);
                                    skipped.setReason(reason);
                                    task.getStatus().getSkippedItems().add(skipped);
                                }));
    }

    private Mono<Void> complete(
            String taskId, String name, Consumer<BatchProcessingStatus> update) {
        return tasks.update(
                        taskId,
                        task -> {
                            if (task.getSpec().getCompletedAttachmentNames().contains(name)) return;
                            task.getSpec().getCompletedAttachmentNames().add(name);
                            var progress = task.getStatus().getProgress();
                            progress.setProcessed(progress.getProcessed() + 1);
                            update.accept(task);
                        })
                .then();
    }
}
