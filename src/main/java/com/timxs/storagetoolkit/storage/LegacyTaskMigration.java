package com.timxs.storagetoolkit.storage;

import com.timxs.storagetoolkit.extension.BatchProcessingStatus;
import com.timxs.storagetoolkit.extension.BatchProcessingStatus.Phase;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Preserves old singleton history without replaying unknown side effects. */
@Component
@RequiredArgsConstructor
public class LegacyTaskMigration {
    private final ReactiveExtensionClient client;
    private final BatchTaskStore tasks;

    public Mono<Void> migrate() {
        return tasks.get("legacy-batch-task")
                .hasElement()
                .flatMap(
                        exists ->
                                exists
                                        ? Mono.empty()
                                        : client.fetch(
                                                        BatchProcessingStatus.class,
                                                        BatchProcessingStatus.SINGLETON_NAME)
                                                .filter(
                                                        task ->
                                                                task.getStatus() != null
                                                                        && task.getStatus()
                                                                                        .getPhase()
                                                                                != null
                                                                        && task.getSpec() != null)
                                                .map(this::toHistory)
                                                .flatMap(tasks::save)
                                                .then());
    }

    private BatchProcessingStatus toHistory(BatchProcessingStatus task) {
        var metadata = new Metadata();
        metadata.setName("legacy-batch-task");
        metadata.setCreationTimestamp(
                task.getStatus().getStartTime() == null
                        ? Instant.EPOCH
                        : task.getStatus().getStartTime());
        task.setMetadata(metadata);
        var spec = task.getSpec();
        if (spec.getAttachmentNames() == null) spec.setAttachmentNames(List.of());
        spec.setReviewAttachmentNames(new ArrayList<>(spec.getAttachmentNames()));
        spec.setCompletedAttachmentNames(new ArrayList<>(spec.getAttachmentNames()));
        var state = task.getStatus();
        if (state.getFailedItems() == null) state.setFailedItems(new ArrayList<>());
        if (state.getSkippedItems() == null) state.setSkippedItems(new ArrayList<>());
        state.getFailedItems().forEach(item -> item.setRetryable(false));
        if (state.getPhase() == Phase.COMPLETED) spec.getReviewAttachmentNames().clear();
        else {
            state.setPhase(Phase.INTERRUPTED);
            state.setErrorMessage("旧版任务没有逐项进度与配置快照，请先核查结果后重新选择附件处理");
        }
        return task;
    }
}
