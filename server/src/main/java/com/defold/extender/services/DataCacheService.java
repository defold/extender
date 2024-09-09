package com.defold.extender.services;

import com.defold.extender.ExtenderException;
import com.defold.extender.cache.CacheEntry;
import com.defold.extender.cache.DataCacheFactory;
import com.defold.extender.cache.info.CacheInfoFileParser;
import com.defold.extender.cache.info.CacheInfoFileWriter;
import com.defold.extender.cache.info.CacheInfoWrapper;
import com.defold.extender.cache.CacheKeyGenerator;
import com.defold.extender.cache.DataCache;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class DataCacheService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataCacheService.class);

    static final String FILE_CACHE_INFO_FILE = "ne-cache-info.json";
    static final String FILE_CACHE_INFO_HASH_TYPE = "sha256";
    static final int    FILE_CACHE_INFO_VERSION = 1;

    private final CacheKeyGenerator cacheKeyGenerator;
    private final CacheInfoFileParser cacheInfoFileParser;
    private final CacheInfoFileWriter cacheInfoFileWriter;
    private final boolean cacheIsEnabled;
    private final int fileSizeThreshold;

    private DataCache dataCache;

    public class DataCacheServiceInfo {
        public AtomicInteger cachedFileCount = new AtomicInteger();
        public AtomicLong cachedFileSize = new AtomicLong();
    }

    DataCacheService(final CacheKeyGenerator cacheKeyGenerator,
                     final CacheInfoFileParser cacheInfoFileParser,
                     final CacheInfoFileWriter cacheInfoFileWriter,
                     final DataCacheFactory dataCacheFactory,
                     @Value("${extender.cache.enabled}") boolean cacheIsEnabled,
                     @Value("${extender.cache.file-size-threshold}") int fileSizeThreshold) {

        this.cacheKeyGenerator = cacheKeyGenerator;
        this.cacheInfoFileParser = cacheInfoFileParser;
        this.cacheInfoFileWriter = cacheInfoFileWriter;
        this.fileSizeThreshold = fileSizeThreshold;
        this.cacheIsEnabled = cacheIsEnabled;

        this.dataCache = dataCacheFactory.createCache();
    }

    private boolean isCached(final String key) {
        return dataCache.exists(key);
    }

    public DataCacheServiceInfo cacheFiles(final File directory) throws IOException {
        DataCacheServiceInfo result = new DataCacheServiceInfo();
        if (! cacheIsEnabled) {
            return result;
        }

        LOGGER.debug(String.format("Caching files in directory %s", directory.getPath()));

        Files.walk(directory.toPath())
                .filter(Files::isRegularFile)
                .filter(path -> ! FILE_CACHE_INFO_FILE.equals(path.getFileName().toString()))
                .forEach(path -> {
                    try {
                        long fileSize = upload(path.toFile());
                        result.cachedFileSize.addAndGet(fileSize);
                        result.cachedFileCount.addAndGet(fileSize >= fileSizeThreshold ? 1 : 0);
                    } catch (IOException e) {
                        LOGGER.error("Could not cache file " + path.toString(), e);
                    }
                });

        LOGGER.info(String.format("Cached %d bytes in %d files", result.cachedFileSize.longValue(), result.cachedFileCount.intValue()));
        return result;
    }

    private long upload(File file) throws IOException {
        // Skip small files
        if (file.length() < fileSizeThreshold) {
            LOGGER.debug(String.format("[cache] %s - SKIPPED", file.getName()));
            return 0;
        }

        final String key = cacheKeyGenerator.generate(file);

        LOGGER.debug(String.format("[cache] %s - %s", file.getName(), key));
        dataCache.put(key, file);

        return file.length();
    }

    // Step through the entries in the json and download them from the key-value server
    public DataCacheServiceInfo getCachedFiles(File directory) throws IOException, ExtenderException {
        DataCacheServiceInfo result = new DataCacheServiceInfo();
        if (! cacheIsEnabled) {
            return result;
        }

        File cacheInfoFile = new File(directory, FILE_CACHE_INFO_FILE);

        // Support older editors that don't supply a cache info file
        if (!cacheInfoFile.exists()) {
            LOGGER.info("No cache info file found, skipping cache");
            return result;
        }
        LOGGER.info("Downloading cached files");

        final CacheInfoWrapper wrapper = cacheInfoFileParser.parse(cacheInfoFile);
        if (wrapper == null) {
            LOGGER.info("Couldn't parse the cache info file. Ignoring");
            return result;
        }

        List<CacheEntry> cacheEntries = wrapper.getEntries();
        // we can face null pointer in cacheEntries if client, who requestd a build, don't have access to /query endpoint
        if (cacheEntries == null) {
            return result;
        }

        for (CacheEntry entry : cacheEntries) {
            // Check if entry is cached
            if (! entry.isCached()) {
                continue;
            }

            verifyCacheEntry(entry);

            File destination = new File(directory, entry.getPath());
            makeParentDirectories(destination);
            result.cachedFileSize.addAndGet(downloadFile(entry, destination));

            if (! destination.exists()) {
                throw new ExtenderException(String.format("Failed downloading '%s' (%s) from cache",
                        entry.getPath(), entry.getKey()));
            }

            verifyCacheKey(destination, entry.getKey());
            result.cachedFileCount.addAndGet(1);
        }

        LOGGER.info(String.format("Downloaded %d bytes in %d cached files", result.cachedFileSize.longValue(), result.cachedFileCount.intValue()));

        return result;
    }

    private void verifyCacheEntry(CacheEntry entry) throws ExtenderException {
        if (StringUtils.isBlank(entry.getPath())) {
            throw new ExtenderException("Corrupt cache info JSON file, missing 'path' field");
        }
        if (StringUtils.isBlank(entry.getKey())) {
            throw new ExtenderException("Corrupt cache info JSON file, missing 'key' field");
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

    private long downloadFile(CacheEntry entry, File destination) throws ExtenderException, IOException {
        try (InputStream inputStream = dataCache.get(entry.getKey())) {
            if (inputStream == null) {
                throw new ExtenderException(String.format("Cache object %s (%s) was not found", entry.getPath(), entry.getKey()));
            }

            return Files.copy(
                    inputStream,
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Boolean isVersionOk(int version, String hashType) {
        return  version == FILE_CACHE_INFO_VERSION &&
                hashType.equals(FILE_CACHE_INFO_HASH_TYPE);
    }

    /**
     * Reads a JSON file which contains an entry for each file, with corresponding checksum (sha256)
     * It modifies the json with the cache status for that file, and then writes the result to another json output file
     *   "files": [{"path": "a/b", "key": "<sha256>"}] ->
     *   "files": [{"path": "a/b", "key": "<sha256>", "cached": true/false}]
     * It also verifies the version number and hash type
     */
    public void queryCache(InputStream input, OutputStream output) throws ExtenderException {
        final CacheInfoWrapper wrapper;
        try {
            wrapper = cacheInfoFileParser.parse(input);
        } catch (IOException e) {
            throw new ExtenderException(e, "Failed to parse cache info JSON: " + keepFirstLineInMessage(e.getMessage()));
        }

        Boolean versionOK = isVersionOk(wrapper.getVersion(), wrapper.getHashType());

        final List<CacheEntry> cacheEntries = wrapper.getEntries();
        for (CacheEntry entry : cacheEntries) {
            if (versionOK) {
                verifyCacheEntry(entry);
                entry.setCached(isCached(entry.getKey()));
            } else {
                entry.setCached(false);
            }

            if (entry.isCached()) {
                touchCacheEntry(entry);
            }
        }

        try {
            cacheInfoFileWriter.write(FILE_CACHE_INFO_VERSION, FILE_CACHE_INFO_HASH_TYPE, cacheEntries, output);
        } catch (IOException e) {
            throw new ExtenderException(e, "Failed to write cache info JSON: " + keepFirstLineInMessage(e.getMessage()));
        }
    }

    private void touchCacheEntry(CacheEntry entry) {
        dataCache.touch(entry.getKey());
    }

    // Jackson has a lot of verbose information in its exception message
    private String keepFirstLineInMessage(final String message) {
        return message.split("(\r\n|\r|\n)", -1)[0];
    }
}
