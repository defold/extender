package com.defold.extender;

import com.defold.extender.log.LogTruncator;

public class ExtenderException extends Exception {
    // The output can be a whole build log, which must not end up in logged stack traces
    static final int MAX_MESSAGE_LENGTH = 32 * 1024;

    private final String output;

    public ExtenderException(String output) {
        super(LogTruncator.truncate(output, MAX_MESSAGE_LENGTH));
        this.output = output;
    }

    public ExtenderException(Exception e, String output) {
        super(e);
        this.output = output;
    }

    String getOutput() {
        return output;
    }
}
