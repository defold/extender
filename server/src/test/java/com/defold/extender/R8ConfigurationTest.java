package com.defold.extender;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

public class R8ConfigurationTest {
    @Test
    public void testR8LimitsBindFromServerConfiguration() {
        R8Configuration configuration = new Binder(new MapConfigurationPropertySource(Map.of(
                "extender.r8.max-generated-extension-classes", 11,
                "extender.r8.max-classfile-header-bytes", 12,
                "extender.r8.max-total-classfile-header-bytes", 13,
                "extender.r8.max-rule-files", 14,
                "extender.r8.max-rule-file-bytes", 15,
                "extender.r8.max-total-rule-bytes", 16)))
                .bind("extender.r8", R8Configuration.class)
                .get();

        assertEquals(11, configuration.getMaxGeneratedExtensionClasses());
        assertEquals(12, configuration.getMaxClassfileHeaderBytes());
        assertEquals(13, configuration.getMaxTotalClassfileHeaderBytes());
        assertEquals(14, configuration.getMaxRuleFiles());
        assertEquals(15, configuration.getMaxRuleFileBytes());
        assertEquals(16, configuration.getMaxTotalRuleBytes());
    }
}
