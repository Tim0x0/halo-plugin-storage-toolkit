package com.timxs.storagetoolkit.environment;

/** Describes the JVM's environment, which may differ from the host running Docker. */
public record RuntimePlatform(String os, String architecture, String libc, String distribution,
                              String deployment, String deploymentEvidence, String javaVersion) {
    public String key() { return os + "/" + architecture + "/" + libc; }
}
