package com.defold.extender.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

public class ProcessExecutorTest {

    @Test
    public void testExecuteCommandsCallbackFiresOncePerCommand() throws Exception {
        ProcessExecutor processExecutor = new ProcessExecutor();
        List<String> commands = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            commands.add("echo file" + i);
        }
        AtomicInteger completed = new AtomicInteger();
        ProcessExecutor.executeCommands(processExecutor, commands, completed::incrementAndGet);
        assertEquals(16, completed.get());
    }

    @Test
    public void testExecuteCommandsWithoutCallback() throws Exception {
        ProcessExecutor processExecutor = new ProcessExecutor();
        List<String> commands = List.of("echo a", "echo b");
        // the 2-arg overload must still work unchanged
        ProcessExecutor.executeCommands(processExecutor, commands);
    }

    @Test
    public void testFailingCommandStillThrows() {
        ProcessExecutor processExecutor = new ProcessExecutor();
        List<String> commands = List.of("echo ok", "false");
        AtomicInteger completed = new AtomicInteger();
        assertThrows(IOException.class,
                () -> ProcessExecutor.executeCommands(processExecutor, commands, completed::incrementAndGet));
    }

    @Test
    public void putEnvMapSkipsNullValues() {
        // XCConfigParser puts System.getenv("XCTOOLCHAIN_PATH") into the map it passes here,
        // which is null whenever that variable is not set. ProcessBuilder.environment()
        // does not accept null values, so they have to be dropped rather than propagated.
        ProcessExecutor pe = new ProcessExecutor();
        Map<String, String> env = new HashMap<>();
        env.put("ARCHS", "arm64");
        env.put("TOOLCHAIN_DIR", null);
        pe.putEnv(env);

        assertEquals("arm64", pe.getEnv().get("ARCHS"));
        assertFalse(pe.getEnv().containsKey("TOOLCHAIN_DIR"));
        assertTrue(pe.getOutput().contains("TOOLCHAIN_DIR"));
    }

    @Test
    public void putEnvMapKeepsAllValidValues() {
        ProcessExecutor pe = new ProcessExecutor();
        pe.putEnv(Map.of("CP_HOME_DIR", "/tmp/.cocoapods", "CONFIGURATION", "Release"));

        assertEquals(2, pe.getEnv().size());
        assertEquals("/tmp/.cocoapods", pe.getEnv().get("CP_HOME_DIR"));
        assertEquals("Release", pe.getEnv().get("CONFIGURATION"));
    }

    @Test
    public void testCommandTimeoutKillsProcess() {
        ProcessExecutor pe = new ProcessExecutor();
        pe.setCommandTimeout(1000);
        long start = System.currentTimeMillis();
        IOException e = assertThrows(IOException.class, () -> pe.execute("sleep 30"));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(e.getMessage().contains("timed out"), e.getMessage());
        assertTrue(elapsed < 15_000, "took " + elapsed + " ms");
    }

    @Test
    public void testCommandWithinTimeoutSucceeds() throws Exception {
        ProcessExecutor pe = new ProcessExecutor();
        pe.setCommandTimeout(10_000);
        assertEquals(0, pe.execute("echo ok"));
        assertTrue(pe.getOutput().contains("ok"));
    }

    @Test
    public void testTimeoutIsOffWhenSandboxIsDisabled() {
        assertEquals(0, new ProcessExecutor().getCommandTimeout());
        assertEquals(SandboxPolicy.Network.NONE, new ProcessExecutor().getPolicy().network());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    public void testSandboxedCommandRunsThroughLauncher(@TempDir Path dir) throws Exception {
        Path launcher = FakeSandboxLauncher.write(dir);
        Path jobDir = java.nio.file.Files.createDirectory(dir.resolve("job"));
        ProcessExecutor pe = new ProcessExecutor(new ProcessSandbox(FakeSandboxLauncher.configuration(launcher)));
        pe.setCwd(jobDir.toFile());

        assertEquals(0, pe.execute("echo hi"));

        assertTrue(pe.getOutput().contains("hi"), pe.getOutput());
        // the log shows the command as written, not the launcher line
        assertTrue(pe.getOutput().startsWith("echo hi\n"), pe.getOutput());
        List<String> argv = FakeSandboxLauncher.recordedArgv(dir);
        assertTrue(argv.contains("--net"), argv.toString());
        assertTrue(argv.contains("none"), argv.toString());
        assertTrue(argv.contains("--rw"), argv.toString());
        assertTrue(argv.contains(jobDir.toAbsolutePath().normalize().toString()), argv.toString());
        assertTrue(argv.contains("--strict"), argv.toString());
        assertEquals(List.of("--", "echo", "hi"), argv.subList(argv.indexOf("--"), argv.size()));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    public void testExecuteCommandsAcceptsPolicyOverride(@TempDir Path dir) throws Exception {
        Path launcher = FakeSandboxLauncher.write(dir);
        Path jobDir = java.nio.file.Files.createDirectory(dir.resolve("job"));
        ProcessExecutor pe = new ProcessExecutor(new ProcessSandbox(FakeSandboxLauncher.configuration(launcher)));
        pe.setCwd(jobDir.toFile());

        ProcessExecutor.executeCommands(pe, List.of("echo a"), null, SandboxPolicy.dependencyResolver(List.of()));

        List<String> argv = FakeSandboxLauncher.recordedArgv(dir);
        assertEquals("all", argv.get(argv.indexOf("--net") + 1), argv.toString());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    public void testSandboxedEnvironmentIsRebuilt(@TempDir Path dir) throws Exception {
        Path launcher = FakeSandboxLauncher.write(dir);
        Path jobDir = java.nio.file.Files.createDirectory(dir.resolve("job"));
        ProcessExecutor pe = new ProcessExecutor(new ProcessSandbox(FakeSandboxLauncher.configuration(launcher)));
        pe.setCwd(jobDir.toFile());
        pe.putEnv("DYNAMO_HOME", "/sdk/from/overlay");

        pe.execute(List.of("sh", "-c", "echo HOME=$HOME DYNAMO_HOME=$DYNAMO_HOME"));

        String home = jobDir.toAbsolutePath().normalize().resolve("home").toString();
        assertTrue(pe.getOutput().contains("HOME=" + home), pe.getOutput());
        assertTrue(pe.getOutput().contains("DYNAMO_HOME=/sdk/from/overlay"), pe.getOutput());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    public void testSandboxedExecutorFailsClosedWithoutCwd(@TempDir Path dir) throws Exception {
        Path launcher = FakeSandboxLauncher.write(dir);
        ProcessExecutor pe = new ProcessExecutor(new ProcessSandbox(FakeSandboxLauncher.configuration(launcher)));
        assertThrows(IOException.class, () -> pe.execute("echo hi"));
    }

    /**
     * With the sandbox disabled prepare() returns env == null ("inherit as before"), which used to
     * drop the policy's own variables on the floor. They are hardening that does not depend on the
     * sandbox - CocoaPods keeps git away from the keychain with them - so they must still arrive.
     */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    public void testPolicyEnvReachesTheChildWithTheSandboxDisabled(@TempDir Path jobDir) throws Exception {
        ProcessExecutor pe = new ProcessExecutor(ProcessSandbox.disabled());
        pe.setCwd(jobDir.toFile());

        pe.execute(List.of("sh", "-c", "echo GIT_ASKPASS=$GIT_ASKPASS"),
                SandboxPolicy.toolchain().withEnv(Map.of("GIT_ASKPASS", "/usr/bin/true")));

        assertTrue(pe.getOutput().contains("GIT_ASKPASS=/usr/bin/true"), pe.getOutput());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    public void testProcessUtilsPassesPolicyThrough(@TempDir Path dir) throws Exception {
        // ProcessUtils creates its own executor from ProcessSandbox.current(), which the tests
        // never install; the policy is still applied to the executor it builds.
        Path jobDir = java.nio.file.Files.createDirectory(dir.resolve("job"));
        String out = ProcessUtils.execCommand(List.of("echo", "utils"), jobDir.toFile(), Map.of(),
                SandboxPolicy.dependencyResolver(List.of()));
        assertTrue(out.contains("utils"), out);
    }
}
