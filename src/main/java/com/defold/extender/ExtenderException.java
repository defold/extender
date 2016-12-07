package com.defold.extender;

public class ExtenderException extends Exception {
    private final String output;

    public ExtenderException(Exception e, String output) {
        super(e);
        this.output = output;
    }

    public String getOutput() {
        return output;
    }
}
