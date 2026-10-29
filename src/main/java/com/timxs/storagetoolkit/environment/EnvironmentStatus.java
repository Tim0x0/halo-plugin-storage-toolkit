package com.timxs.storagetoolkit.environment;

import java.time.Instant;
import java.util.List;

public record EnvironmentStatus(RuntimePlatform platform, boolean storageReady, String storageMessage,
                                Instant checkedAt, List<DependencyStatus> dependencies) {
    public record DependencyStatus(String id, String label, String version, String state, String source,
                                    String message, boolean canDownload, long downloadBytes) {}
}
