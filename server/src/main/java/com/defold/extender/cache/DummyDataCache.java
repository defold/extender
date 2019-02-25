package com.defold.extender.cache;

import java.io.File;
import java.io.InputStream;

public class DummyDataCache implements DataCache {

    @Override
    public InputStream get(final String key) {
        return null;
    }

    @Override
    public boolean exists(String key) {
        return false;
    }

    @Override
    public void touch(String key) {
        // Do nothing
    }

    @Override
    public void put(final String key, final File file) {
        // Do nothing
    }
}
