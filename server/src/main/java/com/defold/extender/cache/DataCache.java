package com.defold.extender.cache;

import java.io.File;
import java.io.InputStream;

public interface DataCache {
    InputStream get(String key);
    boolean exists(String key);
    String put(String key, File file);
}
