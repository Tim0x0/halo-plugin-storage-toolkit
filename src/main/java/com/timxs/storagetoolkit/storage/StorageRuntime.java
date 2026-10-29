package com.timxs.storagetoolkit.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Separates plugin/diagnostic startup from the optional availability of the log database. */
@Component
@RequiredArgsConstructor
public class StorageRuntime {
    private final ToolkitDatabase database;
    private final LegacyLogMigration migration;
    private final BatchTaskStore tasks;
    private volatile boolean ready;
    private volatile String error = "日志存储尚未就绪，请前往环境检测";

    public synchronized void initialize() {
        if (ready) return;
        database.read(connection -> true).block();
        migration.migrate();
        tasks.recoverInterrupted();
        ready = true;
        error = null;
    }

    public boolean isReady() { return ready; }
    public String error() { return error; }
    public void unavailable(String message) { if (!ready) error = message; }
    public void requireReady() {
        if (!ready) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, error);
    }
}
