package com.defold.extender.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

public class ProcessExecutorTest {

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
