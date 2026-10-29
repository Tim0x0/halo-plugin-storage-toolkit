package com.timxs.storagetoolkit.service.impl;

import com.timxs.storagetoolkit.extension.CleanupLog;
import com.timxs.storagetoolkit.model.CleanupReason;
import com.timxs.storagetoolkit.service.CleanupLogService;
import com.timxs.storagetoolkit.storage.SqliteLogStore;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import run.halo.app.extension.Metadata;

import java.time.Instant;

/** 清理日志服务实现 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CleanupLogServiceImpl implements CleanupLogService {

    private final SqliteLogStore logs;

    @Override
    public Mono<CleanupLog> save(CleanupLog entry) {
        return logs.save(entry);
    }

    @Override
    public Mono<CleanupLog> saveLog(
            String attachmentName,
            String displayName,
            long size,
            CleanupReason reason,
            String errorMessage) {
        return getCurrentUsername()
                .flatMap(
                        operator -> {
                            CleanupLog logEntry = new CleanupLog();
                            logEntry.setMetadata(new Metadata());
                            logEntry.getMetadata().setGenerateName("cleanup-log-");

                            CleanupLog.CleanupLogSpec spec = new CleanupLog.CleanupLogSpec();
                            spec.setAttachmentName(attachmentName);
                            spec.setDisplayName(displayName);
                            spec.setSize(size);
                            spec.setReason(reason);
                            spec.setOperator(operator);
                            spec.setDeletedAt(Instant.now());
                            spec.setErrorMessage(errorMessage);
                            logEntry.setSpec(spec);

                            return save(logEntry);
                        });
    }

    private SqliteLogStore.Filter filter(CleanupReason reason, String filename) {
        return new SqliteLogStore.Filter(
                null, null, reason == null ? null : reason.name(), filename);
    }

    @Override
    public Flux<CleanupLog> list(int page, int size, CleanupReason reason, String filename) {
        return logs.list(CleanupLog.class, filter(reason, filename), page, size);
    }

    @Override
    public Mono<Long> count(CleanupReason reason, String filename) {
        return logs.count(CleanupLog.class, filter(reason, filename));
    }

    @Override
    public Mono<CleanupLogStats> getStats() {
        return logs.stats(CleanupLog.class).map(c -> new CleanupLogStats(c[0], c[1], c[2], c[3]));
    }

    @Override
    public Mono<Void> deleteExpired(int days) {
        return logs.deleteExpired(CleanupLog.class, days);
    }

    @Override
    public Mono<Long> deleteAll() {
        return logs.deleteAll(CleanupLog.class);
    }

    private Mono<String> getCurrentUsername() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(auth -> auth != null && auth.isAuthenticated())
                .map(auth -> auth.getName())
                .defaultIfEmpty("system");
    }
}
