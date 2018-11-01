package com.defold.extender.cache.info;

import com.defold.extender.cache.CacheEntry;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

class CacheInfoWrapper {

    @JsonProperty("files")
    private List<CacheEntry> entries;

    @SuppressWarnings("unused")
    CacheInfoWrapper() {
    }

    CacheInfoWrapper(List<CacheEntry> entries) {
        this.entries = entries;
    }

    List<CacheEntry> getEntries() {
        return entries;
    }
}

