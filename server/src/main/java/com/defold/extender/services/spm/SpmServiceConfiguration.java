package com.defold.extender.services.spm;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The per-build Xcode routing table, a map @Value cannot bind. One instance serves builds
 * pinned to different Xcode versions, so DEVELOPER_DIR is resolved per build from this map.
 */
@Component
@ConfigurationProperties(prefix = "extender.spm")
public class SpmServiceConfiguration {

    // Xcode version (e.g. "26.2") -> developer dir (e.g. /Applications/Xcode_26.2.app/Contents/Developer)
    private Map<String, String> xcodeDeveloperDirs = new HashMap<>();

    public Map<String, String> getXcodeDeveloperDirs() {
        return xcodeDeveloperDirs;
    }

    public void setXcodeDeveloperDirs(Map<String, String> xcodeDeveloperDirs) {
        this.xcodeDeveloperDirs = xcodeDeveloperDirs;
    }
}
