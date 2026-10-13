package com.defold.extender.log;

public final class LogTruncator {
    private LogTruncator() {}

    // Keeps the beginning and the end of the value, the result is at most maxLength chars
    public static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        int kept = Math.max(0, maxLength - marker(value.length(), "\n", "\n").length());
        int head = kept / 2;
        int tail = kept - head;
        int truncated = value.length() - head - tail;
        return value.substring(0, head) + marker(truncated, "\n", "\n") + value.substring(value.length() - tail);
    }

    // Keeps the end of the value, the result is at most maxLength chars
    public static String tail(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        int kept = Math.max(0, maxLength - marker(value.length(), "", "\n").length());
        int truncated = value.length() - kept;
        return marker(truncated, "", "\n") + value.substring(value.length() - kept);
    }

    private static String marker(int truncated, String prefix, String suffix) {
        return prefix + "(" + truncated + " chars truncated)" + suffix;
    }
}
