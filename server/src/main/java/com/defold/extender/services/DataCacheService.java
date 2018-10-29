package com.defold.extender.services;

import com.defold.extender.ExtenderException;
import com.defold.extender.cache.CacheEntry;
import com.defold.extender.cache.file.CacheFileParser;
import com.defold.extender.cache.file.CacheFileWriter;
import com.defold.extender.cache.CacheKeyGenerator;
import com.defold.extender.cache.DataCache;
import com.defold.extender.cache.S3DataCache;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class DataCacheService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataCacheService.class);

    static final String FILE_CACHE_INFO_FILE = "ne-cache-info.json";

    private static final String STORE_TYPE_S3 = "S3";
    private static final String STORE_TYPE_LOCAL = "LOCAL";

    private final CacheKeyGenerator cacheKeyGenerator;
    private final CacheFileParser cacheFileParser;
    private final CacheFileWriter cacheFileWriter;
    private final DataCache cacheStore;

    private int fileSizeThreshold;

    @Autowired
    DataCacheService(final CacheKeyGenerator cacheKeyGenerator,
                     final CacheFileParser cacheFileParser,
                     final CacheFileWriter cacheFileWriter,
                     @Value("${extender.cache-store.type}") String storeType,
                     @Value("${extender.cache-store.s3.bucket}") String bucketName,
                     @Value("${extender.cache-store.file-size-threshold}") int fileSizeThreshold) {

        this.cacheKeyGenerator = cacheKeyGenerator;
        this.cacheFileParser = cacheFileParser;
        this.cacheFileWriter = cacheFileWriter;
        this.fileSizeThreshold = fileSizeThreshold;

        if (STORE_TYPE_S3.equals(storeType)) {
            cacheStore = new S3DataCache(bucketName);
        } else if (STORE_TYPE_LOCAL.equals(storeType)) {
            throw new IllegalArgumentException("Local cache store is not implemented!");
        } else {
            throw new IllegalArgumentException(String.format("No cache store of type %s implemented!", storeType));
        }
    }

    boolean isCached(final String key) {
        return cacheStore.exists(key);
    }

    public long cacheFiles(final File directory) throws IOException {
        LOGGER.debug(String.format("Caching files in directory %s", directory.getPath()));

        final AtomicLong totalbytesCached = new AtomicLong();
        Files.walk(directory.toPath())
                .filter(Files::isRegularFile)
                .forEach(path -> {
                    try {
                        totalbytesCached.addAndGet(upload(path.toFile()));
                    } catch (IOException e) {
                        LOGGER.error("Could not cache file " + path.toString(), e);
                    }
                });
        return totalbytesCached.longValue();
    }

    private long upload(File file) throws IOException {
        // Check the size of the file
        if (file.length() < fileSizeThreshold) {
            LOGGER.debug(String.format("[cache] %s - SKIPPED", file.getName()));
            return 0;
        }

        String key = cacheKeyGenerator.generate(file);

        LOGGER.debug(String.format("[cache] %s - %s", file.getName(), key));
        cacheStore.put(key, file);

        return file.length();
    }

    // Step through the entries in the json and download them from the key-value server
    public int getCachedFiles(File directory) throws IOException, ExtenderException {
        File cacheInfoFile = new File(directory, FILE_CACHE_INFO_FILE);

        // Support older editors that don't supply a cache info file
        if (!cacheInfoFile.exists()) {
            return 0;
        }
        LOGGER.info("Downloading cached files");

        List<CacheEntry> cacheEntries = cacheFileParser.parse(cacheInfoFile);
        int numCachedFiles = 0;

        for (CacheEntry entry : cacheEntries) {
            // Check if entry is cached
            if (! entry.isCached()) {
                continue;
            }

            verifyCacheEntry(entry);

            File destination = new File(directory, entry.getPath());
            makeParentDirectories(destination);
            downloadFile(entry.getKey(), destination);

            if (! destination.exists()) {
                throw new ExtenderException(String.format("Failed downloading '%s' (%s) from cache",
                        entry.getPath(), entry.getKey()));
            }

            verifyCacheKey(destination, entry.getKey());
            numCachedFiles++;
        }

        LOGGER.info(String.format("Downloaded %d cached files", numCachedFiles));

        return numCachedFiles;
    }

    private void verifyCacheEntry(CacheEntry entry) throws ExtenderException {
        if (StringUtils.isBlank(entry.getPath())) {
            throw new ExtenderException("Corrupt json, missing 'path' field");
        }
        if (StringUtils.isBlank(entry.getKey())) {
            throw new ExtenderException("Corrupt json, missing 'key' field");
        }
    }

    private void verifyCacheKey(File file, String originalKey) throws ExtenderException, IOException {
        String newKey = cacheKeyGenerator.generate(file);
        if (! newKey.equals(originalKey)) {
            throw new ExtenderException(String.format("The checksum of the file '%s' differs from the one in the cache: %s != %s", file.getAbsolutePath(), originalKey, newKey));
        }
    }

    private boolean makeParentDirectories(File file) {
        return file.getParentFile().exists() || file.getParentFile().mkdirs();
    }

    void downloadFile(String key, File destination) throws IOException {
        try (InputStream inputStream = cacheStore.get(key)) {
            Files.copy(
                    inputStream,
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Reads a JSON file which contains an entry for each file, with corresponding checksum (sha256)
     * It modifies the json with the cache status for that file, and then writes the result to another json output file
     *   "files": [{"path": "a/b", "key": "<sha256>"}] ->
     *   "files": [{"path": "a/b", "key": "<sha256>", "cached": true/false}]
     */
    public void queryCache(InputStream input, OutputStream output) throws IOException, ExtenderException {
        List<CacheEntry> cacheEntries = cacheFileParser.parse(input);
        for (CacheEntry entry : cacheEntries) {
            verifyCacheEntry(entry);
            entry.setCached(isCached(entry.getKey()));
        }

        try {
            cacheFileWriter.write(cacheEntries, output);
        } catch (IOException e) {
            throw new ExtenderException("Failed to write result json: " + e.getMessage());
        }
    }
}
