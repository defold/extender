package com.defold.extender.cache;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class DummyDataCacheTest {

    private DataCache cache;

    @BeforeEach
    public void setUp() {
        cache = new DummyDataCache();
    }

    @Test
    public void makeSureCacheAlwaysReturnsNull() throws Exception {
        assertNull(cache.get("iAmNotHere"));
        assertFalse(cache.exists("iAmNotHere"));

        File file = new File(ClassLoader.getSystemResource("upload/dir/test1.txt").toURI());
        cache.put("iAmNotHere", file);

        assertNull(cache.get("iAmNotHere"));
        assertFalse(cache.exists("iAmNotHere"));
    }
}