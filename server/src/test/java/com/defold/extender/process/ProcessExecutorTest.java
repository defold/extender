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
