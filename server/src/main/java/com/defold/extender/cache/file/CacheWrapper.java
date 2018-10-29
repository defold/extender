package com.defold.extender.cache.file;

import com.defold.extender.cache.CacheEntry;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

class CacheWrapper {

    @JsonProperty("files")
    private List<CacheEntry> entries;

    @SuppressWarnings("unused")
    CacheWrapper() {
    }

    CacheWrapper(List<CacheEntry> entries) {
        this.entries = entries;
    }

    List<CacheEntry> getEntries() {
        return entries;
    }
}

