package com.defold.extender;

public enum BuildFailure {
    NONE("none"),
    USER("user"),
    SERVER("server"),
    ABORTED("aborted");

    private final String tag;

    BuildFailure(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }
}
