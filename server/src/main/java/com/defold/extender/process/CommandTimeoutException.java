package com.defold.extender.process;

import java.io.IOException;

/**
 * A command was killed by {@link ProcessExecutor}'s wall-clock watchdog rather than failing on
 * its own. Callers that retry or interpret a command's output need to tell the two apart: a
 * timed-out command's output is a partial log, and repeating it only spends the timeout again.
 */
public class CommandTimeoutException extends IOException {
    public CommandTimeoutException(String message) {
        super(message);
    }
}
