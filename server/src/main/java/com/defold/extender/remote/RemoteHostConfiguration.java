package com.defold.extender.remote;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "extender.remote-builder")
public class RemoteHostConfiguration {
    private Map<String, RemoteInstanceConfig> platforms = new HashMap<>();

    public Map<String, RemoteInstanceConfig> getPlatforms() {
        return platforms;
    }
}
