package com.defold.extender.cache;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DataCacheFactory {

    private static final String STORE_TYPE_S3 = "S3";
    private static final String STORE_TYPE_LOCAL = "LOCAL";

    private LocalDiskDataCacheFactory localDiskDataCacheFactory;
    private S3DataCacheFactory s3DataCacheFactory;

    private final String cacheType;

    @Autowired
    public DataCacheFactory(LocalDiskDataCacheFactory localDiskDataCacheFactory,
                            S3DataCacheFactory s3DataCacheFactory,
                            @Value("${extender.cache.type:}") String cacheType) {
        this.localDiskDataCacheFactory = localDiskDataCacheFactory;
        this.s3DataCacheFactory = s3DataCacheFactory;
        this.cacheType = cacheType;
    }

    public DataCache createCache() {
        if (STORE_TYPE_S3.equals(cacheType)) {
            return s3DataCacheFactory.createCache();
        } else if (STORE_TYPE_LOCAL.equals(cacheType)) {
            return localDiskDataCacheFactory.createCache();
        } else {
            throw new IllegalArgumentException(String.format("No cache store of type %s implemented!", cacheType));
        }
    }
}
