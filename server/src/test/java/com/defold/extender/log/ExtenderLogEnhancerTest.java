package com.defold.extender.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.cloud.logging.LogEntry;
import com.google.cloud.logging.Payload;

public class ExtenderLogEnhancerTest {

    private static LogEntry.Builder entryWithMessage(String message) {
        Map<String, Object> json = new HashMap<>();
        json.put("message", message);
        json.put("@type", "type.googleapis.com/google.devtools.clouderrorreporting.v1beta1.ReportedErrorEvent");
        return LogEntry.newBuilder(Payload.JsonPayload.of(json));
    }

    private static String message(LogEntry.Builder builder) {
        Payload.JsonPayload payload = builder.build().getPayload();
        return (String) payload.getDataAsMap().get("message");
    }

    @Test
    public void oversizedMessageIsTruncatedBelowCloudLoggingLimit() {
        String huge = "Exception while building\n" + "é".repeat(3_500_000) + "\nCaused by: x";
        LogEntry.Builder builder = entryWithMessage(huge);

        new ExtenderLogEnhancer().enhanceLogEntry(builder);

        String message = message(builder);
        assertTrue(message.getBytes(StandardCharsets.UTF_8).length < 200 * 1024);
        assertTrue(message.startsWith("Exception while building"));
        assertTrue(message.endsWith("Caused by: x"));
        Payload.JsonPayload payload = builder.build().getPayload();
        assertTrue(payload.getDataAsMap().containsKey("@type"));
    }

    @Test
    public void normalMessageIsUntouched() {
        LogEntry.Builder builder = entryWithMessage("Build succeeded");
        new ExtenderLogEnhancer().enhanceLogEntry(builder);
        assertEquals("Build succeeded", message(builder));
    }
}
