package com.defold.extender.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class RemoteInstanceConfigTest {

    @Test
    public void theConstructorArgumentsAreExposedByTheGetters() {
        RemoteInstanceConfig config = new RemoteInstanceConfig("https://darwin.example.com", "osx-1", true);

        assertEquals("https://darwin.example.com", config.getUrl());
        assertEquals("osx-1", config.getInstanceId());
        assertTrue(config.getAlwaysOn());
    }

    @Test
    public void alwaysOnIsCarriedThroughWhenFalse() {
        assertFalse(new RemoteInstanceConfig("https://win.example.com", "win-1", false).getAlwaysOn());
    }

    @Test
    public void anAlwaysOnInstanceMayHaveNoInstanceId() {
        // Instances that are never suspended have nothing for GCPInstanceService to touch, so
        // application.yml leaves instance-id unset for them.
        RemoteInstanceConfig config = new RemoteInstanceConfig("https://darwin.example.com", null, true);

        assertNull(config.getInstanceId());
        assertEquals("https://darwin.example.com", config.getUrl());
    }
}
