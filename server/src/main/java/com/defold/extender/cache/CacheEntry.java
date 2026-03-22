package com.defold.extender.cache;

public class CacheEntry {

    private String key;
    private String path;
    private Boolean cached = false;

    @SuppressWarnings("unused")
    CacheEntry() {
    }

    public CacheEntry(final String key, final String path, final Boolean cached) {
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
        return cached != null && cached;
    }

    public void setCached(final boolean cached) {
        this.cached = cached;
    }
}
