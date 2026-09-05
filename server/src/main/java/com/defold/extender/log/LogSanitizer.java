package com.defold.extender.log;

public final class LogSanitizer {
    private LogSanitizer() {}

    public static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); ++i) {
            char c = value.charAt(i);
            if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c >= 0x20 || c == '\t') {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
