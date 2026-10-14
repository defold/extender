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

import com.defold.extender.log.LogSanitizer;

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
    private final boolean enforcing;
    private final SandboxConfiguration.Backend backend;
    private final Map<String, String> inheritedEnv;
    private final Path realHome;
    private final List<Pattern> denyPatterns;
    private final Set<String> warnedMissingPaths = ConcurrentHashMap.newKeySet();

    public ProcessSandbox(SandboxConfiguration configuration) {
        this(configuration, configuration.isEnabled());
    }

    /**
     * @param enforcing the launcher probe found every kernel layer it needs; false when a
     *                  non-strict configuration runs degraded
     */
    public ProcessSandbox(SandboxConfiguration configuration, boolean enforcing) {
        this(configuration, enforcing, System.getenv(), Path.of(System.getProperty("user.home", "/")));
    }

    ProcessSandbox(SandboxConfiguration configuration, Map<String, String> inheritedEnv) {
        this(configuration, inheritedEnv, Path.of(System.getProperty("user.home", "/")));
    }

    ProcessSandbox(SandboxConfiguration configuration, Map<String, String> inheritedEnv, Path realHome) {
        this(configuration, configuration.isEnabled(), inheritedEnv, realHome);
    }

    private ProcessSandbox(SandboxConfiguration configuration, boolean enforcing, Map<String, String> inheritedEnv,
                           Path realHome) {
        this.configuration = configuration;
        this.enforcing = configuration.isEnabled() && enforcing;
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

    /**
     * Commands really run confined: the sandbox is enabled and the launcher found the kernel
     * layers it needs. A non-strict configuration on a host without them runs the launcher
     * degraded, which is enabled but not enforcing.
     */
    public boolean isEnforcing() {
        return enforcing;
    }

    public SandboxConfiguration.Backend backend() {
        return backend;
    }

    /** Per-command wall-clock limit; 0 when the sandbox is off so existing behaviour is untouched. */
    public long commandTimeoutMillis() {
        return configuration.isEnabled() ? configuration.getCommandTimeout() : 0;
    }

    /** The wall-clock limit for a command run under {@code policy}. */
    public long commandTimeoutMillis(SandboxPolicy policy) {
        if (!configuration.isEnabled()) {
            return 0;
        }
        return policy.isResolver() ? configuration.getResolverCommandTimeout() : configuration.getCommandTimeout();
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
        Path lexicalJobDir = cwd.toPath().toAbsolutePath().normalize();
        Path realJobDir = lexicalJobDir.toRealPath();
        // Seatbelt matches real paths: the job directory under /var/folders is really under
        // /private/var/folders
        Path jobDir = backend == SandboxConfiguration.Backend.SEATBELT ? realJobDir : lexicalJobDir;
        JobDir job = new JobDir(jobDir, lexicalJobDir, realJobDir);
        // Inside the job directory, so covered by its grant; never granted on their own, as a
        // build can replace either with a link to somewhere else
        Path home = jobDir.resolve("home");
        Path tmp = jobDir.resolve("tmp");
        Files.createDirectories(home);
        Files.createDirectories(tmp);

        List<String> argv = backend == SandboxConfiguration.Backend.SEATBELT
                ? buildSeatbeltArgv(args, job, home, overlayEnv, policy)
                : buildLandlockArgv(args, job, overlayEnv, policy);
        return new Launch(argv, buildEnv(home, tmp, overlayEnv, policy));
    }

    /** The job directory as the backend names it, as given, and with every link resolved. */
    record JobDir(Path path, Path lexical, Path real) {
        boolean contains(Path p) {
            return p.startsWith(lexical) || p.startsWith(real);
        }
    }

    /** The paths a command gets, in the three access classes the launchers understand. */
    private record PathGrants(Set<Path> readOnly, Set<Path> readWrite, Set<Path> readWriteExec) {}

    private PathGrants collectPaths(JobDir job, Map<String, String> overlayEnv, SandboxPolicy policy)
            throws IOException {
        Set<Path> readOnly = new LinkedHashSet<>();
        addPaths(readOnly, configuration.getReadOnlyPaths(), null, true);
        // Unlike the server-wide configured list above, a policy's read-only paths can be
        // job-relative (e.g. a subdirectory of a per-job cache another step just built with RWX
        // access); those must get the same requireInsideJob check writable grants get, or an
        // earlier sandboxed step can swap one for a symlink and turn this into an unconfined read.
        addPaths(readOnly, policy.readOnlyPaths(), job, false);
        for (String variable : configuration.getReadOnlyEnvVariables()) {
            addEnvPath(readOnly, variable, overlayEnv);
        }

        Set<Path> readWrite = new LinkedHashSet<>();
        readWrite.add(job.path());
        addPaths(readWrite, policy.readWritePaths(), job, false);
        addPaths(readWrite, configuration.getReadWritePaths(), job, true);
        addPaths(readWrite, configuration.getImageReadWritePaths(), job, true);

        Set<Path> readWriteExec = new LinkedHashSet<>();
        addPaths(readWriteExec, policy.readWriteExecPaths(), job, false);
        addPaths(readWriteExec, configuration.getReadWriteExecPaths(), job, true);

        // A path listed writable must not also be listed read-only: Landlock unions the rights.
        readOnly.removeAll(readWrite);
        readOnly.removeAll(readWriteExec);
        readWrite.removeAll(readWriteExec);
        // Nor may a read-only path lie inside a writable one: it is readable through that grant
        // already, and all the read-only grant would add is EXECUTE on a tree the build writes.
        readOnly.removeIf(p -> isUnderAny(p, readWrite) || isUnderAny(p, readWriteExec));
        PathGrants grants = new PathGrants(readOnly, readWrite, readWriteExec);
        if (backend == SandboxConfiguration.Backend.LANDLOCK) {
            refuseNestedWritablePaths(grants);
        }
        return grants;
    }

    /**
     * Exact equality above only catches a path listed in both classes. Landlock also unions the
     * rights of every rule matching a path's <em>ancestors</em>, so a writable path nested under a
     * read-only one keeps that ancestor's EXECUTE right and becomes a directory a build can write
     * to and execute from - image state that outlives the job. No rule can subtract the right, so
     * the grant has to be moved; this refuses the command rather than silently widening it.
     * (Seatbelt takes the other route: the profile denies process-exec on the writable set.)
     */
    private void refuseNestedWritablePaths(PathGrants grants) throws IOException {
        for (Path writable : grants.readWrite()) {
            for (Path readable : grants.readOnly()) {
                if (writable.startsWith(readable)) {
                    throw new IOException(String.format(
                            "Refusing to grant %s to a build command: it is writable but lies under the "
                                    + "read-only grant %s, and Landlock unions the rights, so it would stay "
                                    + "executable too. Move it outside %s.",
                            writable, readable, readable));
                }
            }
        }
    }

    private static boolean isUnderAny(Path path, Set<Path> roots) {
        for (Path root : roots) {
            if (path.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    List<String> buildLandlockArgv(List<String> args, JobDir job, Map<String, String> overlayEnv,
                                   SandboxPolicy policy) throws IOException {
        PathGrants grants = collectPaths(job, overlayEnv, policy);
        if (!policy.readWritePatterns().isEmpty() || !policy.machServices().isEmpty()
                || !policy.preferenceDomains().isEmpty() || !policy.extraRules().isEmpty()) {
            LOGGER.debug("Landlock backend ignores the Seatbelt-only parts of {}", policy);
        }

        List<String> argv = new ArrayList<>();
        argv.add(configuration.getLauncherPath());
        // the launcher re-checks the writable grants inside it on the descriptors it grants,
        // since a parallel command of the same job can swap a directory for a link meanwhile
        argv.add("--job");
        argv.add(job.path().toString());
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

    List<String> buildSeatbeltArgv(List<String> args, JobDir job, Path home, Map<String, String> overlayEnv,
                                   SandboxPolicy policy) throws IOException {
        PathGrants grants = collectPaths(job, overlayEnv, policy);
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
        putAllExceptDenied(env, inheritedEnv);
        // The executor overlay is usually server generated (DYNAMO_HOME, build.yml env blocks),
        // but a resolver command's overlay can carry parsed content from an uploaded dependency
        // manifest (CocoaPods' own xcconfig has no key allowlist); denied names are stripped
        // here too rather than trusting every caller's overlay and policy env.
        putAllExceptDenied(env, overlayEnv);
        putAllExceptDenied(env, policy.env());
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

    private void putAllExceptDenied(Map<String, String> target, Map<String, String> source) {
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (!isDenied(entry.getKey())) {
                target.put(entry.getKey(), entry.getValue());
            }
        }
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
        Path path = normalize(value, true, true);
        if (path == null) {
            return;
        }
        if (backend == SandboxConfiguration.Backend.LANDLOCK && Files.isRegularFile(path) && path.getParent() != null) {
            // a Landlock rule on a single file is accepted but grants nothing on 9p mounts (Docker
            // Desktop bind mounts); the tool's directory works everywhere
            path = path.getParent();
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

    /**
     * @param job        non-null whenever {@code paths} may contain job-relative entries (writable
     *                   grants, and a policy's read-only paths): one inside the job directory must
     *                   still be inside it with every link resolved, as the build itself may have
     *                   planted the link. Null for paths that never come from inside the job (the
     *                   server-wide configured read-only list).
     * @param configured the path comes from the configuration, not from a per-job policy; only
     *                   those are remembered for the once-only missing-path log
     */
    private void addPaths(Set<Path> target, Collection<String> paths, JobDir job, boolean configured)
            throws IOException {
        for (String raw : paths) {
            if (raw == null) {
                continue;
            }
            String value = raw.strip();
            if (value.isEmpty()) {
                continue;
            }
            if (job != null) {
                requireInsideJob(expandHome(value), job);
            }
            Path path = normalize(value, false, configured);
            if (path != null) {
                target.add(path);
            }
        }
    }

    private static void requireInsideJob(Path path, JobDir job) throws IOException {
        if (!job.contains(path) || !Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path real = path.toRealPath();
        if (!real.startsWith(job.real())) {
            throw new IOException("Refusing to grant " + path + " to a build command: it resolves to " + real
                    + ", outside the job directory");
        }
    }

    /**
     * Absolute, normalized, {@code ~} meaning the real home and, for Seatbelt, symlink-resolved;
     * null when the path does not exist on this host. The configured lists serve every image
     * and the Macs, so a missing configured entry is expected (DEBUG); a missing environment
     * variable target is not (one WARN).
     */
    private Path normalize(String value, boolean warnWhenMissing, boolean remember) {
        Path path = expandHome(value);
        if (!Files.exists(path)) {
            if (!remember) {
                LOGGER.debug("Sandbox path {} does not exist and is not granted", path);
            } else if (warnedMissingPaths.add(path.toString())) {
                if (warnWhenMissing) {
                    LOGGER.warn("Sandbox path {} does not exist and is not granted", LogSanitizer.sanitize(path.toString()));
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
                LOGGER.warn("Sandbox path {} cannot be resolved and is not granted: {}", LogSanitizer.sanitize(path.toString()), e.toString());
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
