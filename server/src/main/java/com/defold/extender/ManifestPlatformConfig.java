package com.defold.extender;

import java.util.HashMap;
import java.util.Map;

class ManifestPlatformConfig {
    public Map<String, Object> bundle = new HashMap<>();      // used on the content pipeline side
    public Map<String, Object> context = new HashMap<>();
}
