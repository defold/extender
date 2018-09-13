package com.defold.extender;

import java.util.HashMap;
import java.util.Map;

public class AppManifestConfiguration {
    public String __base_variant = "";
    public Map<String, AppManifestPlatformConfig> platforms = new HashMap<>();
    public Map<String, Object> context = new HashMap<>();
}
