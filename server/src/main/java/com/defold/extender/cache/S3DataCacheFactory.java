package com.defold.extender.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class S3DataCacheFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(S3DataCacheFactory.class);

    private final String bucketName;

    @Autowired
    public S3DataCacheFactory(@Value("${extender.cache.s3.bucket:}") String bucketName) {
        this.bucketName = bucketName;
    }

    public DataCache createCache() {
        LOGGER.info("Creating S3 cache with bucket name {}", bucketName);
        return new S3DataCache(bucketName);
    }
}
