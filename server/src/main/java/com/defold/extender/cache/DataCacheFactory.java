package com.defold.extender.cache;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class DataCacheFactory {

    private static final String STORE_TYPE_S3 = "S3";
    private static final String STORE_TYPE_LOCAL = "LOCAL";

    private final String storeType;
    private final String baseDirectory;
    private final String bucketName;

    public DataCacheFactory(@Value("${extender.cache.type}") String storeType,
                            @Value("${extender.cache.local.basedir}") String baseDirectory,
                            @Value("${extender.cache.s3.bucket}") String bucketName) {
        this.storeType = storeType;
        this.baseDirectory = baseDirectory;
        this.bucketName = bucketName;
    }

    public DataCache createCache() {
        if (STORE_TYPE_S3.equals(storeType)) {
            return new S3DataCache(bucketName);
        } else if (STORE_TYPE_LOCAL.equals(storeType)) {
            try {
                return new SimpleDiskDataCache(baseDirectory);
            } catch (IOException e) {
                throw new IllegalArgumentException("Illegal base directory for local disk cache: " + baseDirectory, e);
            }
        } else {
            throw new IllegalArgumentException(String.format("No cache store of type %s implemented!", storeType));
        }
    }
}
