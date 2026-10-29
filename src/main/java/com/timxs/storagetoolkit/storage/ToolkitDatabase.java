package com.timxs.storagetoolkit.storage;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;
import org.sqlite.SQLiteConfig;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import run.halo.app.plugin.PluginsRootGetter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

/** Owns the plugin's SQLite file and keeps blocking JDBC off WebFlux threads. */
@Component
public class ToolkitDatabase {
    private final Path path;
    private final Scheduler scheduler =
            Schedulers.newBoundedElastic(1, 10000, "storage-toolkit-db");
    private boolean initialized;
    private boolean closed;
    private volatile String unavailableReason;

    public void suspend(String message) { unavailableReason = message; }
    public void allowNativeAccess() { unavailableReason = null; }

    public ToolkitDatabase(PluginsRootGetter roots) {
        this.path = roots.get().resolve("storage-toolkit").resolve("toolkit.sqlite");
    }

    public <T> Mono<T> read(SqlWork<T> work) {
        return Mono.fromCallable(() -> execute(work)).subscribeOn(scheduler);
    }

    public synchronized <T> T execute(SqlWork<T> work) throws Exception {
        if (unavailableReason != null) throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, unavailableReason);
        if (closed) throw new IllegalStateException("日志数据库已关闭");
        Files.createDirectories(path.getParent());
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(10000);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        try (Connection connection = config.createConnection("jdbc:sqlite:" + path)) {
            if (!initialized) {
                try (var statement = connection.createStatement()) {
                    statement.execute(
                            """
                            CREATE TABLE IF NOT EXISTS operation_logs (
                              kind TEXT NOT NULL, name TEXT NOT NULL, event_time INTEGER NOT NULL,
                              category TEXT, source TEXT, search_text TEXT, alt_text TEXT,
                              outcome TEXT, bytes INTEGER NOT NULL DEFAULT 0, payload TEXT NOT NULL,
                              PRIMARY KEY(kind, name))
                            """);
                    statement.execute(
                            "CREATE INDEX IF NOT EXISTS log_time ON operation_logs(kind, event_time"
                                + " DESC, name)");
                    statement.execute(
                            "CREATE INDEX IF NOT EXISTS log_filter ON operation_logs(kind, outcome,"
                                + " source, category, event_time DESC)");
                    statement.execute(
                            "CREATE TABLE IF NOT EXISTS batch_tasks (name TEXT PRIMARY KEY, created"
                                + " INTEGER NOT NULL, payload TEXT NOT NULL)");
                    statement.execute(
                            "CREATE INDEX IF NOT EXISTS task_time ON batch_tasks(created DESC,"
                                + " name)");
                    statement.execute(
                            "CREATE TABLE IF NOT EXISTS toolkit_metadata (name TEXT PRIMARY KEY,"
                                + " value TEXT NOT NULL)");
                }
                initialized = true;
            }
            return work.apply(connection);
        }
    }

    @PreDestroy
    public synchronized void close() throws Exception {
        closed = true;
        scheduler.dispose();
        // JDBC's static registration otherwise keeps the plugin class loader alive after upgrade.
        var drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            var driver = drivers.nextElement();
            if (driver.getClass().getClassLoader() == ToolkitDatabase.class.getClassLoader()) {
                DriverManager.deregisterDriver(driver);
            }
        }
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T apply(Connection connection) throws Exception;
    }
}
