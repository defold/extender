package com.defold.extender.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

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
    public void failureMessageHoldsOnlyFailingCommandNotWholeLog() throws Exception {
        ProcessExecutor pe = new ProcessExecutor();
        String earlierOutput = "EARLIER-" + "a".repeat(200_000);
        pe.putLog(earlierOutput + "\n");
        pe.execute(List.of("echo", "previous-command-output"));

        IOException e = assertThrows(IOException.class,
                () -> pe.execute(List.of("sh", "-c", "echo compile-error-line; exit 3")));

        String message = e.getMessage();
        assertTrue(message.contains("exit code 3"), message);
        assertTrue(message.contains("sh -c"), message);
        assertTrue(message.contains("compile-error-line"), message);
        assertFalse(message.contains("EARLIER-"), message);
        assertFalse(message.contains("previous-command-output"), message);

        assertTrue(pe.getOutput().contains(earlierOutput));
        assertTrue(pe.getOutput().contains("previous-command-output"));
        assertTrue(pe.getOutput().contains("compile-error-line"));
    }

    @Test
    public void failureMessageIsBoundedForHugeCommandAndOutput() {
        ProcessExecutor pe = new ProcessExecutor();
        String hugeArg = "--lib=" + "b".repeat(100_000);
        IOException e = assertThrows(IOException.class,
                () -> pe.execute(List.of("sh", "-c", "head -c 3000000 /dev/zero | tr '\\0' 'c'; echo; echo LAST-LINE; exit 1", hugeArg)));

        String message = e.getMessage();
        assertTrue(message.length() < 64 * 1024, "message length " + message.length());
        assertTrue(message.contains("LAST-LINE"), "the tail of the command output must be kept");
        assertTrue(pe.getOutput().length() > 3_000_000, "the full log must still be kept");
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
}
