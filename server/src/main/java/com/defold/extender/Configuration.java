package com.defold.extender;

import java.util.Map;

public class Configuration {
    public Map<String, PlatformConfig> platforms;
    public Map<String, Object> context;
    public String main;
    public WhitelistConfig whitelist; // Already a deprecated var! TODO: Remove
}
