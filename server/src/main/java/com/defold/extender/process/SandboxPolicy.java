package com.defold.extender.process;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-command sandbox policy. Immutable.
 *
 * Every {@link ProcessExecutor} starts with {@link #toolchain()}: no network, only the job
 * directory writable. The dependency resolvers (Gradle, dotnet, CocoaPods, xcodebuild) opt
 * into {@link #dependencyResolver(List)} explicitly because they must download artifacts.
 *
 * {@link #readWritePatterns()}, {@link #machServices()} and {@link #preferenceDomains()} only
 * mean something to the Seatbelt (macOS) backend; the Landlock launcher has no regex rules
 * and no Mach namespace, so it ignores them.
 */
public final class SandboxPolicy {

    public enum Network {
        /** socket() fails for every address family except AF_UNIX. */
        NONE,
        /** No network restriction. */
        ALL
    }

    private final Network network;
    private final List<String> readOnlyPaths;
    private final List<String> readWritePaths;
    private final List<String> readWriteExecPaths;
    private final List<String> readWritePatterns;
    private final List<String> machServices;
    private final List<String> preferenceDomains;
    private final List<String> extraRules;
    /** RLIMIT_FSIZE override in bytes (0 = unlimited); null = the configured limit. */
    private final Long maxFileSizeBytes;
    private final Map<String, String> env;

    private SandboxPolicy(Network network, List<String> readOnlyPaths, List<String> readWritePaths,
                          List<String> readWriteExecPaths, List<String> readWritePatterns, List<String> machServices,
                          List<String> preferenceDomains, List<String> extraRules, Long maxFileSizeBytes,
                          Map<String, String> env) {
        this.network = network;
        this.readOnlyPaths = List.copyOf(readOnlyPaths);
        this.readWritePaths = List.copyOf(readWritePaths);
        this.readWriteExecPaths = List.copyOf(readWriteExecPaths);
        this.readWritePatterns = List.copyOf(readWritePatterns);
        this.machServices = List.copyOf(machServices);
        this.preferenceDomains = List.copyOf(preferenceDomains);
        this.extraRules = List.copyOf(extraRules);
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.env = Map.copyOf(env);
    }

    /** Compilers, linkers, archivers, aapt2, d8, R8, protoc, javac, codesign, manifest merge: no network. */
    public static SandboxPolicy toolchain() {
        return new SandboxPolicy(Network.NONE, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, Map.of());
    }

    /** Gradle / NuGet / CocoaPods / SwiftPM resolution: network allowed, plus the given writable cache directories. */
    public static SandboxPolicy dependencyResolver(List<String> extraReadWritePaths) {
        return new SandboxPolicy(Network.ALL, List.of(), extraReadWritePaths, List.of(), List.of(), List.of(), List.of(), List.of(), null, Map.of());
    }

    /** Copy with the network access replaced. */
    public SandboxPolicy withNetwork(Network network) {
        return new SandboxPolicy(network, readOnlyPaths, readWritePaths, readWriteExecPaths, readWritePatterns,
                machServices, preferenceDomains, extraRules, maxFileSizeBytes, env);
    }

    /** Copy with additional environment variables applied after the executor overlay. */
    public SandboxPolicy withEnv(Map<String, String> overrides) {
        Map<String, String> merged = new HashMap<>(this.env);
        merged.putAll(overrides);
        return new SandboxPolicy(network, readOnlyPaths, readWritePaths, readWriteExecPaths, readWritePatterns,
                machServices, preferenceDomains, extraRules, maxFileSizeBytes, merged);
    }

    /** Copy with additional read-only (and executable) directories. */
    public SandboxPolicy withReadOnlyPaths(List<String> paths) {
        return new SandboxPolicy(network, concat(readOnlyPaths, paths), readWritePaths, readWriteExecPaths,
                readWritePatterns, machServices, preferenceDomains, extraRules, maxFileSizeBytes, env);
    }

    /** Copy with additional writable directories. */
    public SandboxPolicy withReadWritePaths(List<String> paths) {
        return new SandboxPolicy(network, readOnlyPaths, concat(readWritePaths, paths), readWriteExecPaths,
                readWritePatterns, machServices, preferenceDomains, extraRules, maxFileSizeBytes, env);
    }

    /** Copy with additional writable directories whose files may also be executed. */
    public SandboxPolicy withReadWriteExecPaths(List<String> paths) {
        return new SandboxPolicy(network, readOnlyPaths, readWritePaths, concat(readWriteExecPaths, paths),
                readWritePatterns, machServices, preferenceDomains, extraRules, maxFileSizeBytes, env);
    }

    /**
     * Copy with additional writable locations given as regular expressions over real paths
     * (Seatbelt only), e.g. {@code ^/private/var/folders/xx/yy/T/xcrun_db}.
     */
    public SandboxPolicy withReadWritePatterns(List<String> patterns) {
        return new SandboxPolicy(network, readOnlyPaths, readWritePaths, readWriteExecPaths,
                concat(readWritePatterns, patterns), machServices, preferenceDomains, extraRules, maxFileSizeBytes, env);
    }

    /** Copy with additional Mach services the command may look up (Seatbelt only). */
    public SandboxPolicy withMachServices(List<String> services) {
        return new SandboxPolicy(network, readOnlyPaths, readWritePaths, readWriteExecPaths, readWritePatterns,
                concat(machServices, services), preferenceDomains, extraRules, maxFileSizeBytes, env);
    }

    /** Copy with additional preference domains the command may read through cfprefsd (Seatbelt only). */
    public SandboxPolicy withPreferenceDomains(List<String> domains) {
        return new SandboxPolicy(network, readOnlyPaths, readWritePaths, readWriteExecPaths, readWritePatterns,
                machServices, concat(preferenceDomains, domains), extraRules, maxFileSizeBytes, env);
    }

    /** Copy with raw SBPL rules appended before the deny block (Seatbelt only). */
    public SandboxPolicy withExtraRules(List<String> rules) {
        return new SandboxPolicy(network, readOnlyPaths, readWritePaths, readWriteExecPaths, readWritePatterns,
                machServices, preferenceDomains, concat(extraRules, rules), maxFileSizeBytes, env);
    }

    /** Copy with its own RLIMIT_FSIZE in bytes, 0 for unlimited, instead of the configured limit. */
    public SandboxPolicy withMaxFileSizeBytes(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("max file size must not be negative: " + bytes);
        }
        return new SandboxPolicy(network, readOnlyPaths, readWritePaths, readWriteExecPaths, readWritePatterns,
                machServices, preferenceDomains, extraRules, bytes, env);
    }

    public Network network() {
        return network;
    }

    public List<String> readOnlyPaths() {
        return readOnlyPaths;
    }

    public List<String> readWritePaths() {
        return readWritePaths;
    }

    public List<String> readWriteExecPaths() {
        return readWriteExecPaths;
    }

    public List<String> readWritePatterns() {
        return readWritePatterns;
    }

    public List<String> machServices() {
        return machServices;
    }

    public List<String> preferenceDomains() {
        return preferenceDomains;
    }

    public List<String> extraRules() {
        return extraRules;
    }

    /** The RLIMIT_FSIZE override, or null to use the configured limit. */
    public Long maxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public Map<String, String> env() {
        return env;
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> merged = new ArrayList<>(first);
        merged.addAll(second);
        return merged;
    }

    @Override
    public String toString() {
        return "SandboxPolicy{network=" + network + ", ro=" + readOnlyPaths + ", rw=" + readWritePaths + ", rwx=" + readWriteExecPaths
                + ", rwPatterns=" + readWritePatterns + ", mach=" + machServices + ", prefs=" + preferenceDomains
                + ", rules=" + extraRules + (maxFileSizeBytes != null ? ", fsize=" + maxFileSizeBytes : "")
                + ", env=" + env.keySet() + "}";
    }
}
