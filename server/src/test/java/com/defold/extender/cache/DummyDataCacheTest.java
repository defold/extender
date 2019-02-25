package com.defold.extender.cache;

import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

public class DummyDataCacheTest {

    private DataCache cache;

    @Before
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