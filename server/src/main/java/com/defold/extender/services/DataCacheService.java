package com.defold.extender.services;

import com.defold.extender.ExtenderException;
import com.defold.extender.cache.CacheEntry;
import com.defold.extender.cache.DataCacheFactory;
import com.defold.extender.cache.info.CacheInfoFileParser;
import com.defold.extender.cache.info.CacheInfoFileWriter;
import com.defold.extender.cache.CacheKeyGenerator;
import com.defold.extender.cache.DataCache;
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

    private final CacheKeyGenerator cacheKeyGenerator;
    private final CacheInfoFileParser cacheInfoFileParser;
    private final CacheInfoFileWriter cacheInfoFileWriter;
    private final DataCache dataCache;

    private int fileSizeThreshold;

    @Autowired
    DataCacheService(final CacheKeyGenerator cacheKeyGenerator,
                     final CacheInfoFileParser cacheInfoFileParser,
                     final CacheInfoFileWriter cacheInfoFileWriter,
                     final DataCacheFactory dataCacheFactory,
                     @Value("${extender.cache.file-size-threshold}") int fileSizeThreshold) {

        this.cacheKeyGenerator = cacheKeyGenerator;
        this.cacheInfoFileParser = cacheInfoFileParser;
        this.cacheInfoFileWriter = cacheInfoFileWriter;
        this.fileSizeThreshold = fileSizeThreshold;
        this.dataCache = dataCacheFactory.createCache();
    }

    private boolean isCached(final String key) {
        return dataCache.exists(key);
    }

    public long cacheFiles(final File directory) throws IOException {
        LOGGER.debug(String.format("Caching files in directory %s", directory.getPath()));

        final AtomicLong totalbytesCached = new AtomicLong();
        Files.walk(directory.toPath())
                .filter(Files::isRegularFile)
                .filter(path -> ! FILE_CACHE_INFO_FILE.equals(path.getFileName().toString()))
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
    public int getCachedFiles(File directory) throws IOException, ExtenderException {
        File cacheInfoFile = new File(directory, FILE_CACHE_INFO_FILE);

        // Support older editors that don't supply a cache info file
        if (!cacheInfoFile.exists()) {
            return 0;
        }
        LOGGER.info("Downloading cached files");

        List<CacheEntry> cacheEntries = cacheInfoFileParser.parse(cacheInfoFile);
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

    private void downloadFile(String key, File destination) throws ExtenderException, IOException {
        try (InputStream inputStream = dataCache.get(key)) {
            if (inputStream == null) {
                throw new ExtenderException(String.format("Cache object with key '%s' was not found", key));
            }

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
    public void queryCache(InputStream input, OutputStream output) throws ExtenderException {
        final List<CacheEntry> cacheEntries;

        try {
            cacheEntries = cacheInfoFileParser.parse(input);
        } catch (IOException e) {
            throw new ExtenderException(e, "Failed to parse cache info JSON: " + keepFirstLineInMessage(e.getMessage()));
        }

        for (CacheEntry entry : cacheEntries) {
            verifyCacheEntry(entry);
            entry.setCached(isCached(entry.getKey()));
        }

        try {
            cacheInfoFileWriter.write(cacheEntries, output);
        } catch (IOException e) {
            throw new ExtenderException(e, "Failed to write cache info JSON: " + keepFirstLineInMessage(e.getMessage()));
        }
    }

    // Jackson has a lot of verbose information in its exception message
    private String keepFirstLineInMessage(final String message) {
        return message.split("(\r\n|\r|\n)", -1)[0];
    }
}
