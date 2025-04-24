package com.defold.extender.services.cocoapods;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class PodUtilsTest {

    @Test
    public void testPodNameSanitize() {
        String[] testedNames = new String[] {
            "DivKit_LayoutKit",
            "GoogleUserMessagingPlatform",
            "Google-Mobile-Ads-SDK",
            "UIAlertController+Blocks",
            "Socket.IO-Client-Swift"
        };
        String[] expectedNames = new String[] {
            "DivKit_LayoutKit",
            "GoogleUserMessagingPlatform",
            "Google-Mobile-Ads-SDK",
            "UIAlertController\\+Blocks",
            "Socket.IO-Client-Swift"
        };
        assertEquals(testedNames.length, expectedNames.length);
        for (int idx = 0; idx < testedNames.length; ++idx) {
            assertEquals(expectedNames[idx], PodUtils.sanitizePodName(testedNames[idx]));
        }
    }
}
