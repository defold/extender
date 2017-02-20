package com.defold.extender;

class ExtenderException extends Exception {
    private final String output;

    ExtenderException(String output) {
        super(output);
        this.output = output;
    }

    ExtenderException(Exception e, String output) {
        super(e);
        this.output = output;
    }

    String getOutput() {
        return output;
    }
}
