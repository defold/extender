package com.defold.extender.process;

import java.io.IOException;

/**
 * A command could not be started at all (sandbox preparation failed, launcher or tool missing),
 * as opposed to one that ran and failed. Its output is only the logged command line.
 */
public class ProcessLaunchException extends IOException {
    public ProcessLaunchException(String message, Throwable cause) {
        super(message, cause);
    }
}
