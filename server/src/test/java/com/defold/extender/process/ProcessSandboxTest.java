package com.defold.extender.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        configuration.setReadOnlyPaths(java.util.Arrays.stream(readOnly).map(Path::toString).toList());
        return configuration;
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
    public void jobDirectoryHomeAndTmpAreWritableAndCreated(@TempDir Path jobDir) throws IOException {
        ProcessSandbox sandbox = sandbox(enabled(), Map.of());
        List<String> argv = sandbox.prepare(COMMAND, jobDir.toFile(), Map.of(), SandboxPolicy.toolchain()).argv();

        Path job = jobDir.toAbsolutePath().normalize();
        assertTrue(indexOfFlag(argv, "--rw", job.toString()) > 0, argv.toString());
        assertTrue(indexOfFlag(argv, "--rw", job.resolve("home").toString()) > 0, argv.toString());
        assertTrue(indexOfFlag(argv, "--rw", job.resolve("tmp").toString()) > 0, argv.toString());
        assertTrue(Files.isDirectory(job.resolve("home")));
        assertTrue(Files.isDirectory(job.resolve("tmp")));
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
        Path tool = Files.writeString(root.resolve("manifestmergetool.jar"), "jar");
        Path inheritedTool = Files.writeString(root.resolve("inherited.jar"), "jar");
        Path jobDir = Files.createDirectory(root.resolve("job"));

        // the executor overlay wins over the inherited environment
        Map<String, String> inherited = Map.of("MANIFEST_MERGE_TOOL", inheritedTool.toString());
        Map<String, String> overlay = Map.of("DYNAMO_HOME", sdk.toString(), "MANIFEST_MERGE_TOOL", tool.toString());

        List<String> argv = sandbox(enabled(), inherited)
                .prepare(COMMAND, jobDir.toFile(), overlay, SandboxPolicy.toolchain()).argv();

        assertTrue(indexOfFlag(argv, "--ro", sdk.toString()) > 0, argv.toString());
        assertTrue(indexOfFlag(argv, "--ro", tool.toString()) > 0, argv.toString());
        assertFalse(argv.contains(inheritedTool.toString()), argv.toString());
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
    public void policiesAreImmutableCopies() {
        java.util.ArrayList<String> paths = new java.util.ArrayList<>(List.of("/a"));
        SandboxPolicy policy = SandboxPolicy.dependencyResolver(paths);
        paths.add("/b");
        assertEquals(List.of("/a"), policy.readWritePaths());
        assertEquals(SandboxPolicy.Network.ALL, policy.network());
        assertEquals(SandboxPolicy.Network.NONE, SandboxPolicy.toolchain().network());
        assertEquals(List.of("/x"), policy.withReadWriteExecPaths(List.of("/x")).readWriteExecPaths());
        assertEquals(List.of(), policy.readWriteExecPaths());
        assertEquals("1", policy.withEnv(Map.of("K", "1")).env().get("K"));
        assertTrue(new File("/a").isAbsolute());
    }
}
