package com.defold.extender.log;

public final class LogTruncator {
    private LogTruncator() {}

    // Keeps the beginning and the end, the result is at most maxLength chars
    public static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        int kept = Math.max(0, maxLength - marker(value.length()).length());
        int head = kept / 2;
        int tail = kept - head;
        int truncated = value.length() - head - tail;
        return value.substring(0, head) + marker(truncated) + value.substring(value.length() - tail);
    }

    private static String marker(int truncated) {
        return "\n(" + truncated + " chars truncated)\n";
    }
}
