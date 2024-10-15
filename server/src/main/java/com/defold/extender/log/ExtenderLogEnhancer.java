package com.defold.extender.log;

import com.google.cloud.logging.LogEntry.Builder;
import com.google.cloud.logging.LoggingEnhancer;
import com.google.cloud.logging.logback.LoggingEventEnhancer;


import ch.qos.logback.classic.spi.ILoggingEvent;

public class ExtenderLogEnhancer implements LoggingEnhancer, LoggingEventEnhancer {
    @Override
    public void enhanceLogEntry(Builder builder) {
        if (ExtenderLogEnhancerConfiguration.isInitialized()) {
            builder.setResource(ExtenderLogEnhancerConfiguration.getMonitoredResource());
        }
    }

    @Override
    public void enhanceLogEntry(Builder builder, ILoggingEvent e) {
        enhanceLogEntry(builder);
    }
}
