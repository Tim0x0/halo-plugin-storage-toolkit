package com.timxs.storagetoolkit.service.impl;

import com.timxs.storagetoolkit.extension.UrlReplaceLog;
import com.timxs.storagetoolkit.model.ReplaceSource;
import com.timxs.storagetoolkit.service.UrlReplaceLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;

import java.time.Instant;
import java.util.UUID;

import static run.halo.app.extension.index.query.Queries.contains;
import static run.halo.app.extension.index.query.Queries.equal;
import static run.halo.app.extension.index.query.Queries.or;

/**
 * URL 替换日志服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UrlReplaceLogServiceImpl implements UrlReplaceLogService {

    private final ReactiveExtensionClient client;

    @Override
    public Mono<UrlReplaceLog> save(UrlReplaceLog logEntry) {
        if (logEntry.getMetadata() == null) {
            logEntry.setMetadata(new Metadata());
        }
        if (logEntry.getMetadata().getName() == null) {
            logEntry.getMetadata().setName(UUID.randomUUID().toString());
        }
        return client.create(logEntry);
    }

    @Override
    public Mono<UrlReplaceLog> saveSuccessLog(String oldUrl, String newUrl, String sourceType,
                                                String sourceName, String sourceTitle,
                                                String referenceType, ReplaceSource source) {
        UrlReplaceLog logEntry = new UrlReplaceLog();
        UrlReplaceLog.UrlReplaceLogSpec spec = new UrlReplaceLog.UrlReplaceLogSpec();
        spec.setOldUrl(oldUrl);
        spec.setNewUrl(newUrl);
        spec.setSourceType(sourceType);
        spec.setSourceName(sourceName);
        spec.setSourceTitle(sourceTitle);
        spec.setReferenceType(referenceType);
        spec.setSource(source);
        spec.setReplacedAt(Instant.now());
        spec.setSuccess(true);
        logEntry.setSpec(spec);
        return save(logEntry)
            .doOnNext(saved -> log.debug("替换日志已保存(成功): {} -> {} in {} {}",
                oldUrl, newUrl, sourceType, sourceName))
            .doOnError(error -> log.error("保存替换日志失败", error));
    }

    @Override
    public Mono<UrlReplaceLog> saveFailedLog(String oldUrl, String newUrl, String sourceType,
                                               String sourceName, String sourceTitle,
                                               String referenceType, ReplaceSource source,
                                               String errorMessage) {
        UrlReplaceLog logEntry = new UrlReplaceLog();
        UrlReplaceLog.UrlReplaceLogSpec spec = new UrlReplaceLog.UrlReplaceLogSpec();
        spec.setOldUrl(oldUrl);
        spec.setNewUrl(newUrl);
        spec.setSourceType(sourceType);
        spec.setSourceName(sourceName);
        spec.setSourceTitle(sourceTitle);
        spec.setReferenceType(referenceType);
        spec.setSource(source);
        spec.setReplacedAt(Instant.now());
        spec.setSuccess(false);
        spec.setErrorMessage(errorMessage);
        logEntry.setSpec(spec);
        return save(logEntry)
            .doOnNext(saved -> log.debug("替换日志已保存(失败): {} -> {} in {} {}: {}",
                oldUrl, newUrl, sourceType, sourceName, errorMessage))
            .doOnError(error -> log.error("保存替换日志失败", error));
    }

    @Override
    public Flux<UrlReplaceLog> list(int page, int size, ReplaceSource source, String keyword) {
        var listOptions = buildListOptions(source, keyword);
        var sort = Sort.by(Sort.Order.desc("spec.replacedAt"));

        var pageRequest = PageRequestImpl.of(page, size, sort);
        return client.listBy(UrlReplaceLog.class, listOptions, pageRequest)
            .flatMapMany(result -> Flux.fromIterable(result.getItems()));
    }

    @Override
    public Mono<Long> count(ReplaceSource source, String keyword) {
        var listOptions = buildListOptions(source, keyword);
        var sort = Sort.unsorted();

        var pageRequest = PageRequestImpl.ofSize(1).withSort(sort);
        return client.listBy(UrlReplaceLog.class, listOptions, pageRequest)
            .map(listResult -> listResult.getTotal());
    }

    @Override
    public Mono<UrlReplaceLogStats> getStats() {
        return client.listAll(UrlReplaceLog.class, ListOptions.builder().build(), Sort.unsorted())
            .filter(logEntry -> logEntry.getMetadata() == null
                || logEntry.getMetadata().getDeletionTimestamp() == null)
            .reduce(new long[]{0, 0, 0, 0, 0, 0}, (counts, logEntry) -> {
                counts[0]++; // total
                if (logEntry.getSpec() != null) {
                    if (logEntry.getSpec().isSuccess()) {
                        counts[1]++; // success
                    } else {
                        counts[2]++; // failed
                    }
                    ReplaceSource src = logEntry.getSpec().getSource();
                    if (src == ReplaceSource.BROKEN_LINK) {
                        counts[3]++;
                    } else if (src == ReplaceSource.DUPLICATE) {
                        counts[4]++;
                    } else if (src == ReplaceSource.BATCH_PROCESSING) {
                        counts[5]++;
                    }
                }
                return counts;
            })
            .map(counts -> new UrlReplaceLogStats(
                counts[0], counts[1], counts[2], counts[3], counts[4], counts[5]
            ));
    }

    @Override
    public Mono<Void> deleteExpired(int retentionDays) {
        Instant cutoff = Instant.now()
            .atZone(java.time.ZoneId.systemDefault())
            .minusDays(retentionDays)
            .toLocalDate()
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant();

        return client.listAll(UrlReplaceLog.class, ListOptions.builder().build(), Sort.unsorted())
            .filter(logEntry -> {
                if (logEntry.getMetadata() != null && logEntry.getMetadata().getDeletionTimestamp() != null) {
                    return false;
                }
                if (logEntry.getSpec() == null || logEntry.getSpec().getReplacedAt() == null) {
                    return false;
                }
                return logEntry.getSpec().getReplacedAt().isBefore(cutoff);
            })
            .flatMap(logEntry -> client.delete(logEntry).then(), 100)
            .then()
            .doOnSuccess(v -> log.info("已清理过期的 URL 替换日志，保留 {} 天", retentionDays));
    }

    @Override
    public Mono<Long> deleteAll() {
        return client.listAll(UrlReplaceLog.class, ListOptions.builder().build(), Sort.unsorted())
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
            .doOnSuccess(count -> log.info("已清空全部 {} 条 URL 替换日志", count));
    }

    /**
     * 构建查询选项
     * 使用 Queries + ListOptions.builder().andQuery() 链式方法动态组合条件
     */
    private ListOptions buildListOptions(ReplaceSource source, String keyword) {
        var builder = ListOptions.builder();

        // 来源精确匹配
        if (source != null) {
            builder.andQuery(equal("spec.source", source.name()));
        }

        // 关键词模糊搜索（旧 URL、内容标题任一匹配）
        if (StringUtils.hasText(keyword)) {
            builder.andQuery(or(
                contains("spec.oldUrl", keyword),
                contains("spec.sourceTitle", keyword)
            ));
        }

        return builder.build();
    }
}
