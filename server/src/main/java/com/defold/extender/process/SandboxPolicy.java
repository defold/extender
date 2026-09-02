package com.defold.extender.process;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-command sandbox policy. Immutable.
 *
 * Every {@link ProcessExecutor} starts with {@link #toolchain()}: no network, only the job
 * directory writable. The two dependency resolvers (Gradle, dotnet) opt into
 * {@link #dependencyResolver(List)} explicitly because they must download artifacts.
 */
public final class SandboxPolicy {

    public enum Network {
        /** socket() fails for every address family except AF_UNIX. */
        NONE,
        /** No network restriction. */
        ALL
    }

    private final Network network;
    private final List<String> readWritePaths;
    private final List<String> readWriteExecPaths;
    private final Map<String, String> env;

    private SandboxPolicy(Network network, List<String> readWritePaths, List<String> readWriteExecPaths,
                          Map<String, String> env) {
        this.network = network;
        this.readWritePaths = List.copyOf(readWritePaths);
        this.readWriteExecPaths = List.copyOf(readWriteExecPaths);
        this.env = Map.copyOf(env);
    }

    /** Compilers, linkers, archivers, aapt2, d8, R8, protoc, javac, manifest merge: no network. */
    public static SandboxPolicy toolchain() {
        return new SandboxPolicy(Network.NONE, List.of(), List.of(), Map.of());
    }

    /** Gradle / NuGet resolution: network allowed, plus the given writable cache directories. */
    public static SandboxPolicy dependencyResolver(List<String> extraReadWritePaths) {
        return new SandboxPolicy(Network.ALL, extraReadWritePaths, List.of(), Map.of());
    }

    /** Copy with additional environment variables applied after the executor overlay. */
    public SandboxPolicy withEnv(Map<String, String> overrides) {
        Map<String, String> merged = new HashMap<>(this.env);
        merged.putAll(overrides);
        return new SandboxPolicy(network, readWritePaths, readWriteExecPaths, merged);
    }

    /** Copy with additional writable directories whose files may also be executed. */
    public SandboxPolicy withReadWriteExecPaths(List<String> paths) {
        List<String> merged = new ArrayList<>(this.readWriteExecPaths);
        merged.addAll(paths);
        return new SandboxPolicy(network, readWritePaths, merged, env);
    }

    public Network network() {
        return network;
    }

    public List<String> readWritePaths() {
        return readWritePaths;
    }

    public List<String> readWriteExecPaths() {
        return readWriteExecPaths;
    }

    public Map<String, String> env() {
        return env;
    }

    @Override
    public String toString() {
        return "SandboxPolicy{network=" + network + ", rw=" + readWritePaths + ", rwx=" + readWriteExecPaths
                + ", env=" + env.keySet() + "}";
    }
}
