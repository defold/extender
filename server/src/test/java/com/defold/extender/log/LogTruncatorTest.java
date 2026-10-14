package com.defold.extender.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

public class LogTruncatorTest {
    private static final Pattern MARKER = Pattern.compile("\\n\\((\\d+) chars truncated\\)\\n");

    private static int truncatedCount(String result) {
        Matcher m = MARKER.matcher(result);
        assertTrue(m.find(), "missing truncation marker in: " + result);
        return Integer.parseInt(m.group(1));
    }

    @Test
    public void shortValueIsUnchanged() {
        String value = "short";
        assertSame(value, LogTruncator.truncate(value, 100));
        assertNull(LogTruncator.truncate(null, 100));
    }

    @Test
    public void truncateKeepsHeadAndTail() {
        String value = "HEAD" + "x".repeat(10_000) + "TAIL";
        String result = LogTruncator.truncate(value, 1000);
        assertTrue(result.startsWith("HEAD"));
        assertTrue(result.endsWith("TAIL"));
        assertTrue(result.length() <= 1000);
        int markerLength = result.length() - MARKER.matcher(result).replaceFirst("").length();
        assertEquals(value.length(), result.length() - markerLength + truncatedCount(result));
    }

    @Test
    public void resultNeverExceedsLimitJustAboveIt() {
        String value = "y".repeat(101);
        assertTrue(LogTruncator.truncate(value, 100).length() <= 100);
    }
}
