package com.defold.extender.process;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Settings for the sandbox that wraps every build subprocess: Landlock/seccomp/rlimits inside
 * the Docker builders, Seatbelt ({@code sandbox-exec}) on the macOS standalone builders.
 *
 * Both are allowlists: everything a toolchain reads must be under {@code read-only-paths}
 * (or a directory named by one of {@code read-only-env-variables}, the SDK, the job
 * directory, which are added automatically). Image-specific writable state (emscripten cache,
 * wine prefix, ...) is supplied by the Docker image through
 * {@code EXTENDER_SANDBOX_IMAGEREADWRITEPATHS}, a comma separated list, which binds to
 * {@code image-read-write-paths} and is merged with {@code read-write-paths}. Paths that do
 * not exist on a host are skipped, so one list serves every image and the Macs.
 */
@Component
@ConfigurationProperties(prefix = "extender.sandbox")
public class SandboxConfiguration {

    /** Which launcher dialect to speak; {@code AUTO} picks by operating system. */
    public enum Backend {
        AUTO, LANDLOCK, SEATBELT
    }

    private boolean enabled = false;
    private Backend backend = Backend.AUTO;
    private String launcherPath = "/usr/local/bin/extender-sandbox";
    /** Fail startup when the kernel layers are unavailable instead of degrading silently. */
    private boolean strict = true;
    /** Wall-clock limit per subprocess, milliseconds. */
    private long commandTimeout = 1_200_000L;
    private List<String> readOnlyPaths = new ArrayList<>();
    private List<String> readWritePaths = new ArrayList<>();
    private List<String> readWriteExecPaths = new ArrayList<>();
    private List<String> imageReadWritePaths = new ArrayList<>();
    /** Environment variables whose values are directories (or files) granted read-only + execute. */
    private List<String> readOnlyEnvVariables = new ArrayList<>(List.of("DYNAMO_HOME", "MANIFEST_MERGE_TOOL"));
    /** Regexes matched against environment variable names; matching variables are not inherited. */
    private List<String> envDenyPatterns = new ArrayList<>();
    private Limits limits = new Limits();
    private Darwin darwin = new Darwin();

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

    /**
     * Seatbelt-only settings. Seatbelt governs more than files: Mach service lookups and
     * cfprefsd preference reads are denied unless listed here. {@code ~/} in a path means the
     * server user's real home directory.
     */
    public static class Darwin {
        /** Mach services every command may look up (global-name). */
        private List<String> machServices = new ArrayList<>();
        /** Preference domains every command may read through cfprefsd. */
        private List<String> preferenceDomains = new ArrayList<>();
        /** Denied outright, even inside a granted tree (keychains, ssh keys, cloud credentials). */
        private List<String> denyPaths = new ArrayList<>();
        /** Executables denied even though their directory is granted (sudo, security, launchctl). */
        private List<String> denyExecPaths = new ArrayList<>();
        /** Paths relative to the real home linked into the per-job home, and granted read-only. */
        private List<String> homeLinks = new ArrayList<>();
        /**
         * Regexes, relative to the darwin user temp dir, of the entries every command may write
         * there: Foundation's atomic saves ({@code TemporaryItems/}, {@code <uuid>-<pid>-<hex>}
         * temp files and directories) and the xcrun cache ignore TMPDIR. The directory itself (which also holds
         * every job) is never granted.
         */
        private List<String> userTempPatterns = new ArrayList<>(List.of("TemporaryItems/", "xcrun_db", "[0-9A-Fa-f-]+-[0-9]+-[0-9A-Fa-f]+(/|$)"));
        /** Raw SBPL rules appended before the deny block; an operator escape hatch. */
        private List<String> extraRules = new ArrayList<>();

        public List<String> getMachServices() {
            return machServices;
        }

        public void setMachServices(List<String> machServices) {
            this.machServices = nonNull(machServices);
        }

        public List<String> getPreferenceDomains() {
            return preferenceDomains;
        }

        public void setPreferenceDomains(List<String> preferenceDomains) {
            this.preferenceDomains = nonNull(preferenceDomains);
        }

        public List<String> getDenyPaths() {
            return denyPaths;
        }

        public void setDenyPaths(List<String> denyPaths) {
            this.denyPaths = nonNull(denyPaths);
        }

        public List<String> getDenyExecPaths() {
            return denyExecPaths;
        }

        public void setDenyExecPaths(List<String> denyExecPaths) {
            this.denyExecPaths = nonNull(denyExecPaths);
        }

        public List<String> getHomeLinks() {
            return homeLinks;
        }

        public void setHomeLinks(List<String> homeLinks) {
            this.homeLinks = nonNull(homeLinks);
        }

        public List<String> getExtraRules() {
            return extraRules;
        }

        public List<String> getUserTempPatterns() {
            return userTempPatterns;
        }

        public void setUserTempPatterns(List<String> userTempPatterns) {
            this.userTempPatterns = nonNull(userTempPatterns);
        }

        public void setExtraRules(List<String> extraRules) {
            this.extraRules = nonNull(extraRules);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Backend getBackend() {
        return backend;
    }

    public void setBackend(Backend backend) {
        this.backend = backend == null ? Backend.AUTO : backend;
    }

    /** The backend actually used: {@code AUTO} resolves to Seatbelt on macOS and Landlock elsewhere. */
    public Backend resolveBackend() {
        if (backend != Backend.AUTO) {
            return backend;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("darwin") ? Backend.SEATBELT : Backend.LANDLOCK;
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

    public List<String> getReadOnlyEnvVariables() {
        return readOnlyEnvVariables;
    }

    public void setReadOnlyEnvVariables(List<String> readOnlyEnvVariables) {
        this.readOnlyEnvVariables = nonNull(readOnlyEnvVariables);
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

    public Darwin getDarwin() {
        return darwin;
    }

    public void setDarwin(Darwin darwin) {
        this.darwin = darwin == null ? new Darwin() : darwin;
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
