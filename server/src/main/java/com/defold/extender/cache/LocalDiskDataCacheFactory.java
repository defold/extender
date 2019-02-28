package com.defold.extender.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class LocalDiskDataCacheFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalDiskDataCacheFactory.class);

    private final String baseDirectory;

    @Autowired
    public LocalDiskDataCacheFactory(@Value("${extender.cache.local.basedir:}") String baseDirectory) {
        this.baseDirectory = baseDirectory;
    }

    public DataCache createCache() {
        LOGGER.info("Creating local disk cache in directory {}", baseDirectory);
        try {
            return new LocalDiskDataCache(baseDirectory);
        } catch (IOException e) {
            throw new IllegalArgumentException("Illegal base directory for local disk cache: " + baseDirectory, e);
        }
    }
}
