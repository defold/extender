package com.defold.extender.log;

import java.util.HashMap;
import java.util.Map;

import com.google.cloud.logging.LogEntry.Builder;
import com.google.cloud.logging.LoggingEnhancer;
import com.google.cloud.logging.Payload;
import com.google.cloud.logging.logback.LoggingEventEnhancer;


import ch.qos.logback.classic.spi.ILoggingEvent;

public class ExtenderLogEnhancer implements LoggingEnhancer, LoggingEventEnhancer {
    // Cloud Logging rejects entries above 256 KB; a char may take up to 3 bytes in UTF-8
    static final int MAX_MESSAGE_LENGTH = 32 * 1024;

    @Override
    public void enhanceLogEntry(Builder builder) {
        if (ExtenderLogEnhancerConfiguration.isInitialized()) {
            builder.setResource(ExtenderLogEnhancerConfiguration.getMonitoredResource());
        }
        truncateMessage(builder);
    }

    @Override
    public void enhanceLogEntry(Builder builder, ILoggingEvent e) {
        enhanceLogEntry(builder);
    }

    private static void truncateMessage(Builder builder) {
        if (!(builder.build().getPayload() instanceof Payload.JsonPayload payload)) {
            return;
        }
        Map<String, Object> data = payload.getDataAsMap();
        if (!(data.get("message") instanceof String message) || message.length() <= MAX_MESSAGE_LENGTH) {
            return;
        }
        Map<String, Object> truncated = new HashMap<>(data);
        truncated.put("message", LogTruncator.truncate(message, MAX_MESSAGE_LENGTH));
        builder.setPayload(Payload.JsonPayload.of(truncated));
    }
}
