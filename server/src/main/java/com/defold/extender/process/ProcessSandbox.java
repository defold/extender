package com.defold.extender.process;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a command line into a launcher invocation:
 * {@code extender-sandbox --ro ... --rw ... --net none -- cmd args...} with a scrubbed environment.
 *
 * The paths a command may touch are resolved at execute time from the executor's working
 * directory (the job directory), its environment overlay ({@code DYNAMO_HOME},
 * {@code MANIFEST_MERGE_TOOL}), the {@link SandboxPolicy} and the static configuration.
 *
 * One instance is installed by Spring at startup ({@link #install(ProcessSandbox)}); before
 * that, and on hosts without the launcher (macOS standalone, unit tests), {@link #current()}
 * is {@link #disabled()} and {@link #prepare} is a pass-through.
 */
public final class ProcessSandbox {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessSandbox.class);

    private static volatile ProcessSandbox current = disabled();

    /** The launcher argv and the complete child environment ({@code env == null}: inherit as before). */
    public record Launch(List<String> argv, Map<String, String> env) {}

    private final SandboxConfiguration configuration;
    private final Map<String, String> inheritedEnv;
    private final List<Pattern> denyPatterns;
    private final Set<String> warnedMissingPaths = ConcurrentHashMap.newKeySet();

    public ProcessSandbox(SandboxConfiguration configuration) {
        this(configuration, System.getenv());
    }

    ProcessSandbox(SandboxConfiguration configuration, Map<String, String> inheritedEnv) {
        this.configuration = configuration;
        this.inheritedEnv = Map.copyOf(inheritedEnv);
        List<Pattern> patterns = new ArrayList<>();
        for (String pattern : configuration.getEnvDenyPatterns()) {
            if (pattern != null && !pattern.isBlank()) {
                patterns.add(Pattern.compile(pattern.strip()));
            }
        }
        this.denyPatterns = List.copyOf(patterns);
    }

    public static ProcessSandbox current() {
        return current;
    }

    public static void install(ProcessSandbox sandbox) {
        current = sandbox;
    }

    public static ProcessSandbox disabled() {
        SandboxConfiguration off = new SandboxConfiguration();
        off.setEnabled(false);
        return new ProcessSandbox(off, Map.of());
    }

    public boolean isEnabled() {
        return configuration.isEnabled();
    }

    /** Per-command wall-clock limit; 0 when the sandbox is off so existing behaviour is untouched. */
    public long commandTimeoutMillis() {
        return configuration.isEnabled() ? configuration.getCommandTimeout() : 0;
    }

    public SandboxConfiguration configuration() {
        return configuration;
    }

    public Launch prepare(List<String> args, File cwd, Map<String, String> overlayEnv, SandboxPolicy policy)
            throws IOException {
        if (!configuration.isEnabled()) {
            return new Launch(args, null);
        }
        if (cwd == null) {
            throw new IOException("Process sandbox is enabled but no working directory is set for: "
                    + String.join(" ", args));
        }
        Path jobDir = cwd.toPath().toAbsolutePath().normalize();
        Path home = jobDir.resolve("home");
        Path tmp = jobDir.resolve("tmp");
        Files.createDirectories(home);
        Files.createDirectories(tmp);

        return new Launch(buildArgv(args, jobDir, home, tmp, overlayEnv, policy), buildEnv(home, tmp, overlayEnv, policy));
    }

    List<String> buildArgv(List<String> args, Path jobDir, Path home, Path tmp, Map<String, String> overlayEnv,
                           SandboxPolicy policy) {
        Set<Path> readOnly = new LinkedHashSet<>();
        addPaths(readOnly, configuration.getReadOnlyPaths());
        addEnvPath(readOnly, "DYNAMO_HOME", overlayEnv);
        addEnvPath(readOnly, "MANIFEST_MERGE_TOOL", overlayEnv);

        Set<Path> readWrite = new LinkedHashSet<>();
        readWrite.add(jobDir);
        readWrite.add(home);
        readWrite.add(tmp);
        addPaths(readWrite, policy.readWritePaths());
        addPaths(readWrite, configuration.getReadWritePaths());
        addPaths(readWrite, configuration.getImageReadWritePaths());

        Set<Path> readWriteExec = new LinkedHashSet<>();
        addPaths(readWriteExec, policy.readWriteExecPaths());
        addPaths(readWriteExec, configuration.getReadWriteExecPaths());

        // A path listed writable must not also be listed read-only: Landlock unions the rights.
        readOnly.removeAll(readWrite);
        readOnly.removeAll(readWriteExec);
        readWrite.removeAll(readWriteExec);

        List<String> argv = new ArrayList<>();
        argv.add(configuration.getLauncherPath());
        for (Path p : readOnly) {
            argv.add("--ro");
            argv.add(p.toString());
        }
        for (Path p : readWrite) {
            argv.add("--rw");
            argv.add(p.toString());
        }
        for (Path p : readWriteExec) {
            argv.add("--rwx");
            argv.add(p.toString());
        }
        argv.add("--net");
        argv.add(policy.network() == SandboxPolicy.Network.ALL ? "all" : "none");

        SandboxConfiguration.Limits limits = configuration.getLimits();
        addLimit(argv, "--cpu", limits.getCpuSeconds());
        addLimit(argv, "--nproc", limits.getMaxProcesses());
        addLimit(argv, "--fsize", limits.getMaxFileSizeBytes());
        addLimit(argv, "--nofile", limits.getMaxOpenFiles());
        if (configuration.isStrict()) {
            argv.add("--strict");
        }
        argv.add("--");
        argv.addAll(args);
        return argv;
    }

    Map<String, String> buildEnv(Path home, Path tmp, Map<String, String> overlayEnv, SandboxPolicy policy) {
        Map<String, String> env = new HashMap<>();
        for (Map.Entry<String, String> entry : inheritedEnv.entrySet()) {
            if (!isDenied(entry.getKey())) {
                env.put(entry.getKey(), entry.getValue());
            }
        }
        // The executor overlay is server generated (DYNAMO_HOME, build.yml env blocks) and trusted.
        env.putAll(overlayEnv);
        env.putAll(policy.env());
        // Anything a tool writes "at home" or in a temp dir lands in the job directory.
        env.put("HOME", home.toString());
        env.put("TMPDIR", tmp.toString());
        env.put("XDG_CACHE_HOME", home.resolve(".cache").toString());
        // Child JVMs (R8, manifest merge tool, javac, Gradle) must not touch /tmp either.
        env.put("JAVA_TOOL_OPTIONS", "-Djava.io.tmpdir=" + tmp + " -XX:-UsePerfData");
        return env;
    }

    boolean isDenied(String key) {
        for (Pattern pattern : denyPatterns) {
            if (pattern.matcher(key).matches()) {
                return true;
            }
        }
        return false;
    }

    private void addEnvPath(Set<Path> target, String variable, Map<String, String> overlayEnv) {
        String value = overlayEnv.get(variable);
        if (value == null) {
            value = inheritedEnv.get(variable);
        }
        if (value != null && !value.isBlank()) {
            addPaths(target, List.of(value));
        }
    }

    private void addPaths(Set<Path> target, Collection<String> paths) {
        for (String raw : paths) {
            if (raw == null) {
                continue;
            }
            String value = raw.strip();
            if (value.isEmpty()) {
                continue;
            }
            Path path = Path.of(value).toAbsolutePath().normalize();
            if (!Files.exists(path)) {
                if (warnedMissingPaths.add(path.toString())) {
                    LOGGER.warn("Sandbox path {} does not exist and is not granted", path);
                }
                continue;
            }
            target.add(path);
        }
    }

    private static void addLimit(List<String> argv, String flag, long value) {
        if (value > 0) {
            argv.add(flag);
            argv.add(Long.toString(value));
        }
    }
}
