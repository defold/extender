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
 * Turns a command line into a launcher invocation with a scrubbed environment:
 * {@code extender-sandbox --ro ... --rw ... --net none -- cmd args...} for the Landlock
 * launcher in the Docker builders, {@code extender-sandbox --profile <SBPL> -- cmd args...}
 * for the Seatbelt launcher on the macOS standalone builders.
 *
 * The paths a command may touch are resolved at execute time from the executor's working
 * directory (the job directory), its environment overlay (the variables listed in
 * {@code read-only-env-variables}, e.g. {@code DYNAMO_HOME}), the {@link SandboxPolicy} and
 * the static configuration.
 *
 * One instance is installed by Spring at startup ({@link #install(ProcessSandbox)}); before
 * that, and in unit tests, {@link #current()} is {@link #disabled()} and {@link #prepare} is a
 * pass-through.
 */
public final class ProcessSandbox {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessSandbox.class);

    private static volatile ProcessSandbox current = disabled();

    /** The launcher argv and the complete child environment ({@code env == null}: inherit as before). */
    public record Launch(List<String> argv, Map<String, String> env) {}

    private final SandboxConfiguration configuration;
    private final SandboxConfiguration.Backend backend;
    private final Map<String, String> inheritedEnv;
    private final Path realHome;
    private final List<Pattern> denyPatterns;
    private final Set<String> warnedMissingPaths = ConcurrentHashMap.newKeySet();

    public ProcessSandbox(SandboxConfiguration configuration) {
        this(configuration, System.getenv(), Path.of(System.getProperty("user.home", "/")));
    }

    ProcessSandbox(SandboxConfiguration configuration, Map<String, String> inheritedEnv) {
        this(configuration, inheritedEnv, Path.of(System.getProperty("user.home", "/")));
    }

    ProcessSandbox(SandboxConfiguration configuration, Map<String, String> inheritedEnv, Path realHome) {
        this.configuration = configuration;
        this.backend = configuration.resolveBackend();
        this.inheritedEnv = Map.copyOf(inheritedEnv);
        this.realHome = realHome.toAbsolutePath().normalize();
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

    public SandboxConfiguration.Backend backend() {
        return backend;
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
        if (backend == SandboxConfiguration.Backend.SEATBELT) {
            // Seatbelt matches real paths: the job directory under /var/folders is really
            // under /private/var/folders
            jobDir = jobDir.toRealPath();
        }
        Path home = jobDir.resolve("home");
        Path tmp = jobDir.resolve("tmp");
        Files.createDirectories(home);
        Files.createDirectories(tmp);

        List<String> argv = backend == SandboxConfiguration.Backend.SEATBELT
                ? buildSeatbeltArgv(args, jobDir, home, tmp, overlayEnv, policy)
                : buildLandlockArgv(args, jobDir, home, tmp, overlayEnv, policy);
        return new Launch(argv, buildEnv(home, tmp, overlayEnv, policy));
    }

    /** The paths a command gets, in the three access classes the launchers understand. */
    private record PathGrants(Set<Path> readOnly, Set<Path> readWrite, Set<Path> readWriteExec) {}

    private PathGrants collectPaths(Path jobDir, Path home, Path tmp, Map<String, String> overlayEnv,
                                    SandboxPolicy policy) {
        Set<Path> readOnly = new LinkedHashSet<>();
        addPaths(readOnly, configuration.getReadOnlyPaths());
        addPaths(readOnly, policy.readOnlyPaths());
        for (String variable : configuration.getReadOnlyEnvVariables()) {
            addEnvPath(readOnly, variable, overlayEnv);
        }

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
        return new PathGrants(readOnly, readWrite, readWriteExec);
    }

    List<String> buildLandlockArgv(List<String> args, Path jobDir, Path home, Path tmp, Map<String, String> overlayEnv,
                                   SandboxPolicy policy) {
        PathGrants grants = collectPaths(jobDir, home, tmp, overlayEnv, policy);
        if (!policy.readWritePatterns().isEmpty() || !policy.machServices().isEmpty()
                || !policy.preferenceDomains().isEmpty() || !policy.extraRules().isEmpty()) {
            LOGGER.debug("Landlock backend ignores the Seatbelt-only parts of {}", policy);
        }

        List<String> argv = new ArrayList<>();
        argv.add(configuration.getLauncherPath());
        for (Path p : grants.readOnly()) {
            argv.add("--ro");
            argv.add(p.toString());
        }
        for (Path p : grants.readWrite()) {
            argv.add("--rw");
            argv.add(p.toString());
        }
        for (Path p : grants.readWriteExec()) {
            argv.add("--rwx");
            argv.add(p.toString());
        }
        argv.add("--net");
        argv.add(policy.network() == SandboxPolicy.Network.ALL ? "all" : "none");
        addLimits(argv, policy);
        argv.add("--");
        argv.addAll(args);
        return argv;
    }

    List<String> buildSeatbeltArgv(List<String> args, Path jobDir, Path home, Path tmp, Map<String, String> overlayEnv,
                                   SandboxPolicy policy) throws IOException {
        PathGrants grants = collectPaths(jobDir, home, tmp, overlayEnv, policy);
        SandboxConfiguration.Darwin darwin = configuration.getDarwin();

        // Tools that read platform state through $HOME find it in the per-job home
        for (String link : darwin.getHomeLinks()) {
            Path target = seedHomeLink(home, link);
            if (target != null) {
                grants.readOnly().add(target);
            }
        }

        List<String> readWritePatterns = new ArrayList<>();
        for (String suffix : darwin.getUserTempPatterns()) {
            if (suffix != null && !suffix.isBlank()) {
                readWritePatterns.add(DarwinSandboxPaths.userTempPattern(suffix.strip()));
            }
        }
        readWritePatterns.addAll(policy.readWritePatterns());

        List<String> machServices = new ArrayList<>(darwin.getMachServices());
        machServices.addAll(policy.machServices());
        List<String> preferenceDomains = new ArrayList<>(darwin.getPreferenceDomains());
        preferenceDomains.addAll(policy.preferenceDomains());
        List<String> extraRules = new ArrayList<>(darwin.getExtraRules());
        extraRules.addAll(policy.extraRules());

        Set<Path> denyPaths = new LinkedHashSet<>();
        for (String raw : darwin.getDenyPaths()) {
            Path path = expandHome(raw);
            if (path != null && Files.exists(path)) {
                denyPaths.add(path.toRealPath());
            }
        }
        List<String> denyExec = new ArrayList<>();
        for (String raw : darwin.getDenyExecPaths()) {
            if (raw != null && !raw.isBlank()) {
                denyExec.add(raw.strip());
            }
        }

        String profile = SeatbeltProfile.render(new SeatbeltProfile.Grants(grants.readOnly(), grants.readWrite(),
                grants.readWriteExec(), readWritePatterns, policy.network(), machServices,
                preferenceDomains, denyPaths, denyExec, extraRules));

        List<String> argv = new ArrayList<>();
        argv.add(configuration.getLauncherPath());
        argv.add("--profile");
        argv.add(profile);
        addLimits(argv, policy);
        argv.add("--");
        argv.addAll(args);
        return argv;
    }

    private void addLimits(List<String> argv, SandboxPolicy policy) {
        SandboxConfiguration.Limits limits = configuration.getLimits();
        addLimit(argv, "--cpu", limits.getCpuSeconds());
        addLimit(argv, "--nproc", limits.getMaxProcesses());
        addLimit(argv, "--fsize", policy.maxFileSizeBytes() != null ? policy.maxFileSizeBytes() : limits.getMaxFileSizeBytes());
        addLimit(argv, "--nofile", limits.getMaxOpenFiles());
        if (configuration.isStrict()) {
            argv.add("--strict");
        }
    }

    /** Links {@code <home>/<link>} to the same path under the real home; returns the real target or null. */
    private Path seedHomeLink(Path home, String link) throws IOException {
        if (link == null || link.isBlank()) {
            return null;
        }
        Path relative = Path.of(link.strip());
        if (relative.isAbsolute() || relative.normalize().startsWith("..")) {
            LOGGER.warn("Ignoring sandbox home link {}: must be relative to the home directory", link);
            return null;
        }
        Path target = realHome.resolve(relative);
        if (!Files.isDirectory(target)) {
            return null;
        }
        Path linkPath = home.resolve(relative);
        if (!Files.exists(linkPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(linkPath.getParent());
            try {
                Files.createSymbolicLink(linkPath, target);
            } catch (java.nio.file.FileAlreadyExistsException e) {
                // parallel commands of one job prepare concurrently
            }
        }
        return target.toRealPath();
    }

    private Path expandHome(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip();
        if (value.equals("~")) {
            return realHome;
        }
        if (value.startsWith("~/")) {
            return realHome.resolve(value.substring(2)).normalize();
        }
        return Path.of(value).toAbsolutePath().normalize();
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
        // clang's implicit module cache defaults to a per-user cache dir that macOS derives
        // from the uid, not from HOME or TMPDIR; a per-job cache also cannot be poisoned by
        // another build
        env.put("CLANG_MODULE_CACHE_PATH", home.resolve(".cache/clang/ModuleCache").toString());
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
        if (variable == null || variable.isBlank()) {
            return;
        }
        String name = variable.strip();
        String value = overlayEnv.get(name);
        if (value == null) {
            value = inheritedEnv.get(name);
        }
        if (value == null || value.isBlank()) {
            return;
        }
        Path path = normalize(value, true);
        if (path == null) {
            return;
        }
        if (backend == SandboxConfiguration.Backend.SEATBELT) {
            // DEVELOPER_DIR points inside an Xcode bundle whose shared frameworks live beside
            // Contents/Developer; the tools do not load without the whole bundle
            Path bundle = enclosingAppBundle(path);
            if (bundle != null) {
                path = bundle;
            }
        }
        target.add(path);
    }

    private static Path enclosingAppBundle(Path path) {
        for (Path p = path; p != null; p = p.getParent()) {
            Path name = p.getFileName();
            if (name != null && name.toString().endsWith(".app")) {
                return p;
            }
        }
        return null;
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
            Path path = normalize(value, false);
            if (path != null) {
                target.add(path);
            }
        }
    }

    /**
     * Absolute, normalized, {@code ~} meaning the real home and, for Seatbelt, symlink-resolved;
     * null when the path does not exist on this host. The configured lists serve every image
     * and the Macs, so a missing configured entry is expected (DEBUG); a missing environment
     * variable target is not (one WARN).
     */
    private Path normalize(String value, boolean warnWhenMissing) {
        Path path = expandHome(value);
        if (!Files.exists(path)) {
            if (warnedMissingPaths.add(path.toString())) {
                if (warnWhenMissing) {
                    LOGGER.warn("Sandbox path {} does not exist and is not granted", path);
                } else {
                    LOGGER.debug("Sandbox path {} does not exist and is not granted", path);
                }
            }
            return null;
        }
        if (backend == SandboxConfiguration.Backend.SEATBELT) {
            try {
                return path.toRealPath();
            } catch (IOException e) {
                LOGGER.warn("Sandbox path {} cannot be resolved and is not granted: {}", path, e.toString());
                return null;
            }
        }
        return path;
    }

    private static void addLimit(List<String> argv, String flag, long value) {
        if (value > 0) {
            argv.add(flag);
            argv.add(Long.toString(value));
        }
    }
}
