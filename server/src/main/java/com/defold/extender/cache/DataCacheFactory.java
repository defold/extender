package com.defold.extender.cache;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DataCacheFactory {

    private static final String STORE_TYPE_S3 = "S3";
    private static final String STORE_TYPE_LOCAL = "LOCAL";

    private final String storeType;
    private final String bucketName;

    public DataCacheFactory(@Value("${extender.cache-store.type}") String storeType,
                            @Value("${extender.cache-store.s3.bucket}") String bucketName) {
        this.storeType = storeType;
        this.bucketName = bucketName;
    }

    public DataCache createCache() {
        if (STORE_TYPE_S3.equals(storeType)) {
            return new S3DataCache(bucketName);
        } else if (STORE_TYPE_LOCAL.equals(storeType)) {
            throw new IllegalArgumentException("Local cache store is not implemented!");
        } else {
            throw new IllegalArgumentException(String.format("No cache store of type %s implemented!", storeType));
        }
    }
}
