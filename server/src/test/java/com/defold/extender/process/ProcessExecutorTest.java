package com.defold.extender.process;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
