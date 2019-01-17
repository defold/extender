package com.defold.extender.cache.info;

import com.defold.extender.cache.CacheEntry;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class CacheInfoWrapper {

    private int version;

    private String hashType;

    @JsonProperty("files")
    private List<CacheEntry> entries;

    @SuppressWarnings("unused")
    CacheInfoWrapper() {
    }

    // Used when writing to disc
    CacheInfoWrapper(int version, String hashType, List<CacheEntry> entries) {
        this.version = version;
        this.hashType = hashType;
        this.entries = entries;
    }

    public List<CacheEntry> getEntries() {
        return entries;
    }

    public int getVersion() {
        return version;
    }

    public String getHashType() {
        return hashType;
    }
}

