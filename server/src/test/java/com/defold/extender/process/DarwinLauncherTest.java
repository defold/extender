package com.defold.extender.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compiles the macOS launcher and runs real commands through it: the Seatbelt equivalent of
 * the Linux image self-test. Needs a C compiler (Xcode command line tools).
 */
@EnabledOnOs(OS.MAC)
public class DarwinLauncherTest {
    private static final Path SOURCE = Path.of("scripts/standalone/sandbox/extender-sandbox-darwin.c").toAbsolutePath();
    private static Path launcher;

    @BeforeAll
    static void compileLauncher(@TempDir Path dir) throws Exception {
        assumeTrue(Files.isRegularFile(SOURCE), "launcher source not found at " + SOURCE);
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/cc")), "no C compiler");
        launcher = dir.resolve("extender-sandbox");
        Process cc = new ProcessBuilder("/usr/bin/cc", "-O2", "-Wall", "-Wextra", "-o", launcher.toString(), SOURCE.toString())
                .redirectErrorStream(true).start();
        String out = new String(cc.getInputStream().readAllBytes());
        assertTrue(cc.waitFor(2, TimeUnit.MINUTES) && cc.exitValue() == 0, "cc failed: " + out);
        SandboxLauncherProbe.Result probe = SandboxLauncherProbe.run(launcher);
        assumeTrue(probe.enforceable(), "Seatbelt unavailable: " + probe.description());
    }

    private static ProcessExecutor executor(Path jobDir) {
        SandboxConfiguration configuration = new SandboxConfiguration();
        configuration.setEnabled(true);
        configuration.setBackend(SandboxConfiguration.Backend.SEATBELT);
        configuration.setLauncherPath(launcher.toString());
        configuration.setStrict(true);
        configuration.setReadOnlyPaths(List.of("/usr", "/bin", "/sbin", "/System", "/Library", "/private/etc", "/opt"));
        configuration.setReadWritePaths(List.of("/dev"));
        ProcessExecutor pe = new ProcessExecutor(new ProcessSandbox(configuration));
        pe.setCwd(jobDir.toFile());
        return pe;
    }

    @Test
    public void jobDirectoryIsWritableAndSystemToolsRun(@TempDir Path jobDir) throws Exception {
        ProcessExecutor pe = executor(jobDir);
        pe.execute(List.of("/bin/sh", "-c", "echo hi > out.txt && cat out.txt && echo HOME=$HOME"));
        assertTrue(pe.getOutput().contains("hi\n"), pe.getOutput());
        assertTrue(pe.getOutput().contains("HOME=" + jobDir.toRealPath().resolve("home")), pe.getOutput());
        assertTrue(Files.exists(jobDir.resolve("out.txt")));
    }

    @Test
    public void unlistedPathsAreInvisible(@TempDir Path jobDir, @TempDir Path elsewhere) throws Exception {
        Path secret = Files.writeString(elsewhere.resolve("secret.txt"), "token");
        ProcessExecutor pe = executor(jobDir);
        IOException e = assertThrows(IOException.class, () -> pe.execute(List.of("/bin/cat", secret.toString())));
        assertTrue(e.getMessage().contains("Operation not permitted"), e.getMessage());
    }

    /**
     * The job directory is writable and must not also be executable, or a build could compile a
     * binary and run it. A path explicitly granted read-write-exec has to stay executable even
     * though it sits inside that writable tree - the shape SwiftPM needs, since it compiles
     * Package.swift, plugins and macros under the working directory and then runs them. The
     * profile expresses this as a deny over the writable set followed by a re-grant of the rwx
     * set, so this asserts against the kernel what the rule ordering is supposed to achieve.
     */
    @Test
    public void writableJobDirCannotExecButANestedExecGrantCan(@TempDir Path jobDir) throws Exception {
        Path plugins = Files.createDirectory(jobDir.resolve("plugins"));
        Path inJobDir = jobDir.resolve("tool");
        Path inPlugins = plugins.resolve("tool");
        // Compiled, not copied. Whether a copied system binary runs at all varies on Apple
        // Silicon - a copy of /bin/echo is SIGKILLed, a copy of /usr/bin/true is not - so a copy
        // can fail for reasons that have nothing to do with the sandbox, which would make the
        // allowed half of this test pass or fail for the wrong reason. A freshly built binary is
        // ad-hoc signed by the toolchain, and is what the case is really about anyway: a build
        // producing an executable and then running it.
        Path source = jobDir.resolve("tool.c");
        Files.writeString(source, "#include <stdio.h>\nint main(){puts(\"ran-from-the-exec-grant\");return 0;}\n");
        for (Path tool : List.of(inJobDir, inPlugins)) {
            Process cc = new ProcessBuilder("/usr/bin/cc", "-o", tool.toString(), source.toString())
                    .redirectErrorStream(true).start();
            String out = new String(cc.getInputStream().readAllBytes());
            assertTrue(cc.waitFor(2, TimeUnit.MINUTES) && cc.exitValue() == 0, "cc failed: " + out);
        }

        ProcessExecutor denied = executor(jobDir);
        assertThrows(IOException.class, () -> denied.execute(List.of(inJobDir.toString()),
                SandboxPolicy.toolchain()));

        ProcessExecutor allowed = executor(jobDir);
        allowed.execute(List.of(inPlugins.toString()),
                SandboxPolicy.toolchain().withReadWriteExecPaths(List.of(plugins.toString())));
        assertTrue(allowed.getOutput().contains("ran-from-the-exec-grant"), allowed.getOutput());

        // and the grant is scoped: it does not re-open the job directory around it
        ProcessExecutor stillDenied = executor(jobDir);
        assertThrows(IOException.class, () -> stillDenied.execute(List.of(inJobDir.toString()),
                SandboxPolicy.toolchain().withReadWriteExecPaths(List.of(plugins.toString()))));
    }

    @Test
    public void systemTreesAreReadOnly(@TempDir Path jobDir) {
        ProcessExecutor pe = executor(jobDir);
        assertThrows(IOException.class, () -> pe.execute(List.of("/bin/sh", "-c", "echo x > /usr/extender-sandbox-test")));
        assertTrue(!Files.exists(Path.of("/usr/extender-sandbox-test")));
    }

    @Test
    public void toolchainPolicyHasNoNetwork(@TempDir Path jobDir) {
        ProcessExecutor pe = executor(jobDir);
        IOException e = assertThrows(IOException.class,
                () -> pe.execute(List.of("/usr/bin/curl", "-sS", "-m", "3", "https://example.com")));
        assertTrue(e.getMessage().contains("Could not resolve host") || e.getMessage().contains("Operation not permitted"),
                e.getMessage());
    }

    @Test
    public void theServerProcessCannotBeSignalled(@TempDir Path jobDir) {
        ProcessExecutor pe = executor(jobDir);
        assertThrows(IOException.class,
                () -> pe.execute(List.of("/bin/sh", "-c", "kill -0 " + ProcessHandle.current().pid())));
    }

    @Test
    public void secretsAreScrubbedFromTheEnvironment(@TempDir Path jobDir) throws Exception {
        SandboxConfiguration configuration = new SandboxConfiguration();
        configuration.setEnabled(true);
        configuration.setBackend(SandboxConfiguration.Backend.SEATBELT);
        configuration.setLauncherPath(launcher.toString());
        configuration.setReadOnlyPaths(List.of("/usr", "/bin", "/System", "/Library", "/private/etc"));
        configuration.setEnvDenyPatterns(List.of(".*TOKEN.*"));
        ProcessExecutor pe = new ProcessExecutor(new ProcessSandbox(configuration,
                Map.of("PATH", "/usr/bin:/bin", "EXTENDER_TEST_DECOY_TOKEN", "decoy", "KEEP_ME", "yes")));
        pe.setCwd(jobDir.toFile());
        pe.execute(List.of("/bin/sh", "-c", "echo token=${EXTENDER_TEST_DECOY_TOKEN:-absent} keep=$KEEP_ME"));
        assertTrue(pe.getOutput().contains("token=absent keep=yes"), pe.getOutput());
    }

    @Test
    public void timeoutKillsTheCommand(@TempDir Path jobDir) {
        ProcessExecutor pe = executor(jobDir);
        pe.setCommandTimeout(1000);
        long start = System.currentTimeMillis();
        IOException e = assertThrows(IOException.class, () -> pe.execute(List.of("/bin/sleep", "30")));
        assertTrue(e.getMessage().contains("timed out"), e.getMessage());
        assertTrue(System.currentTimeMillis() - start < 15_000);
    }

    /**
     * setsid() cannot be forbidden (Seatbelt has no operation for it and macOS offers no syscall
     * filter), so a descendant can always leave the command's process group. The launcher's tag
     * sweep is what still reaches it.
     */
    @Test
    public void detachedDescendantsAreKilled(@TempDir Path jobDir) throws Exception {
        String marker = "extender-escapee-" + ProcessHandle.current().pid();
        ProcessExecutor pe = executor(jobDir);
        pe.execute(List.of("/usr/bin/perl", "-MPOSIX", "-e",
                "if (fork() == 0) { POSIX::setsid();"
                + " open(STDIN, \"</dev/null\"); open(STDOUT, \">/dev/null\"); open(STDERR, \">/dev/null\");"
                + " exec(\"/bin/sleep\", \"600\", \"" + marker + "\"); }"));

        assertTrue(findEscapee(marker).isEmpty(),
                "a detached descendant outlived the command: " + findEscapee(marker));
    }

    private static List<ProcessHandle> findEscapee(String marker) throws Exception {
        // the sweep runs as the launcher exits; give the kill a moment to land
        for (int attempt = 0; attempt < 20; attempt++) {
            List<ProcessHandle> found = ProcessHandle.allProcesses()
                    .filter(p -> p.info().commandLine().map(c -> c.contains(marker)).orElse(false))
                    .toList();
            if (found.isEmpty()) {
                return found;
            }
            Thread.sleep(100);
        }
        return ProcessHandle.allProcesses()
                .filter(p -> p.info().commandLine().map(c -> c.contains(marker)).orElse(false))
                .peek(ProcessHandle::destroyForcibly)
                .toList();
    }

    @Test
    public void degradedModeIsRefusedWhenStrict(@TempDir Path jobDir) throws Exception {
        // a launcher that cannot find sandbox-exec must not run the command in strict mode:
        // simulate by asking for a profile sandbox-exec rejects, which fails the command
        ProcessExecutor pe = executor(jobDir);
        SandboxConfiguration configuration = pe.getSandbox().configuration();
        configuration.getDarwin().setExtraRules(List.of("(this-is-not-sbpl)"));
        IOException e = assertThrows(IOException.class, () -> pe.execute(List.of("/usr/bin/true")));
        assertEquals(true, e.getMessage().contains("sandbox-exec"), e.getMessage());
    }
}
