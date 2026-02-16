package com.timxs.storagetoolkit.service.impl;

import com.timxs.storagetoolkit.extension.CleanupLog;
import com.timxs.storagetoolkit.model.CleanupReason;
import com.timxs.storagetoolkit.service.CleanupLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;

import java.time.Instant;
import java.time.ZoneId;

import static run.halo.app.extension.index.query.Queries.contains;
import static run.halo.app.extension.index.query.Queries.equal;

/**
 * 清理日志服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CleanupLogServiceImpl implements CleanupLogService {

    private final ReactiveExtensionClient client;

    @Override
    public Mono<CleanupLog> save(CleanupLog logEntry) {
        if (logEntry.getMetadata() == null) {
            logEntry.setMetadata(new Metadata());
        }
        if (logEntry.getMetadata().getName() == null && logEntry.getMetadata().getGenerateName() == null) {
            logEntry.getMetadata().setGenerateName("cleanup-log-");
        }
        return client.create(logEntry);
    }

    @Override
    public Mono<CleanupLog> saveLog(String attachmentName, String displayName,
                                     long size, CleanupReason reason, String errorMessage) {
        return getCurrentUsername()
            .flatMap(operator -> {
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

                return client.create(logEntry);
            });
    }

    @Override
    public Flux<CleanupLog> list(int page, int size, CleanupReason reason, String filename) {
        var listOptions = buildListOptions(reason, filename);
        var sort = Sort.by(Sort.Order.desc("spec.deletedAt"));

        var pageRequest = PageRequestImpl.of(page, size, sort);
        return client.listBy(CleanupLog.class, listOptions, pageRequest)
            .flatMapMany(result -> Flux.fromIterable(result.getItems()));
    }

    @Override
    public Mono<Long> count(CleanupReason reason, String filename) {
        var listOptions = buildListOptions(reason, filename);
        var sort = Sort.unsorted();

        var pageRequest = PageRequestImpl.ofSize(1).withSort(sort);
        return client.listBy(CleanupLog.class, listOptions, pageRequest)
            .map(listResult -> listResult.getTotal());
    }

    @Override
    public Mono<CleanupLogStats> getStats() {
        return client.listAll(CleanupLog.class, ListOptions.builder().build(), Sort.unsorted())
            .filter(logEntry -> logEntry.getMetadata() == null
                || logEntry.getMetadata().getDeletionTimestamp() == null)
            .reduce(new long[]{0, 0, 0, 0}, (counts, logEntry) -> {
                counts[0]++; // totalCount
                if (logEntry.getSpec() != null) {
                    counts[3] += logEntry.getSpec().getSize(); // freedBytes
                    if (logEntry.getSpec().getReason() == CleanupReason.DUPLICATE) {
                        counts[1]++; // duplicateCount
                    } else if (logEntry.getSpec().getReason() == CleanupReason.UNREFERENCED) {
                        counts[2]++; // unreferencedCount
                    }
                }
                return counts;
            })
            .map(counts -> new CleanupLogStats(
                counts[0], counts[1], counts[2], counts[3]
            ));
    }

    @Override
    public Mono<Void> deleteExpired(int retentionDays) {
        // 计算截止日期（当天 0 点），删除该日期之前的所有日志
        Instant cutoff = Instant.now()
            .atZone(ZoneId.systemDefault())
            .minusDays(retentionDays)
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant();

        return client.listAll(CleanupLog.class, ListOptions.builder().build(), Sort.unsorted())
            .filter(logEntry -> {
                // 跳过已标记删除的
                if (logEntry.getMetadata() != null && logEntry.getMetadata().getDeletionTimestamp() != null) {
                    return false;
                }
                if (logEntry.getSpec() == null || logEntry.getSpec().getDeletedAt() == null) {
                    return false;
                }
                // 只删除超过保留期限的日志
                return logEntry.getSpec().getDeletedAt().isBefore(cutoff);
            })
            .flatMap(logEntry -> client.delete(logEntry).then(), 100)
            .then()
            .doOnSuccess(v -> log.info("已清理过期清理日志，保留 {} 天", retentionDays));
    }

    @Override
    public Mono<Long> deleteAll() {
        return client.listAll(CleanupLog.class, ListOptions.builder().build(), Sort.unsorted())
            // 过滤掉已标记删除的
            .filter(logEntry -> logEntry.getMetadata() == null
                || logEntry.getMetadata().getDeletionTimestamp() == null)
            .collectList()
            .flatMap(logs -> {
                if (logs.isEmpty()) {
                    return Mono.just(0L);
                }
                long count = logs.size();
                return Flux.fromIterable(logs)
                    .flatMap(logEntry -> client.delete(logEntry), 100)
                    .then(Mono.just(count));
            })
            .doOnSuccess(count -> log.info("已清空全部 {} 条清理日志", count));
    }

    /**
     * 构建查询选项
     */
    private ListOptions buildListOptions(CleanupReason reason, String filename) {
        var builder = ListOptions.builder();

        // 按原因过滤
        if (reason != null) {
            builder.andQuery(equal("spec.reason", reason.name()));
        }

        // 文件名模糊搜索
        if (StringUtils.hasText(filename)) {
            builder.andQuery(contains("spec.displayName", filename));
        }

        return builder.build();
    }

    /**
     * 获取当前登录用户名
     */
    private Mono<String> getCurrentUsername() {
        return ReactiveSecurityContextHolder.getContext()
            .map(ctx -> ctx.getAuthentication())
            .filter(auth -> auth != null && auth.isAuthenticated())
            .map(auth -> auth.getName())
            .defaultIfEmpty("system");
    }
}
