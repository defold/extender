package com.defold.extender.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * RemoteInstanceConfig has no setters and no no-arg constructor, so the platforms map can only be
 * populated by Spring's value-object (constructor) binding. That holds only while the class keeps
 * exactly one constructor. If it silently stops binding, every remote build loses its target URL,
 * and nothing else in the test suite would notice.
 */
public class RemoteHostConfigurationTest {

    private static final String PREFIX = "extender.remote-builder";

    private static BindResult<RemoteHostConfiguration> bind(Map<String, Object> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind(PREFIX, RemoteHostConfiguration.class);
    }

    private static Map<String, Object> platformProperties(String platform, String url, String instanceId, String alwaysOn) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(String.format("%s.platforms.%s.url", PREFIX, platform), url);
        properties.put(String.format("%s.platforms.%s.instance-id", PREFIX, platform), instanceId);
        properties.put(String.format("%s.platforms.%s.always-on", PREFIX, platform), alwaysOn);
        return properties;
    }

    @Test
    public void aPlatformIsBoundThroughTheRemoteInstanceConfigConstructor() {
        RemoteHostConfiguration configuration =
                bind(platformProperties("osx-latest", "https://darwin.example.com", "osx-1", "true")).get();

        assertEquals(1, configuration.getPlatforms().size());
        RemoteInstanceConfig osx = configuration.getPlatforms().get("osx-latest");
        assertEquals("https://darwin.example.com", osx.getUrl());
        assertEquals("osx-1", osx.getInstanceId());
        assertTrue(osx.getAlwaysOn());
    }

    @Test
    public void relaxedBindingMapsKebabCasePropertiesOntoCamelCaseConstructorParameters() {
        // instance-id -> instanceId, always-on -> alwaysOn. This is what application.yml actually writes.
        RemoteInstanceConfig config =
                bind(platformProperties("android-ndk25", "https://android.example.com", "android-7", "false"))
                        .get().getPlatforms().get("android-ndk25");

        assertEquals("android-7", config.getInstanceId());
        assertFalse(config.getAlwaysOn());
    }

    @Test
    public void severalPlatformsAreBoundIndependently() {
        Map<String, Object> properties = platformProperties("osx-latest", "https://darwin.example.com", "osx-1", "true");
        properties.putAll(platformProperties("windows-latest", "https://win.example.com", "win-1", "false"));

        Map<String, RemoteInstanceConfig> platforms = bind(properties).get().getPlatforms();

        assertEquals(2, platforms.size());
        assertEquals("https://darwin.example.com", platforms.get("osx-latest").getUrl());
        assertTrue(platforms.get("osx-latest").getAlwaysOn());
        assertEquals("https://win.example.com", platforms.get("windows-latest").getUrl());
        assertFalse(platforms.get("windows-latest").getAlwaysOn());
    }

    @Test
    public void aPlatformKeyContainingDashesSurvivesBinding() {
        // Platform keys such as "x86_64-osx" and "arm64-ios" are map keys, not property names, so
        // relaxed binding must not rewrite them.
        Map<String, RemoteInstanceConfig> platforms =
                bind(platformProperties("x86_64-osx", "https://darwin.example.com", "osx-1", "true"))
                        .get().getPlatforms();

        assertTrue(platforms.containsKey("x86_64-osx"), String.format("Bound keys: %s", platforms.keySet()));
    }

    @Test
    public void noPropertiesUnderThePrefixLeavesTheConfigurationUnbound() {
        assertFalse(bind(Map.of("extender.job-result.location", "/var/tmp/results")).isBound());
    }

    @Test
    public void aFreshConfigurationExposesAnEmptyPlatformsMap() {
        assertTrue(new RemoteHostConfiguration().getPlatforms().isEmpty());
    }
}
