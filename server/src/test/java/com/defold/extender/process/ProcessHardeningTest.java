package com.defold.extender.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs({OS.LINUX})
public class ProcessHardeningTest {

    /** A same-uid child reads this JVM's environment before, and cannot after. */
    @Test
    public void testNonDumpableProcessHidesItsEnvironment() throws Throwable {
        String environ = "/proc/" + ProcessHandle.current().pid() + "/environ";
        assertTrue(readable(environ), "a same-uid process should read " + environ + " of a dumpable JVM");

        assertEquals(0, ProcessHardening.makeNonDumpable());
        assertFalse(readable(environ), environ + " is still readable after PR_SET_DUMPABLE=0");
        // the process' own view is unaffected
        assertTrue(ProcessHandle.current().info().command().isPresent());
    }

    private static boolean readable(String path) throws Exception {
        Process cat = new ProcessBuilder("cat", path).redirectErrorStream(true).start();
        String output = new String(cat.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(cat.waitFor(30, TimeUnit.SECONDS), "cat hung");
        return cat.exitValue() == 0 && !output.isEmpty();
    }
}
