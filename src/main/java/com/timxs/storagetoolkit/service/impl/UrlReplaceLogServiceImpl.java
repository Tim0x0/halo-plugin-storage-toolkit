package com.timxs.storagetoolkit.service.impl;

import com.timxs.storagetoolkit.extension.UrlReplaceLog;
import com.timxs.storagetoolkit.model.ReplaceSource;
import com.timxs.storagetoolkit.service.UrlReplaceLogService;
import com.timxs.storagetoolkit.storage.SqliteLogStore;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/** URL 替换日志服务实现 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UrlReplaceLogServiceImpl implements UrlReplaceLogService {

    private final SqliteLogStore logs;

    @Override
    public Mono<UrlReplaceLog> save(UrlReplaceLog entry) {
        return logs.save(entry);
    }

    @Override
    public Mono<UrlReplaceLog> saveSuccessLog(
            String oldUrl,
            String newUrl,
            String sourceType,
            String sourceName,
            String sourceTitle,
            String referenceType,
            ReplaceSource source) {
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
                .doOnNext(
                        saved ->
                                log.debug(
                                        "替换日志已保存(成功): {} -> {} in {} {}",
                                        oldUrl,
                                        newUrl,
                                        sourceType,
                                        sourceName))
                .doOnError(error -> log.error("保存替换日志失败", error));
    }

    @Override
    public Mono<UrlReplaceLog> saveFailedLog(
            String oldUrl,
            String newUrl,
            String sourceType,
            String sourceName,
            String sourceTitle,
            String referenceType,
            ReplaceSource source,
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
                .doOnNext(
                        saved ->
                                log.debug(
                                        "替换日志已保存(失败): {} -> {} in {} {}: {}",
                                        oldUrl,
                                        newUrl,
                                        sourceType,
                                        sourceName,
                                        errorMessage))
                .doOnError(error -> log.error("保存替换日志失败", error));
    }

    private SqliteLogStore.Filter filter(ReplaceSource source, String keyword) {
        return new SqliteLogStore.Filter(
                null, source == null ? null : source.name(), null, keyword);
    }

    @Override
    public Flux<UrlReplaceLog> list(int page, int size, ReplaceSource source, String keyword) {
        return logs.list(UrlReplaceLog.class, filter(source, keyword), page, size);
    }

    @Override
    public Mono<Long> count(ReplaceSource source, String keyword) {
        return logs.count(UrlReplaceLog.class, filter(source, keyword));
    }

    @Override
    public Mono<UrlReplaceLogStats> getStats() {
        return logs.stats(UrlReplaceLog.class)
                .map(c -> new UrlReplaceLogStats(c[0], c[1], c[2], c[3], c[4], c[5]));
    }

    @Override
    public Mono<Void> deleteExpired(int days) {
        return logs.deleteExpired(UrlReplaceLog.class, days);
    }

    @Override
    public Mono<Long> deleteAll() {
        return logs.deleteAll(UrlReplaceLog.class);
    }
}
