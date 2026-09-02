package com.defold.extender.process;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Settings for the Landlock/seccomp/rlimit sandbox that wraps every build subprocess.
 *
 * Landlock is an allowlist: everything a toolchain reads must be under {@code read-only-paths}
 * (or the SDK / job directory, which are added automatically). Image-specific writable state
 * (emscripten cache, wine prefix, ...) is supplied by the Docker image through
 * {@code EXTENDER_SANDBOX_IMAGEREADWRITEPATHS}, a comma separated list, which binds to
 * {@code image-read-write-paths} and is merged with {@code read-write-paths}.
 */
@Component
@ConfigurationProperties(prefix = "extender.sandbox")
public class SandboxConfiguration {
    private boolean enabled = false;
    private String launcherPath = "/usr/local/bin/extender-sandbox";
    /** Fail startup when Landlock or seccomp is unavailable instead of degrading silently. */
    private boolean strict = true;
    /** Wall-clock limit per subprocess, milliseconds. */
    private long commandTimeout = 1_200_000L;
    private List<String> readOnlyPaths = new ArrayList<>();
    private List<String> readWritePaths = new ArrayList<>();
    private List<String> readWriteExecPaths = new ArrayList<>();
    private List<String> imageReadWritePaths = new ArrayList<>();
    /** Regexes matched against environment variable names; matching variables are not inherited. */
    private List<String> envDenyPatterns = new ArrayList<>();
    private Limits limits = new Limits();

    public static class Limits {
        /** RLIMIT_CPU in seconds; 0 = unlimited. Sums CPU across threads, so keep it off for LTO/wasm-opt. */
        private long cpuSeconds = 0;
        /** RLIMIT_NPROC; per uid, so it also counts the server JVM's threads. 0 = unlimited. */
        private long maxProcesses = 4096;
        /** RLIMIT_FSIZE in bytes; 0 = unlimited. */
        private long maxFileSizeBytes = 8L * 1024L * 1024L * 1024L;
        /** RLIMIT_NOFILE; 0 = unlimited. */
        private long maxOpenFiles = 16384;

        public long getCpuSeconds() {
            return cpuSeconds;
        }

        public void setCpuSeconds(long cpuSeconds) {
            this.cpuSeconds = requireNonNegative(cpuSeconds, "limits.cpu-seconds");
        }

        public long getMaxProcesses() {
            return maxProcesses;
        }

        public void setMaxProcesses(long maxProcesses) {
            this.maxProcesses = requireNonNegative(maxProcesses, "limits.max-processes");
        }

        public long getMaxFileSizeBytes() {
            return maxFileSizeBytes;
        }

        public void setMaxFileSizeBytes(long maxFileSizeBytes) {
            this.maxFileSizeBytes = requireNonNegative(maxFileSizeBytes, "limits.max-file-size-bytes");
        }

        public long getMaxOpenFiles() {
            return maxOpenFiles;
        }

        public void setMaxOpenFiles(long maxOpenFiles) {
            this.maxOpenFiles = requireNonNegative(maxOpenFiles, "limits.max-open-files");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getLauncherPath() {
        return launcherPath;
    }

    public void setLauncherPath(String launcherPath) {
        if (launcherPath == null || launcherPath.isBlank()) {
            throw new IllegalArgumentException("extender.sandbox.launcher-path must not be empty");
        }
        this.launcherPath = launcherPath;
    }

    public boolean isStrict() {
        return strict;
    }

    public void setStrict(boolean strict) {
        this.strict = strict;
    }

    public long getCommandTimeout() {
        return commandTimeout;
    }

    public void setCommandTimeout(long commandTimeout) {
        if (commandTimeout <= 0) {
            throw new IllegalArgumentException("extender.sandbox.command-timeout must be positive");
        }
        this.commandTimeout = commandTimeout;
    }

    public List<String> getReadOnlyPaths() {
        return readOnlyPaths;
    }

    public void setReadOnlyPaths(List<String> readOnlyPaths) {
        this.readOnlyPaths = nonNull(readOnlyPaths);
    }

    public List<String> getReadWritePaths() {
        return readWritePaths;
    }

    public void setReadWritePaths(List<String> readWritePaths) {
        this.readWritePaths = nonNull(readWritePaths);
    }

    public List<String> getReadWriteExecPaths() {
        return readWriteExecPaths;
    }

    public void setReadWriteExecPaths(List<String> readWriteExecPaths) {
        this.readWriteExecPaths = nonNull(readWriteExecPaths);
    }

    public List<String> getImageReadWritePaths() {
        return imageReadWritePaths;
    }

    public void setImageReadWritePaths(List<String> imageReadWritePaths) {
        this.imageReadWritePaths = nonNull(imageReadWritePaths);
    }

    public List<String> getEnvDenyPatterns() {
        return envDenyPatterns;
    }

    public void setEnvDenyPatterns(List<String> envDenyPatterns) {
        this.envDenyPatterns = nonNull(envDenyPatterns);
    }

    public Limits getLimits() {
        return limits;
    }

    public void setLimits(Limits limits) {
        this.limits = limits == null ? new Limits() : limits;
    }

    private static List<String> nonNull(List<String> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }

    private static long requireNonNegative(long value, String property) {
        if (value < 0) {
            throw new IllegalArgumentException("extender.sandbox." + property + " must not be negative");
        }
        return value;
    }
}
