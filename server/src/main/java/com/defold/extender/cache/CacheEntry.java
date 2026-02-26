package com.defold.extender.cache;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

public class CacheEntry {

    private String key;
    private String path;
    private boolean cached = false;

    @SuppressWarnings("unused")
    CacheEntry() {
    }

    public CacheEntry(final String key, final String path, final boolean cached) {
        this.key = key;
        this.path = path;
        this.cached = cached;
    }

    public String getKey() {
        return key;
    }

    public void setKey(final String key) {
        this.key = key;
    }

    public String getPath() {
        return path;
    }

    public void setPath(final String path) {
        this.path = path;
    }

    public boolean isCached() {
        return cached;
    }

    @JsonSetter(nulls = Nulls.SKIP)
    public void setCached(final boolean cached) {
        this.cached = cached;
    }
}
