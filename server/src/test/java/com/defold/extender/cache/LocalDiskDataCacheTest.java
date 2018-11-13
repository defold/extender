package com.defold.extender.cache;

import com.defold.extender.TestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class LocalDiskDataCacheTest {

    private Path baseDirectory;
    private DataCache cache;

    @Before
    public void setUp() throws Exception {
        baseDirectory = Files.createTempDirectory("diskCacheTest");
        baseDirectory.toFile().deleteOnExit();
        cache = new LocalDiskDataCache(baseDirectory);
    }

    @Test
    public void shouldPutFilesInCache() throws IOException, URISyntaxException {
        for (int i = 0; i < TestUtils.CACHE_ENTRIES.length; i++) {
            CacheEntry entry = TestUtils.CACHE_ENTRIES[i];
            String key = entry.getKey();
            File source = getSourceFile(entry.getPath());
            cache.put(key, source);

            File cachedFile = new File(String.format("%s/%s/%s", baseDirectory, key.substring(0,2), key));
            assertTrue(Files.exists(cachedFile.toPath()));
            assertTrue(FileUtils.contentEquals(source, cachedFile));
            assertEquals((i+1), countFilesInCache());
        }

        assertEquals(TestUtils.CACHE_ENTRIES.length, countFilesInCache());
    }

    @Test
    public void shouldGetCachedObject() throws Exception {
        putFilesInCache();

        for (CacheEntry entry : TestUtils.CACHE_ENTRIES) {
            InputStream inputStream = cache.get(entry.getKey());
            File source = getSourceFile(entry.getPath());
            assertTrue(IOUtils.contentEquals(new FileInputStream(source), inputStream));
        }
    }

    @Test
    public void shouldFailToGetMissingCachedObject() throws Exception {
        putFilesInCache();
        assertNull(cache.get("iAmNotHere"));
    }

    @Test
    public void exists() throws Exception {
        putFilesInCache();

        for (CacheEntry entry : TestUtils.CACHE_ENTRIES) {
            assertTrue(cache.exists(entry.getKey()));
        }
    }

    @Test
    public void shouldFailToCheckIfExists() throws Exception {
        putFilesInCache();
        assertFalse(cache.exists("iAmNotHere"));
    }

    private long countFilesInCache() throws IOException {
        return Files
                .walk(baseDirectory)
                .filter(Files::isRegularFile)
                .count();
    }

    private void putFilesInCache() throws IOException, URISyntaxException {
        for (CacheEntry entry : TestUtils.CACHE_ENTRIES) {
            String key = entry.getKey();
            File source = new File(ClassLoader.getSystemResource("upload/" + entry.getPath()).toURI());
            cache.put(key, source);
        }
    }

    private File getSourceFile(final String path) throws URISyntaxException {
        return new File(ClassLoader.getSystemResource("upload/"+path).toURI());
    }
}