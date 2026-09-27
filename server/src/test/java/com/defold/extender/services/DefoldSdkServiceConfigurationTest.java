package com.defold.extender.services;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.*;

class DefoldSdkServiceConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DefoldSdkServiceConfiguration.class)
    static class PropertiesConfiguration {}

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsOrderedPairs() {
        contextRunner.withPropertyValues(
            "extender.sdk.sources[0].mappings-url=https://public.example/%s/platform.sdks.json",
            "extender.sdk.sources[0].sdk-url=https://public.example/%s/defoldsdk.zip",
            "extender.sdk.sources[1].mappings-url=https://switch.example/%s/platform.sdks.json",
            "extender.sdk.sources[1].sdk-url=https://switch.example/%s/defoldsdk.zip"
        ).run(context -> {
            assertNull(context.getStartupFailure());
            var sources = context.getBean(DefoldSdkServiceConfiguration.class).getSources();
            assertEquals(2, sources.size());
            assertEquals("https://public.example/%s/platform.sdks.json", sources.get(0).getMappingsUrl());
            assertEquals("https://switch.example/%s/defoldsdk.zip", sources.get(1).getSdkUrl());
        });
    }

    @Test
    void bindsDefaultApplicationConfiguration() throws Exception {
        var properties = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        contextRunner.withInitializer(context -> properties.forEach(source ->
            context.getEnvironment().getPropertySources().addLast(source)))
            .run(context -> {
                assertNull(context.getStartupFailure());
                var sources = context.getBean(DefoldSdkServiceConfiguration.class).getSources();
                assertEquals(2, sources.size());
                assertEquals("https://d.defold.com/archive/stable/%s/engine/platform.sdks.json", sources.get(0).getMappingsUrl());
                assertEquals("https://d.defold.com/archive/%s/engine/defoldsdk.zip", sources.get(1).getSdkUrl());
            });
    }

    @Test
    void pairsLegacyUrlListsInsteadOfSilentlyUsingDefaultSources() {
        contextRunner.withPropertyValues(
            "extender.sdk.sources[0].mappings-url=https://public.example/%s/platform.sdks.json",
            "extender.sdk.sources[0].sdk-url=https://public.example/%s/defoldsdk.zip",
            "extender.sdk.sdk-urls=https://switch.example/%s/defoldsdk.zip",
            "extender.sdk.mappings-urls=https://switch.example/%s/platform.sdks.json"
        ).run(context -> {
            assertNull(context.getStartupFailure());
            var sources = context.getBean(DefoldSdkServiceConfiguration.class).getConfiguredSources();
            assertEquals(1, sources.size());
            assertEquals("https://switch.example/%s/defoldsdk.zip", sources.get(0).getSdkUrl());
        });
    }

    @Test
    void pairsLegacyUrlsByIdentityInMappingPriorityOrder() {
        var configuration = DefoldSdkServiceConfiguration.builder()
            .sdkUrls(new String[]{"https://public.example/%s/defoldsdk.zip", "https://switch.example/%s/defoldsdk.zip"})
            .mappingsUrls(new String[]{"https://switch.example/%s/platform.sdks.json", "https://public.example/%s/platform.sdks.json"})
            .build();
        var sources = configuration.getConfiguredSources();
        assertEquals("https://switch.example/%s/defoldsdk.zip", sources.get(0).getSdkUrl());
        assertEquals("https://public.example/%s/defoldsdk.zip", sources.get(1).getSdkUrl());
    }

    @Test
    void rejectsAmbiguousLegacyUrlLists() {
        var configuration = DefoldSdkServiceConfiguration.builder()
            .sdkUrls(new String[]{"https://public.example/%s/defoldsdk.zip"})
            .mappingsUrls(new String[]{"https://switch.example/%s/platform.sdks.json"}).build();
        assertThrows(IllegalArgumentException.class, configuration::getConfiguredSources);
    }

}
