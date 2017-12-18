package com.defold.extender;

public class Timer {
    private long time;

    public long start() {
        long now = System.currentTimeMillis();
        long t = now - time;
        time = now;
        return t;
    }
}
