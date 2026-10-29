package com.timxs.storagetoolkit.storage;

import com.timxs.storagetoolkit.extension.CleanupLog;
import com.timxs.storagetoolkit.extension.ProcessingLog;
import com.timxs.storagetoolkit.extension.UrlReplaceLog;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.utils.JsonUtils;

import java.time.Duration;

/** Copy, verify and then delete legacy records. Interrupted migrations can safely restart. */
@Component
@RequiredArgsConstructor
public class LegacyLogMigration {
    private final ReactiveExtensionClient client;
    private final SqliteLogStore logs;
    private final ToolkitDatabase database;
    private final LegacyTaskMigration taskMigration;

    public void migrate() {
        migrateType(ProcessingLog.class)
                .then(migrateType(CleanupLog.class))
                .then(migrateType(UrlReplaceLog.class))
                .then(taskMigration.migrate())
                .block(Duration.ofMinutes(10));
    }

    private <T extends AbstractExtension> Mono<Void> migrateType(Class<T> type) {
        return hasMigrated(type)
                .flatMap(
                        done ->
                                done
                                        ? Mono.empty()
                                        : client.listAll(
                                                        type,
                                                        ListOptions.builder().build(),
                                                        Sort.unsorted())
                                                .filter(
                                                        entry ->
                                                                entry.getMetadata()
                                                                                .getDeletionTimestamp()
                                                                        == null)
                                                .concatMap(entry -> copyAndDelete(type, entry))
                                                .then(markMigrated(type)));
    }

    private <T extends AbstractExtension> Mono<Void> copyAndDelete(Class<T> type, T entry) {
        return logs.save(entry)
                .flatMap(saved -> logs.get(type, saved.getMetadata().getName()))
                .switchIfEmpty(Mono.error(new IllegalStateException("迁移日志丢失")))
                .flatMap(
                        saved -> {
                            if (!JsonUtils.objectToJson(saved)
                                    .equals(JsonUtils.objectToJson(entry))) {
                                return Mono.error(
                                        new IllegalStateException(
                                                "日志迁移核验失败: " + entry.getMetadata().getName()));
                            }
                            return client.delete(entry);
                        })
                .then();
    }

    private Mono<Boolean> hasMigrated(Class<?> type) {
        return database.read(
                connection -> {
                    try (var stmt =
                            connection.prepareStatement(
                                    "SELECT value FROM toolkit_metadata WHERE name=?")) {
                        stmt.setString(1, "migrated-" + type.getSimpleName());
                        try (var rows = stmt.executeQuery()) {
                            return rows.next();
                        }
                    }
                });
    }

    private Mono<Void> markMigrated(Class<?> type) {
        return database.read(
                        connection -> {
                            try (var stmt =
                                    connection.prepareStatement(
                                            "INSERT INTO toolkit_metadata(name,value) VALUES (?,"
                                                + " 'true')")) {
                                stmt.setString(1, "migrated-" + type.getSimpleName());
                                stmt.executeUpdate();
                            }
                            return true;
                        })
                .then();
    }
}
