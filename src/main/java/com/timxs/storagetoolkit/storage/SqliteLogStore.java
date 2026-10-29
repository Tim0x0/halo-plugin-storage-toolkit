package com.timxs.storagetoolkit.storage;

import com.timxs.storagetoolkit.extension.CleanupLog;
import com.timxs.storagetoolkit.extension.ProcessingLog;
import com.timxs.storagetoolkit.extension.UrlReplaceLog;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.Metadata;
import run.halo.app.infra.utils.JsonUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Indexed log queries shared by the three existing log services. */
@Component
@RequiredArgsConstructor
public class SqliteLogStore {
    private final ToolkitDatabase database;

    public <T extends AbstractExtension> Mono<T> save(T entry) {
        return database.read(
                connection -> {
                    if (entry.getMetadata() == null) entry.setMetadata(new Metadata());
                    if (entry.getMetadata().getName() == null)
                        entry.getMetadata().setName(UUID.randomUUID().toString());
                    if (entry.getMetadata().getCreationTimestamp() == null)
                        entry.getMetadata().setCreationTimestamp(Instant.now());
                    Fields fields = fields(entry);
                    try (var stmt =
                            connection.prepareStatement(
                                    """
                                    INSERT INTO operation_logs(kind,name,event_time,category,source,search_text,alt_text,outcome,bytes,payload)
                                    VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(kind,name) DO NOTHING
                                    """)) {
                        stmt.setString(1, entry.getClass().getSimpleName());
                        stmt.setString(2, entry.getMetadata().getName());
                        stmt.setLong(3, fields.time().toEpochMilli());
                        stmt.setString(4, fields.category());
                        stmt.setString(5, fields.source());
                        stmt.setString(6, fields.search());
                        stmt.setString(7, fields.alt());
                        stmt.setString(8, fields.outcome());
                        stmt.setLong(9, fields.bytes());
                        stmt.setString(10, JsonUtils.objectToJson(entry));
                        stmt.executeUpdate();
                    }
                    return entry;
                });
    }

    public <T> Mono<T> get(Class<T> type, String name) {
        return database.read(
                connection -> {
                    try (var stmt =
                            connection.prepareStatement(
                                    "SELECT payload FROM operation_logs WHERE kind=? AND name=?")) {
                        stmt.setString(1, type.getSimpleName());
                        stmt.setString(2, name);
                        try (var rows = stmt.executeQuery()) {
                            return rows.next()
                                    ? JsonUtils.jsonToObject(rows.getString(1), type)
                                    : null;
                        }
                    }
                });
    }

    public <T> Flux<T> list(Class<T> type, Filter filter, int page, int size) {
        return database.read(
                        connection -> {
                            List<T> result = new ArrayList<>();
                            try (var stmt =
                                    connection.prepareStatement(
                                            "SELECT payload FROM operation_logs "
                                                    + where()
                                                    + " ORDER BY event_time DESC, name DESC LIMIT ?"
                                                    + " OFFSET ?")) {
                                bind(stmt, type, filter);
                                stmt.setInt(11, Math.max(1, Math.min(size, 200)));
                                stmt.setLong(
                                        12,
                                        (long) (Math.max(1, page) - 1)
                                                * Math.max(1, Math.min(size, 200)));
                                try (var rows = stmt.executeQuery()) {
                                    while (rows.next())
                                        result.add(JsonUtils.jsonToObject(rows.getString(1), type));
                                }
                            }
                            return result;
                        })
                .flatMapMany(Flux::fromIterable);
    }

    public Mono<Long> count(Class<?> type, Filter filter) {
        return database.read(
                connection -> {
                    try (var stmt =
                            connection.prepareStatement(
                                    "SELECT COUNT(*) FROM operation_logs " + where())) {
                        bind(stmt, type, filter);
                        try (var rows = stmt.executeQuery()) {
                            rows.next();
                            return rows.getLong(1);
                        }
                    }
                });
    }

    public Mono<long[]> stats(Class<?> type) {
        String aggregates =
                type == ProcessingLog.class
                        ? "COUNT(*),SUM(outcome='SUCCESS'),SUM(outcome='FAILED'),SUM(outcome='SKIPPED'),SUM(outcome='PARTIAL'),SUM(bytes)"
                        : type == CleanupLog.class
                                ? "COUNT(*),SUM(category='DUPLICATE'),SUM(category='UNREFERENCED'),SUM(bytes)"
                                : "COUNT(*),SUM(outcome='SUCCESS'),SUM(outcome='FAILED'),SUM(source='BROKEN_LINK'),SUM(source='DUPLICATE'),SUM(source='BATCH_PROCESSING')";
        return database.read(
                connection -> {
                    try (var stmt =
                            connection.prepareStatement(
                                    "SELECT " + aggregates + " FROM operation_logs WHERE kind=?")) {
                        stmt.setString(1, type.getSimpleName());
                        try (var rows = stmt.executeQuery()) {
                            rows.next();
                            long[] values = new long[rows.getMetaData().getColumnCount()];
                            for (int i = 0; i < values.length; i++) values[i] = rows.getLong(i + 1);
                            return values;
                        }
                    }
                });
    }

    public Mono<Long> deleteAll(Class<?> type) {
        return deleteBefore(type, Long.MAX_VALUE);
    }

    public Mono<Void> deleteExpired(Class<?> type, int days) {
        long cutoff =
                Instant.now()
                        .atZone(ZoneId.systemDefault())
                        .minusDays(days)
                        .toLocalDate()
                        .atStartOfDay(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli();
        return deleteBefore(type, cutoff).then();
    }

    private Mono<Long> deleteBefore(Class<?> type, long cutoff) {
        return database.read(
                connection -> {
                    try (var stmt =
                            connection.prepareStatement(
                                    "DELETE FROM operation_logs WHERE kind=? AND event_time<?")) {
                        stmt.setString(1, type.getSimpleName());
                        stmt.setLong(2, cutoff);
                        return (long) stmt.executeUpdate();
                    }
                });
    }

    private String where() {
        return "WHERE kind=? AND (? IS NULL OR outcome=?) AND (? IS NULL OR source=?) AND (? IS"
                   + " NULL OR category=?) AND (?='' OR instr(coalesce(search_text,''),?)>0 OR"
                   + " instr(coalesce(alt_text,''),?)>0)";
    }

    private void bind(java.sql.PreparedStatement stmt, Class<?> type, Filter filter)
            throws Exception {
        stmt.setString(1, type.getSimpleName());
        stmt.setString(2, filter.outcome());
        stmt.setString(3, filter.outcome());
        stmt.setString(4, filter.source());
        stmt.setString(5, filter.source());
        stmt.setString(6, filter.category());
        stmt.setString(7, filter.category());
        String keyword = filter.keyword() == null ? "" : filter.keyword();
        stmt.setString(8, keyword);
        stmt.setString(9, keyword);
        stmt.setString(10, keyword);
    }

    private Fields fields(AbstractExtension entry) {
        if (entry instanceof ProcessingLog log) {
            var s = log.getSpec();
            return new Fields(
                    time(s.getProcessedAt()),
                    null,
                    name(s.getSource()),
                    s.getOriginalFilename(),
                    null,
                    name(s.getStatus()),
                    Math.max(0, s.getOriginalSize() - s.getResultSize()));
        }
        if (entry instanceof CleanupLog log) {
            var s = log.getSpec();
            return new Fields(
                    time(s.getDeletedAt()),
                    name(s.getReason()),
                    null,
                    s.getDisplayName(),
                    null,
                    s.getErrorMessage() == null ? "SUCCESS" : "FAILED",
                    s.getErrorMessage() == null ? s.getSize() : 0);
        }
        if (entry instanceof UrlReplaceLog log) {
            var s = log.getSpec();
            return new Fields(
                    time(s.getReplacedAt()),
                    null,
                    name(s.getSource()),
                    s.getOldUrl(),
                    s.getSourceTitle(),
                    s.isSuccess() ? "SUCCESS" : "FAILED",
                    0);
        }
        throw new IllegalArgumentException("不支持的日志类型");
    }

    private Instant time(Instant time) {
        return time == null ? Instant.now() : time;
    }

    private String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    public record Filter(String outcome, String source, String category, String keyword) {}

    private record Fields(
            Instant time,
            String category,
            String source,
            String search,
            String alt,
            String outcome,
            long bytes) {}
}
