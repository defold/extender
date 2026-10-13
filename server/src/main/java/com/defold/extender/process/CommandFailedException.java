package com.defold.extender.process;

import java.io.IOException;

public class CommandFailedException extends IOException {
    private final int exitCode;

    public CommandFailedException(int exitCode, String output) {
        super(output);
        this.exitCode = exitCode;
    }

    public int getExitCode() {
        return exitCode;
    }

    // The tool ran and rejected its input. 126/127 mean the tool could not be started,
    // and anything above 128 (or negative) means it was killed by a signal.
    public boolean isToolError() {
        return exitCode >= 1 && exitCode <= 125;
    }
}
