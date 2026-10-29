package com.timxs.storagetoolkit.storage;

import com.timxs.storagetoolkit.extension.BatchProcessingStatus;
import com.timxs.storagetoolkit.extension.BatchProcessingStatus.Phase;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import run.halo.app.extension.ListResult;
import run.halo.app.infra.utils.JsonUtils;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Durable task snapshots. Mutations are serialized with the SQLite write itself. */
@Component
@RequiredArgsConstructor
public class BatchTaskStore {
    private final ToolkitDatabase database;

    public Mono<BatchProcessingStatus> save(BatchProcessingStatus task) {
        return database.read(
                connection -> {
                    var existing = load(connection, task.getMetadata().getName());
                    if (existing != null
                            && (!java.util.Objects.equals(
                                            existing.getMetadata().getVersion(),
                                            task.getMetadata().getVersion())
                                    || existing.getStatus().getPhase() == Phase.PROCESSING
                                    || existing.getStatus().getPhase() == Phase.PENDING
                                    || existing.getStatus().getPhase() == Phase.CANCELLING)) {
                        throw new IllegalStateException("任务状态已变化，请刷新后重试");
                    }
                    write(connection, task);
                    try (var stmt =
                            connection.prepareStatement(
                                    "INSERT INTO toolkit_metadata(name,value) VALUES"
                                        + " ('current-task',?) ON CONFLICT(name) DO UPDATE SET"
                                        + " value=excluded.value")) {
                        stmt.setString(1, task.getMetadata().getName());
                        stmt.executeUpdate();
                    }
                    return task;
                });
    }

    public Mono<BatchProcessingStatus> get(String name) {
        return database.read(connection -> load(connection, name));
    }

    public Mono<BatchProcessingStatus> latest() {
        return database.read(
                connection -> {
                    try (var stmt = connection.createStatement();
                            var rows =
                                    stmt.executeQuery(
                                            "SELECT payload FROM batch_tasks ORDER BY (name=(SELECT"
                                                + " value FROM toolkit_metadata WHERE"
                                                + " name='current-task')) DESC, created DESC, name"
                                                + " DESC LIMIT 1")) {
                        return rows.next()
                                ? JsonUtils.jsonToObject(
                                        rows.getString(1), BatchProcessingStatus.class)
                                : null;
                    }
                });
    }

    public Mono<BatchProcessingStatus> update(String name, Consumer<BatchProcessingStatus> change) {
        return database.read(
                connection -> {
                    var task = load(connection, name);
                    if (task == null) throw new IllegalArgumentException("任务不存在");
                    change.accept(task);
                    write(connection, task);
                    return task;
                });
    }

    public Mono<ListResult<BatchProcessingStatus>> list(int page, int size) {
        int safePage = Math.max(1, page), safeSize = Math.min(100, Math.max(1, size));
        return database.read(
                connection -> {
                    List<BatchProcessingStatus> tasks = new ArrayList<>();
                    long total;
                    try (var stmt = connection.createStatement();
                            var rows = stmt.executeQuery("SELECT COUNT(*) FROM batch_tasks")) {
                        rows.next();
                        total = rows.getLong(1);
                    }
                    try (var stmt =
                            connection.prepareStatement(
                                    "SELECT payload FROM batch_tasks ORDER BY created DESC, name"
                                        + " DESC LIMIT ? OFFSET ?")) {
                        stmt.setInt(1, safeSize);
                        stmt.setLong(2, (long) (safePage - 1) * safeSize);
                        try (var rows = stmt.executeQuery()) {
                            while (rows.next())
                                tasks.add(
                                        JsonUtils.jsonToObject(
                                                rows.getString(1), BatchProcessingStatus.class));
                        }
                    }
                    return new ListResult<>(safePage, safeSize, total, tasks);
                });
    }

    public void recoverInterrupted() {
        database.read(
                        connection -> {
                            List<BatchProcessingStatus> tasks = new ArrayList<>();
                            try (var stmt = connection.createStatement();
                                    var rows =
                                            stmt.executeQuery("SELECT payload FROM batch_tasks")) {
                                while (rows.next())
                                    tasks.add(
                                            JsonUtils.jsonToObject(
                                                    rows.getString(1),
                                                    BatchProcessingStatus.class));
                            }
                            for (var task : tasks) {
                                var state = task.getStatus();
                                if (state.getPhase() == Phase.PENDING
                                        || state.getPhase() == Phase.PROCESSING
                                        || state.getPhase() == Phase.CANCELLING) {
                                    state.setPhase(Phase.INTERRUPTED);
                                    state.setErrorMessage("服务已重启，可继续未执行项；涉及上传或引用替换的中断项需先人工核查");
                                    write(connection, task);
                                }
                            }
                            return true;
                        })
                .block();
    }

    private BatchProcessingStatus load(Connection connection, String name) throws Exception {
        try (var stmt =
                connection.prepareStatement("SELECT payload FROM batch_tasks WHERE name=?")) {
            stmt.setString(1, name);
            try (var rows = stmt.executeQuery()) {
                return rows.next()
                        ? JsonUtils.jsonToObject(rows.getString(1), BatchProcessingStatus.class)
                        : null;
            }
        }
    }

    private void write(Connection connection, BatchProcessingStatus task) throws Exception {
        Long version = task.getMetadata().getVersion();
        task.getMetadata().setVersion(version == null ? 1 : version + 1);
        try (var stmt =
                connection.prepareStatement(
                        "INSERT INTO batch_tasks(name,created,payload) VALUES (?,?,?) ON"
                            + " CONFLICT(name) DO UPDATE SET payload=excluded.payload")) {
            stmt.setString(1, task.getMetadata().getName());
            stmt.setLong(2, task.getMetadata().getCreationTimestamp().toEpochMilli());
            stmt.setString(3, JsonUtils.objectToJson(task));
            stmt.executeUpdate();
        }
    }
}
