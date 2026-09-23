package com.defold.extender.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ProcessSandboxTest {
    private static final String LAUNCHER = "/nonexistent/extender-sandbox";
    private static final List<String> COMMAND = List.of("clang++", "-c", "a.cpp", "-o", "a.o");

    private static SandboxConfiguration enabled(Path... readOnly) {
        SandboxConfiguration configuration = new SandboxConfiguration();
        configuration.setEnabled(true);
        configuration.setLauncherPath(LAUNCHER);
        configuration.setStrict(true);
        configuration.setBackend(SandboxConfiguration.Backend.LANDLOCK);
        configuration.setReadOnlyPaths(java.util.Arrays.stream(readOnly).map(Path::toString).toList());
        return configuration;
    }

    private static SandboxConfiguration seatbelt(Path... readOnly) {
        SandboxConfiguration configuration = enabled(readOnly);
        configuration.setBackend(SandboxConfiguration.Backend.SEATBELT);
        return configuration;
    }

    private static String profileOf(List<String> argv) {
        assertEquals("--profile", argv.get(1), argv.toString());
        return argv.get(2);
    }

    private static ProcessSandbox sandbox(SandboxConfiguration configuration, Map<String, String> inherited) {
        return new ProcessSandbox(configuration, inherited);
    }

    private static int indexOfFlag(List<String> argv, String flag, String value) {
        for (int i = 0; i + 1 < argv.size(); i++) {
            if (argv.get(i).equals(flag) && argv.get(i + 1).equals(value)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    public void disabledSandboxIsPassthrough() throws IOException {
        ProcessSandbox sandbox = ProcessSandbox.disabled();
        ProcessSandbox.Launch launch = sandbox.prepare(COMMAND, null, Map.of(), SandboxPolicy.toolchain());

        assertFalse(sandbox.isEnabled());
        assertEquals(0, sandbox.commandTimeoutMillis());
        assertSame(COMMAND, launch.argv());
        assertNull(launch.env());
    }

    @Test
    public void currentDefaultsToDisabled() {
        assertFalse(ProcessSandbox.current().isEnabled());
    }

    @Test
    public void argvWrapsCommandWithLauncherAndNoNetwork(@TempDir Path jobDir) throws IOException {
        ProcessSandbox sandbox = sandbox(enabled(), Map.of());
        List<String> argv = sandbox.prepare(COMMAND, jobDir.toFile(), Map.of(), SandboxPolicy.toolchain()).argv();

        assertEquals(LAUNCHER, argv.get(0));
        assertTrue(indexOfFlag(argv, "--net", "none") > 0, argv.toString());
        assertTrue(argv.contains("--strict"));
        int separator = argv.indexOf("--");
        assertEquals(COMMAND, argv.subList(separator + 1, argv.size()));
        // the launcher's own flags all precede the separator
        assertEquals(-1, argv.subList(separator + 1, argv.size()).indexOf("--net"));
    }

    @Test
    public void jobDirectoryIsWritableAndHomeAndTmpAreCreatedInsideIt(@TempDir Path jobDir) throws IOException {
        ProcessSandbox sandbox = sandbox(enabled(), Map.of());
        List<String> argv = sandbox.prepare(COMMAND, jobDir.toFile(), Map.of(), SandboxPolicy.toolchain()).argv();

        Path job = jobDir.toAbsolutePath().normalize();
        assertTrue(indexOfFlag(argv, "--job", job.toString()) > 0, argv.toString());
        assertTrue(indexOfFlag(argv, "--rw", job.toString()) > 0, argv.toString());
        // covered by the job directory's grant; a grant of their own would follow a planted link
        assertEquals(-1, indexOfFlag(argv, "--rw", job.resolve("home").toString()), argv.toString());
        assertEquals(-1, indexOfFlag(argv, "--rw", job.resolve("tmp").toString()), argv.toString());
        assertTrue(Files.isDirectory(job.resolve("home")));
        assertTrue(Files.isDirectory(job.resolve("tmp")));
    }

    @Test
    public void writableGrantLinkedOutOfTheJobDirectoryIsRefused(@TempDir Path root) throws IOException {
        Path jobDir = Files.createDirectory(root.resolve("job"));
        Path outside = Files.createDirectory(root.resolve("shared-cache"));
        Path build = Files.createDirectory(jobDir.resolve("build"));
        Files.createSymbolicLink(build.resolve(".nuget"), outside);

        for (SandboxConfiguration configuration : List.of(enabled(), seatbelt())) {
            ProcessSandbox sandbox = sandbox(configuration, Map.of());
            SandboxPolicy policy = SandboxPolicy.toolchain()
                    .withReadWriteExecPaths(List.of(build.resolve(".nuget").toString()));
            IOException e = assertThrows(IOException.class,
                    () -> sandbox.prepare(COMMAND, jobDir.toFile(), Map.of(), policy));
            assertTrue(e.getMessage().contains("outside the job directory"), e.getMessage());

            // an intermediate directory replaced by a link is caught the same way
            SandboxPolicy nested = SandboxPolicy.toolchain()
                    .withReadWritePaths(List.of(build.resolve(".nuget/packages").toString()));
            Files.createDirectories(outside.resolve("packages"));
            assertThrows(IOException.class, () -> sandbox.prepare(COMMAND, jobDir.toFile(), Map.of(), nested));
        }
    }

    @Test
    public void writableGrantInsideTheJobDirectoryIsKept(@TempDir Path jobDir) throws IOException {
        Path cache = Files.createDirectories(jobDir.resolve("build/.nuget"));
        ProcessSandbox sandbox = sandbox(enabled(), Map.of());
        SandboxPolicy policy = SandboxPolicy.toolchain().withReadWriteExecPaths(List.of(cache.toString()));
        List<String> argv = sandbox.prepare(COMMAND, jobDir.toFile(), Map.of(), policy).argv();
        assertTrue(indexOfFlag(argv, "--rwx", cache.toAbsolutePath().normalize().toString()) > 0, argv.toString());
    }

    @Test
    public void readOnlyGrantInsideAWritableOneIsDropped(@TempDir Path jobDir) throws IOException {
        // read-only means read and EXECUTE, and Landlock unions it with the job directory's write
        Path runtime = Files.createDirectories(jobDir.resolve("build/.nuget/runtime/native"));
        ProcessSandbox sandbox = sandbox(enabled(), Map.of());
        SandboxPolicy policy = SandboxPolicy.toolchain().withReadOnlyPaths(List.of(runtime.toString()));
        List<String> argv = sandbox.prepare(COMMAND, jobDir.toFile(), Map.of(), policy).argv();
        assertEquals(-1, indexOfFlag(argv, "--ro", runtime.toAbsolutePath().normalize().toString()), argv.toString());
    }

    @Test
    public void resolverPoliciesGetTheResolverTimeout() {
        SandboxConfiguration configuration = enabled();
        configuration.setCommandTimeout(1000);
        configuration.setResolverCommandTimeout(5000);
        ProcessSandbox sandbox = sandbox(configuration, Map.of());
        assertEquals(1000, sandbox.commandTimeoutMillis(SandboxPolicy.toolchain()));
        assertEquals(5000, sandbox.commandTimeoutMillis(SandboxPolicy.dependencyResolver(List.of())));
        assertEquals(5000, sandbox.commandTimeoutMillis(SandboxPolicy.dependencyResolver(List.of())
                .withNetwork(SandboxPolicy.Network.NONE).withReadOnlyPaths(List.of("/x"))));
        assertEquals(0, ProcessSandbox.disabled().commandTimeoutMillis(SandboxPolicy.dependencyResolver(List.of())));
    }

    @Test
    public void degradedSandboxIsEnabledButNotEnforcing() {
        SandboxConfiguration configuration = enabled();
        configuration.setStrict(false);
        assertTrue(new ProcessSandbox(configuration, true).isEnforcing());
        ProcessSandbox degraded = new ProcessSandbox(configuration, false);
        assertTrue(degraded.isEnabled());
        assertFalse(degraded.isEnforcing());
        assertFalse(ProcessSandbox.disabled().isEnforcing());
    }

    @Test
    public void configuredPathsAreGrantedByKind(@TempDir Path root) throws IOException {
        Path ro = Files.createDirectory(root.resolve("ro"));
        Path rw = Files.createDirectory(root.resolve("rw"));
        Path imageRw = Files.createDirectory(root.resolve("image-rw"));
        Path rwx = Files.createDirectory(root.resolve("rwx"));
        Path jobDir = Files.createDirectory(root.resolve("job"));

        SandboxConfiguration configuration = enabled(ro);
        configuration.setReadWritePaths(List.of(rw.toString()));
        configuration.setImageReadWritePaths(List.of(imageRw.toString()));
        configuration.setReadWriteExecPaths(List.of(rwx.toString()));

        List<String> argv = sandbox(configuration, Map.of())
                .prepare(COMMAND, jobDir.toFile(), Map.of(), SandboxPolicy.toolchain()).argv();

        assertTrue(indexOfFlag(argv, "--ro", ro.toString()) > 0, argv.toString());
        assertTrue(indexOfFlag(argv, "--rw", rw.toString()) > 0, argv.toString());
        assertTrue(indexOfFlag(argv, "--rw", imageRw.toString()) > 0, argv.toString());
        assertTrue(indexOfFlag(argv, "--rwx", rwx.toString()) > 0, argv.toString());
    }

    @Test
    public void dependencyResolverPolicyOpensNetworkAndExtraWritablePaths(@TempDir Path root) throws IOException {
        Path cache = Files.createDirectory(root.resolve("gradle-home"));
        Path packages = Files.createDirectory(root.resolve("nuget"));
        Path jobDir = Files.createDirectory(root.resolve("job"));

        SandboxPolicy policy = SandboxPolicy.dependencyResolver(List.of(cache.toString()))
                .withReadWriteExecPaths(List.of(packages.toString()));
        List<String> argv = sandbox(enabled(), Map.of()).prepare(COMMAND, jobDir.toFile(), Map.of(), policy).argv();

        assertTrue(indexOfFlag(argv, "--net", "all") > 0, argv.toString());
        assertTrue(indexOfFlag(argv, "--rw", cache.toString()) > 0, argv.toString());
        assertTrue(indexOfFlag(argv, "--rwx", packages.toString()) > 0, argv.toString());
    }

    @Test
    public void missingPathsAreSkipped(@TempDir Path jobDir) throws IOException {
        SandboxConfiguration configuration = enabled();
        configuration.setReadOnlyPaths(List.of("/nonexistent/sdk-root", ""));
        configuration.setReadWritePaths(List.of("/nonexistent/cache"));

        List<String> argv = sandbox(configuration, Map.of())
                .prepare(COMMAND, jobDir.toFile(), Map.of(), SandboxPolicy.toolchain()).argv();

        assertFalse(argv.contains("/nonexistent/sdk-root"), argv.toString());
        assertFalse(argv.contains("/nonexistent/cache"), argv.toString());
        assertFalse(argv.contains("--ro"), argv.toString());
    }

    @Test
    public void sdkAndManifestMergeToolFromEnvironmentAreReadOnly(@TempDir Path root) throws IOException {
        Path sdk = Files.createDirectories(root.resolve("sdk").resolve("defoldsdk"));
        Path apps = Files.createDirectory(root.resolve("apps"));
        Path tool = Files.writeString(apps.resolve("manifestmergetool.jar"), "jar");
        Path inheritedTool = Files.writeString(Files.createDirectory(root.resolve("other")).resolve("inherited.jar"), "jar");
        Path jobDir = Files.createDirectory(root.resolve("job"));

        // the executor overlay wins over the inherited environment
        Map<String, String> inherited = Map.of("MANIFEST_MERGE_TOOL", inheritedTool.toString());
        Map<String, String> overlay = Map.of("DYNAMO_HOME", sdk.toString(), "MANIFEST_MERGE_TOOL", tool.toString());

        List<String> argv = sandbox(enabled(), inherited)
                .prepare(COMMAND, jobDir.toFile(), overlay, SandboxPolicy.toolchain()).argv();

        assertTrue(indexOfFlag(argv, "--ro", sdk.toString()) > 0, argv.toString());
        // a file target is granted through its directory: file rules do not bind on 9p mounts
        assertTrue(indexOfFlag(argv, "--ro", apps.toString()) > 0, argv.toString());
        assertEquals(-1, indexOfFlag(argv, "--ro", tool.toString()), argv.toString());
        assertFalse(argv.contains(inheritedTool.getParent().toString()), argv.toString());
    }

    @Test
    public void writablePathIsNeverAlsoReadOnly(@TempDir Path root) throws IOException {
        Path shared = Files.createDirectory(root.resolve("shared"));
        Path jobDir = Files.createDirectory(root.resolve("job"));
        SandboxConfiguration configuration = enabled(shared);
        configuration.setReadWritePaths(List.of(shared.toString()));

        List<String> argv = sandbox(configuration, Map.of())
                .prepare(COMMAND, jobDir.toFile(), Map.of(), SandboxPolicy.toolchain()).argv();

        assertEquals(-1, indexOfFlag(argv, "--ro", shared.toString()), argv.toString());
        assertTrue(indexOfFlag(argv, "--rw", shared.toString()) > 0, argv.toString());
    }

    @Test
    public void limitFlagsFollowConfigurationAndZeroMeansOmitted(@TempDir Path jobDir) throws IOException {
        SandboxConfiguration configuration = enabled();
        configuration.getLimits().setCpuSeconds(0);
        configuration.getLimits().setMaxProcesses(500);
        configuration.getLimits().setMaxFileSizeBytes(1024);
        configuration.getLimits().setMaxOpenFiles(0);
        configuration.setStrict(false);

        List<String> argv = sandbox(configuration, Map.of())
                .prepare(COMMAND, jobDir.toFile(), Map.of(), SandboxPolicy.toolchain()).argv();

        assertFalse(argv.contains("--cpu"), argv.toString());
        assertTrue(indexOfFlag(argv, "--nproc", "500") > 0, argv.toString());
        assertTrue(indexOfFlag(argv, "--fsize", "1024") > 0, argv.toString());
        assertFalse(argv.contains("--nofile"), argv.toString());
        assertFalse(argv.contains("--strict"), argv.toString());

        // a policy may replace the file size limit; 0 drops the flag
        List<String> unlimited = sandbox(configuration, Map.of())
                .prepare(COMMAND, jobDir.toFile(), Map.of(), SandboxPolicy.toolchain().withMaxFileSizeBytes(0)).argv();
        assertFalse(unlimited.contains("--fsize"), unlimited.toString());
        List<String> bigger = sandbox(configuration, Map.of())
                .prepare(COMMAND, jobDir.toFile(), Map.of(), SandboxPolicy.toolchain().withMaxFileSizeBytes(4096)).argv();
        assertTrue(indexOfFlag(bigger, "--fsize", "4096") > 0, bigger.toString());
        assertThrows(IllegalArgumentException.class, () -> SandboxPolicy.toolchain().withMaxFileSizeBytes(-1));
    }

    @Test
    public void environmentIsScrubbedOverlaidAndForced(@TempDir Path jobDir) throws IOException {
        SandboxConfiguration configuration = enabled();
        configuration.setEnvDenyPatterns(List.of("AWS_.*", ".*(TOKEN|SECRET).*", "GOOGLE_APPLICATION_CREDENTIALS"));
        Map<String, String> inherited = Map.of(
                "PATH", "/usr/bin",
                "JAVA_HOME", "/usr/local/jdk",
                "AWS_SECRET_ACCESS_KEY", "aws",
                "EXTENDER_TEST_DECOY_TOKEN", "decoy",
                "GOOGLE_APPLICATION_CREDENTIALS", "/etc/extender/credentials/log_writer.json",
                "HOME", "/home/extender",
                "DYNAMO_HOME", "/inherited/sdk");
        Map<String, String> overlay = Map.of("DYNAMO_HOME", "/var/extender/sdk/abc/defoldsdk", "EM_CACHE", "/var/extender/emcache");
        SandboxPolicy policy = SandboxPolicy.toolchain().withEnv(Map.of("DOTNET_CLI_HOME", "/job/.dotnet"));

        Map<String, String> env = sandbox(configuration, inherited)
                .prepare(COMMAND, jobDir.toFile(), overlay, policy).env();

        Path job = jobDir.toAbsolutePath().normalize();
        assertEquals("/usr/bin", env.get("PATH"));
        assertEquals("/usr/local/jdk", env.get("JAVA_HOME"));
        assertFalse(env.containsKey("AWS_SECRET_ACCESS_KEY"));
        assertFalse(env.containsKey("EXTENDER_TEST_DECOY_TOKEN"));
        assertFalse(env.containsKey("GOOGLE_APPLICATION_CREDENTIALS"));
        assertEquals("/var/extender/sdk/abc/defoldsdk", env.get("DYNAMO_HOME"));
        assertEquals("/var/extender/emcache", env.get("EM_CACHE"));
        assertEquals("/job/.dotnet", env.get("DOTNET_CLI_HOME"));
        assertEquals(job.resolve("home").toString(), env.get("HOME"));
        assertEquals(job.resolve("tmp").toString(), env.get("TMPDIR"));
        assertEquals(job.resolve("home").resolve(".cache").toString(), env.get("XDG_CACHE_HOME"));
        assertEquals(job.resolve("home").resolve(".cache/clang/ModuleCache").toString(), env.get("CLANG_MODULE_CACHE_PATH"));
        assertEquals("-Djava.io.tmpdir=" + job.resolve("tmp") + " -XX:-UsePerfData", env.get("JAVA_TOOL_OPTIONS"));
    }

    @Test
    public void denyPatternsMatchWholeVariableName() {
        SandboxConfiguration configuration = enabled();
        configuration.setEnvDenyPatterns(List.of("AWS_.*", "SECRET"));
        ProcessSandbox sandbox = sandbox(configuration, Map.of());

        assertTrue(sandbox.isDenied("AWS_REGION"));
        assertTrue(sandbox.isDenied("SECRET"));
        assertFalse(sandbox.isDenied("MY_SECRET_VALUE"));
        assertFalse(sandbox.isDenied("PATH"));
    }

    @Test
    public void invalidDenyPatternFailsConstruction() {
        SandboxConfiguration configuration = enabled();
        configuration.setEnvDenyPatterns(List.of("(unbalanced"));
        assertThrows(java.util.regex.PatternSyntaxException.class, () -> sandbox(configuration, Map.of()));
    }

    @Test
    public void enabledSandboxFailsClosedWithoutWorkingDirectory() {
        ProcessSandbox sandbox = sandbox(enabled(), Map.of());
        IOException e = assertThrows(IOException.class,
                () -> sandbox.prepare(COMMAND, null, Map.of(), SandboxPolicy.toolchain()));
        assertTrue(e.getMessage().contains("clang++"), e.getMessage());
    }

    @Test
    public void enabledSandboxReportsConfiguredTimeout() {
        SandboxConfiguration configuration = enabled();
        configuration.setCommandTimeout(42);
        assertEquals(42, sandbox(configuration, Map.of()).commandTimeoutMillis());
    }

    @Test
    public void seatbeltArgvCarriesTheProfileAndTheCommand(@TempDir Path jobDir) throws IOException {
        SandboxConfiguration configuration = seatbelt();
        configuration.getDarwin().setUserTempPatterns(List.of());
        ProcessSandbox sandbox = sandbox(configuration, Map.of());
        List<String> argv = sandbox.prepare(COMMAND, jobDir.toFile(), Map.of(), SandboxPolicy.toolchain()).argv();
        assertFalse(profileOf(argv).contains("TemporaryItems"), profileOf(argv));

        assertEquals(LAUNCHER, argv.get(0));
        String profile = profileOf(argv);
        assertTrue(profile.startsWith("(version 1)\n(deny default)\n(import \"system.sb\")"), profile);
        assertTrue(profile.contains("(subpath " + SeatbeltProfile.quote(jobDir.toRealPath().toString()) + ")"), profile);
        assertTrue(profile.contains("(allow network* (local unix-socket) (remote unix-socket))"), profile);
        assertFalse(profile.contains("(allow network*)\n"), profile);
        assertTrue(argv.contains("--strict"));
        assertTrue(indexOfFlag(argv, "--nproc", "16384") > 0, argv.toString());
        assertFalse(argv.contains("--ro"), argv.toString());
        int separator = argv.indexOf("--");
        assertEquals(COMMAND, argv.subList(separator + 1, argv.size()));
        assertTrue(Files.isDirectory(jobDir.resolve("home")));
        assertTrue(Files.isDirectory(jobDir.resolve("tmp")));
    }

    @Test
    public void seatbeltGrantsRealPathsOnly(@TempDir Path root) throws IOException {
        Path real = Files.createDirectory(root.resolve("real"));
        Path link = symlinkOrSkip(root.resolve("link"), real);
        Path jobDir = Files.createDirectory(root.resolve("job"));

        String profile = profileOf(sandbox(seatbelt(link), Map.of())
                .prepare(COMMAND, jobDir.toFile(), Map.of(), SandboxPolicy.toolchain()).argv());

        assertTrue(profile.contains(SeatbeltProfile.quote(real.toRealPath().toString())), profile);
        assertFalse(profile.contains(SeatbeltProfile.quote(link.toString())), profile);
    }

    @Test
    public void seatbeltWidensDeveloperDirToTheAppBundle(@TempDir Path root) throws IOException {
        Path developerDir = Files.createDirectories(root.resolve("Xcode.app/Contents/Developer"));
        Path jobDir = Files.createDirectory(root.resolve("job"));
        SandboxConfiguration configuration = seatbelt();
        configuration.setReadOnlyEnvVariables(List.of("DEVELOPER_DIR"));

        String profile = profileOf(sandbox(configuration, Map.of())
                .prepare(COMMAND, jobDir.toFile(), Map.of("DEVELOPER_DIR", developerDir.toString()), SandboxPolicy.toolchain()).argv());

        Path bundle = root.resolve("Xcode.app").toRealPath();
        assertTrue(profile.contains("(subpath " + SeatbeltProfile.quote(bundle.toString()) + ")"), profile);
        assertFalse(profile.contains(SeatbeltProfile.quote(developerDir.toRealPath().toString())), profile);
    }

    @Test
    public void seatbeltSeedsHomeLinksAndGrantsTheirTargets(@TempDir Path root) throws IOException {
        Path realHome = Files.createDirectory(root.resolve("realhome"));
        Path developer = Files.createDirectories(realHome.resolve("Library/Developer"));
        Path jobDir = Files.createDirectory(root.resolve("job"));
        symlinkOrSkip(root.resolve("probe"), developer);
        SandboxConfiguration configuration = seatbelt();
        configuration.getDarwin().setHomeLinks(List.of("Library/Developer", "Library/Missing", "/etc"));

        ProcessSandbox sandbox = new ProcessSandbox(configuration, Map.of(), realHome);
        String profile = profileOf(sandbox.prepare(COMMAND, jobDir.toFile(), Map.of(), SandboxPolicy.toolchain()).argv());

        Path link = jobDir.resolve("home/Library/Developer");
        assertTrue(Files.isSymbolicLink(link), link.toString());
        assertEquals(developer.toRealPath(), Files.readSymbolicLink(link).toRealPath());
        assertTrue(profile.contains("(subpath " + SeatbeltProfile.quote(developer.toRealPath().toString()) + ")"), profile);
        assertFalse(Files.exists(jobDir.resolve("home/Library/Missing")));
        // a second command reuses the link
        sandbox.prepare(COMMAND, jobDir.toFile(), Map.of(), SandboxPolicy.toolchain());
        assertTrue(Files.isSymbolicLink(link));
    }

    @Test
    public void seatbeltDenyRulesExpandHomeAndComeLast(@TempDir Path root) throws IOException {
        Path realHome = Files.createDirectory(root.resolve("realhome"));
        Path ssh = Files.createDirectory(realHome.resolve(".ssh"));
        Path jobDir = Files.createDirectory(root.resolve("job"));
        SandboxConfiguration configuration = seatbelt(realHome);
        configuration.setReadOnlyPaths(List.of("~/Library", "~/.missing"));
        Files.createDirectory(realHome.resolve("Library"));
        configuration.getDarwin().setDenyPaths(List.of("~/.ssh", "~/.missing", "/nonexistent/keychains"));
        configuration.getDarwin().setDenyExecPaths(List.of("/usr/bin/sudo", " "));
        configuration.getDarwin().setExtraRules(List.of("(allow sysctl-write)", ""));

        String profile = profileOf(new ProcessSandbox(configuration, Map.of(), realHome)
                .prepare(COMMAND, jobDir.toFile(), Map.of(), SandboxPolicy.toolchain()).argv());

        String denyFiles = "(deny file* (subpath " + SeatbeltProfile.quote(ssh.toRealPath().toString()) + "))";
        String denyExec = "(deny process-exec (literal \"/usr/bin/sudo\"))";
        assertTrue(profile.contains(denyFiles), profile);
        assertTrue(profile.contains(denyExec), profile);
        assertTrue(profile.indexOf("(allow sysctl-write)") < profile.indexOf(denyFiles), profile);
        assertTrue(profile.indexOf("(allow file-read*") < profile.indexOf(denyFiles), profile);
        assertFalse(profile.contains(".missing"), profile);
        assertTrue(profile.contains("(subpath " + SeatbeltProfile.quote(realHome.resolve("Library").toRealPath().toString()) + ")"), profile);
    }

    @Test
    public void policyReadOnlyPathsAreGrantedOnBothBackends(@TempDir Path root) throws IOException {
        Path extra = Files.createDirectory(root.resolve("extra"));
        Path jobDir = Files.createDirectory(root.resolve("job"));
        SandboxPolicy policy = SandboxPolicy.toolchain().withReadOnlyPaths(List.of(extra.toString()));

        List<String> landlock = sandbox(enabled(), Map.of()).prepare(COMMAND, jobDir.toFile(), Map.of(), policy).argv();
        assertTrue(indexOfFlag(landlock, "--ro", extra.toString()) > 0, landlock.toString());
        String profile = profileOf(sandbox(seatbelt(), Map.of()).prepare(COMMAND, jobDir.toFile(), Map.of(), policy).argv());
        assertTrue(profile.contains("(subpath " + SeatbeltProfile.quote(extra.toRealPath().toString()) + ")"), profile);
    }

    @Test
    public void seatbeltRendersPolicyExtras(@TempDir Path root) throws IOException {
        Path cache = Files.createDirectory(root.resolve("cache"));
        Path plugins = Files.createDirectory(root.resolve("plugins"));
        Path jobDir = Files.createDirectory(root.resolve("job"));
        SandboxConfiguration configuration = seatbelt();
        configuration.getDarwin().setMachServices(List.of("com.apple.lsd.mapdb"));
        configuration.getDarwin().setPreferenceDomains(List.of("kCFPreferencesAnyApplication"));
        SandboxPolicy policy = SandboxPolicy.dependencyResolver(List.of(cache.toString()))
                .withReadWriteExecPaths(List.of(plugins.toString()))
                .withReadWritePatterns(List.of("^/private/var/folders/[^/]+/[^/]+/T/xcrun_db"))
                .withMachServices(List.of("com.apple.FSEvents"))
                .withPreferenceDomains(List.of("com.apple.dt.Xcode"))
                .withExtraRules(List.of("(allow system-fsctl)"));

        List<String> argv = sandbox(configuration, Map.of()).prepare(COMMAND, jobDir.toFile(), Map.of(), policy).argv();
        String profile = profileOf(argv);
        assertTrue(profile.contains("(allow system-fsctl)\n"), profile);

        assertTrue(profile.contains("(system-network)\n(allow network*)"), profile);
        assertTrue(profile.contains("(subpath " + SeatbeltProfile.quote(cache.toRealPath().toString()) + ")"), profile);
        assertTrue(profile.contains("(regex #\"^/private/var/folders/[^/]+/[^/]+/T/xcrun_db\")"), profile);
        // the darwin temp entries every command gets come first
        String tempDir = SeatbeltProfile.regexQuote(DarwinSandboxPaths.userTempDir().toString());
        assertTrue(profile.contains("(regex #\"^" + tempDir + "/TemporaryItems/\")"), profile);
        assertTrue(profile.indexOf("/TemporaryItems/") < profile.indexOf("/T/xcrun_db"), profile);
        assertTrue(profile.contains("(global-name \"com.apple.lsd.mapdb\") (global-name \"com.apple.FSEvents\")"), profile);
        assertTrue(profile.contains("(preference-domain \"kCFPreferencesAnyApplication\" \"com.apple.dt.Xcode\")"), profile);
        String execLine = profile.lines().filter(l -> l.startsWith("(allow process-exec")).findFirst().orElse("");
        assertTrue(execLine.contains(SeatbeltProfile.quote(plugins.toRealPath().toString())), execLine);
        assertFalse(execLine.contains(SeatbeltProfile.quote(cache.toRealPath().toString())), execLine);
    }

    /**
     * The SPM shape: the job directory is writable and the SwiftPM working directory inside it is
     * read-write-exec, because SwiftPM compiles Package.swift, plugins and macros there and runs
     * them. The profile denies process-exec on the writable set, so the rwx re-grant has to come
     * after that deny for the nested directory to stay executable.
     */
    @Test
    public void nestedExecGrantSurvivesTheWritableExecDeny(@TempDir Path root) throws IOException {
        Path jobDir = Files.createDirectory(root.resolve("job"));
        Path workingDir = Files.createDirectories(jobDir.resolve("spm/working"));
        SandboxPolicy policy = SandboxPolicy.toolchain()
                .withReadWriteExecPaths(List.of(workingDir.toString()));

        List<String> argv = sandbox(seatbelt(), Map.of()).prepare(COMMAND, jobDir.toFile(), Map.of(), policy).argv();
        String profile = profileOf(argv);
        String job = SeatbeltProfile.quote(jobDir.toRealPath().toString());
        String working = SeatbeltProfile.quote(workingDir.toRealPath().toString());

        int denyJobExec = profile.indexOf("(deny process-exec");
        int allowWorkingExec = profile.lastIndexOf("(allow process-exec");
        assertTrue(denyJobExec >= 0, profile);
        assertTrue(profile.lines().filter(l -> l.startsWith("(deny process-exec")).findFirst().orElse("").contains(job),
                profile);
        assertTrue(profile.lines().filter(l -> l.startsWith("(allow process-exec")).reduce((a, b) -> b).orElse("")
                .contains(working), profile);
        // last matching rule wins: the re-grant must come after the deny
        assertTrue(allowWorkingExec > denyJobExec, profile);
    }

    @Test
    public void landlockBackendIgnoresSeatbeltOnlyPolicyParts(@TempDir Path jobDir) throws IOException {
        SandboxPolicy policy = SandboxPolicy.toolchain()
                .withReadWritePatterns(List.of("^/x"))
                .withMachServices(List.of("com.apple.FSEvents"));
        List<String> argv = sandbox(enabled(), Map.of()).prepare(COMMAND, jobDir.toFile(), Map.of(), policy).argv();
        assertFalse(String.join(" ", argv).contains("FSEvents"), argv.toString());
        assertFalse(argv.contains("--profile"), argv.toString());
    }

    @Test
    public void autoBackendFollowsTheOperatingSystem() {
        SandboxConfiguration configuration = new SandboxConfiguration();
        boolean mac = System.getProperty("os.name").toLowerCase().contains("mac");
        assertEquals(mac ? SandboxConfiguration.Backend.SEATBELT : SandboxConfiguration.Backend.LANDLOCK,
                configuration.resolveBackend());
        configuration.setBackend(SandboxConfiguration.Backend.SEATBELT);
        assertEquals(SandboxConfiguration.Backend.SEATBELT, configuration.resolveBackend());
    }

    @Test
    public void policiesAreImmutableCopies() {
        java.util.ArrayList<String> paths = new java.util.ArrayList<>(List.of("/a"));
        SandboxPolicy policy = SandboxPolicy.dependencyResolver(paths);
        paths.add("/b");
        assertEquals(List.of("/a"), policy.readWritePaths());
        assertEquals(SandboxPolicy.Network.ALL, policy.network());
        assertEquals(SandboxPolicy.Network.NONE, SandboxPolicy.toolchain().network());
        assertEquals(List.of("/x"), policy.withReadWriteExecPaths(List.of("/x")).readWriteExecPaths());
        assertEquals(List.of("/r"), policy.withReadOnlyPaths(List.of("/r")).readOnlyPaths());
        assertEquals(List.of(), policy.readOnlyPaths());
        assertEquals(List.of(), policy.readWriteExecPaths());
        assertEquals("1", policy.withEnv(Map.of("K", "1")).env().get("K"));
    }

    /** Windows grants symlink creation only to administrators; these tests are about macOS anyway. */
    private static Path symlinkOrSkip(Path link, Path target) throws IOException {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (java.nio.file.FileSystemException | UnsupportedOperationException e) {
            assumeTrue(false, "cannot create symbolic links here: " + e.getMessage());
            throw e;
        }
    }
}
