package com.timxs.storagetoolkit.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timxs.storagetoolkit.extension.CleanupLog;
import com.timxs.storagetoolkit.extension.ProcessingLog;
import com.timxs.storagetoolkit.extension.UrlReplaceLog;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Sort;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * 三个日志服务 deleteExpired 的单元测试（实现同模式，统一覆盖）
 * 验证过期清理走时间索引范围查询（Queries.lessThan）而非无条件全量遍历，
 * 且只删除查询结果中未标记删除（deletionTimestamp 为空）的记录
 */
class LogDeleteExpiredTest {

    private static final int RETENTION_DAYS = 30;

    private ReactiveExtensionClient client;

    @BeforeEach
    void setUp() {
        client = mock(ReactiveExtensionClient.class);
    }

    @Test
    void processingLogShouldDeleteExpiredViaTimeIndexQuery() {
        var service = new ProcessingLogServiceImpl(client);
        when(client.listAll(eq(ProcessingLog.class), any(ListOptions.class), any(Sort.class)))
            .thenReturn(Flux.just(
                processingLog("expired-1", false),
                processingLog("marked-for-deletion", true)));
        when(client.delete(any(ProcessingLog.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        var expectedBefore = expectedCondition("spec.processedAt");
        service.deleteExpired(RETENTION_DAYS).block(Duration.ofSeconds(10));
        var expectedAfter = expectedCondition("spec.processedAt");

        // 查询条件必须是时间索引上的 lessThan 范围查询
        var optionsCaptor = ArgumentCaptor.forClass(ListOptions.class);
        verify(client).listAll(eq(ProcessingLog.class), optionsCaptor.capture(), any(Sort.class));
        // cutoff 在调用前后各算一次，避免测试恰好跨过午夜时误报
        assertThat(optionsCaptor.getValue().toString())
            .isIn(expectedBefore, expectedAfter);

        // 已标记删除的记录不再重复 delete
        var deleteCaptor = ArgumentCaptor.forClass(ProcessingLog.class);
        verify(client, times(1)).delete(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().getMetadata().getName()).isEqualTo("expired-1");
    }

    @Test
    void cleanupLogShouldDeleteExpiredViaTimeIndexQuery() {
        var service = new CleanupLogServiceImpl(client);
        when(client.listAll(eq(CleanupLog.class), any(ListOptions.class), any(Sort.class)))
            .thenReturn(Flux.just(cleanupLog("expired-1", false)));
        when(client.delete(any(CleanupLog.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        var expectedBefore = expectedCondition("spec.deletedAt");
        service.deleteExpired(RETENTION_DAYS).block(Duration.ofSeconds(10));
        var expectedAfter = expectedCondition("spec.deletedAt");

        var optionsCaptor = ArgumentCaptor.forClass(ListOptions.class);
        verify(client).listAll(eq(CleanupLog.class), optionsCaptor.capture(), any(Sort.class));
        assertThat(optionsCaptor.getValue().toString()).isIn(expectedBefore, expectedAfter);
        verify(client, times(1)).delete(any(CleanupLog.class));
    }

    @Test
    void urlReplaceLogShouldDeleteExpiredViaTimeIndexQuery() {
        var service = new UrlReplaceLogServiceImpl(client);
        when(client.listAll(eq(UrlReplaceLog.class), any(ListOptions.class), any(Sort.class)))
            .thenReturn(Flux.just(urlReplaceLog("expired-1", false)));
        when(client.delete(any(UrlReplaceLog.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        var expectedBefore = expectedCondition("spec.replacedAt");
        service.deleteExpired(RETENTION_DAYS).block(Duration.ofSeconds(10));
        var expectedAfter = expectedCondition("spec.replacedAt");

        var optionsCaptor = ArgumentCaptor.forClass(ListOptions.class);
        verify(client).listAll(eq(UrlReplaceLog.class), optionsCaptor.capture(), any(Sort.class));
        assertThat(optionsCaptor.getValue().toString()).isIn(expectedBefore, expectedAfter);
        verify(client, times(1)).delete(any(UrlReplaceLog.class));
    }

    /**
     * 与服务实现相同的 cutoff 计算，拼出 LessThanCondition.toString() 的期望形式
     */
    private static String expectedCondition(String fieldName) {
        Instant cutoff = Instant.now()
            .atZone(ZoneId.systemDefault())
            .minusDays(RETENTION_DAYS)
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant();
        return fieldName + " < " + cutoff;
    }

    private static ProcessingLog processingLog(String name, boolean markedForDeletion) {
        var logEntry = new ProcessingLog();
        logEntry.setMetadata(metadata(name, markedForDeletion));
        return logEntry;
    }

    private static CleanupLog cleanupLog(String name, boolean markedForDeletion) {
        var logEntry = new CleanupLog();
        logEntry.setMetadata(metadata(name, markedForDeletion));
        return logEntry;
    }

    private static UrlReplaceLog urlReplaceLog(String name, boolean markedForDeletion) {
        var logEntry = new UrlReplaceLog();
        logEntry.setMetadata(metadata(name, markedForDeletion));
        return logEntry;
    }

    private static Metadata metadata(String name, boolean markedForDeletion) {
        var metadata = new Metadata();
        metadata.setName(name);
        if (markedForDeletion) {
            metadata.setDeletionTimestamp(Instant.now());
        }
        return metadata;
    }
}
