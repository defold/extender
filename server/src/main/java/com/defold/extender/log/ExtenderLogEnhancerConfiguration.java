package com.defold.extender.log;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.google.cloud.MonitoredResource;
import com.google.cloud.spring.core.DefaultGcpProjectIdProvider;

@Component
@ConditionalOnProperty(name = "spring.cloud.gcp.logging.enabled", havingValue = "true")
public class ExtenderLogEnhancerConfiguration {
    
    private String instanceName;
    private String applicationName;
    private String instanceLocation;
    private String projectId;
    private MonitoredResource monitoredResource;

    private static ExtenderLogEnhancerConfiguration instance;
    private ExtenderLogEnhancerConfiguration(@Autowired Environment env) {
        if (instance != null) {
            throw new IllegalStateException("ExtenderLogEnhancerConfiguration already initialized");
        }
        instance = this;
        this.instanceName = env.getProperty("management.metrics.tags.instance", "unknown");
        this.applicationName = env.getProperty("management.metrics.tags.application", "unknown");
        this.instanceLocation = env.getProperty("extender.logging.instance-location", "europe-west1-b");
        this.projectId = new DefaultGcpProjectIdProvider().getProjectId();
        this.monitoredResource = MonitoredResource.newBuilder("generic_node")
                                            .addLabel("project_id", projectId)
                                            .addLabel("location", instanceLocation)
                                            .addLabel("namespace", applicationName)
                                            .addLabel("node_id", instanceName)
                                            .build();
    }

    public static boolean isInitialized() {
        return instance != null;
    }

    public static MonitoredResource getMonitoredResource() {
        return instance != null ? instance.monitoredResource : null;
    }
}
