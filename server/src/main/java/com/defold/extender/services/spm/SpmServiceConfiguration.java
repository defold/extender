package com.defold.extender.services.spm;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The per-build Xcode routing table, a map @Value cannot bind. One instance serves builds
 * pinned to different Xcode versions, so DEVELOPER_DIR is resolved per build from this map.
 * Also holds the time budget of a package graph's resolution.
 */
@Component
@ConfigurationProperties(prefix = "extender.spm")
public class SpmServiceConfiguration {

    // Xcode version (e.g. "26.2") -> developer dir (e.g. /Applications/Xcode_26.2.app/Contents/Developer)
    private Map<String, String> xcodeDeveloperDirs = new HashMap<>();
    // ms; no further mirror-and-build round of a package graph is started after this
    private long resolveTimeBudget = 900_000L;

    public Map<String, String> getXcodeDeveloperDirs() {
        return xcodeDeveloperDirs;
    }

    public void setXcodeDeveloperDirs(Map<String, String> xcodeDeveloperDirs) {
        this.xcodeDeveloperDirs = xcodeDeveloperDirs;
    }

    public long getResolveTimeBudget() {
        return resolveTimeBudget;
    }

    public void setResolveTimeBudget(long resolveTimeBudget) {
        this.resolveTimeBudget = resolveTimeBudget;
    }
}
