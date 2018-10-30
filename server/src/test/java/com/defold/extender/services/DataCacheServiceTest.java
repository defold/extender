package com.defold.extender.services;

import com.defold.extender.ExtenderException;
import com.defold.extender.TestUtils;
import com.defold.extender.cache.CacheEntry;
import com.defold.extender.cache.DataCache;
import com.defold.extender.cache.DataCacheFactory;
import com.defold.extender.cache.file.CacheFileParser;
import com.defold.extender.cache.file.CacheFileWriter;
import com.defold.extender.cache.CacheKeyGenerator;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class DataCacheServiceTest {

    private static final int fileThreshold = 5;

    @Test
    @Ignore
    public void testUploadingToS3Cache() throws Exception {
        final DataCacheService dataCacheService = new DataCacheService(new CacheKeyGenerator(), new CacheFileParser(),
                new CacheFileWriter(), new DataCacheFactory("S3", "defold-extender-cache-dev"), fileThreshold);

        File uploadDirectory = new File(ClassLoader.getSystemResource("upload").toURI());
        dataCacheService.cacheFiles(uploadDirectory);
    }

    @Test
    @Ignore
    public void testDownloadingFromS3Cache() throws Exception {
        final DataCacheService dataCacheService = new DataCacheService(new CacheKeyGenerator(), new CacheFileParser(),
                new CacheFileWriter(), new DataCacheFactory("S3", "defold-extender-cache-dev"), fileThreshold);

        final File uploadDirectory = Files.createTempDirectory("extenderTest").toFile();
        final File sourceInfoFile = new File(ClassLoader.getSystemResource("upload/"+DataCacheService.FILE_CACHE_INFO_FILE).toURI());
        final File targetInfoFile = new File(uploadDirectory.getPath() + "/" + DataCacheService.FILE_CACHE_INFO_FILE);

        // Copy cache info file to upload directory
        Files.copy(sourceInfoFile.toPath(), targetInfoFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

        dataCacheService.getCachedFiles(uploadDirectory);

        System.out.println(">>> Upload directory: " + uploadDirectory.getAbsolutePath());
    }

    @Test
    public void testCachingFiles() throws Exception {
        final DataCache dataCache = mock(DataCache.class);

        final DataCacheFactory dataCacheFactory = mock(DataCacheFactory.class);
        when(dataCacheFactory.createCache()).thenReturn(dataCache);

        final DataCacheService dataCacheService = new DataCacheService(new CacheKeyGenerator(), new CacheFileParser(),
                mock(CacheFileWriter.class), dataCacheFactory, fileThreshold);

        File uploadDirectory = new File(ClassLoader.getSystemResource("upload").toURI());
        dataCacheService.cacheFiles(uploadDirectory);

        // Verify that 2 files were cached
        verify(dataCache, times(2)).put(anyString(), any(File.class));

        // Verify that correct cache keys and file names were used
        for (CacheEntry entry : TestUtils.CACHE_ENTRIES) {
            ArgumentCaptor<File> argument = ArgumentCaptor.forClass(File.class);
            verify(dataCache).put(eq(entry.getKey()), argument.capture());
            assertTrue(argument.getValue().getPath().endsWith(entry.getPath()));
        }
    }

    @Test
    public void testDownloadNoCacheInfoFile() throws IOException, ExtenderException {
        final DataCacheFactory dataCacheFactory = mock(DataCacheFactory.class);
        final CacheFileParser cacheFileParser = mock(CacheFileParser.class);

        final DataCacheService dataCacheService = new DataCacheService(new CacheKeyGenerator(), cacheFileParser,
                new CacheFileWriter(), dataCacheFactory, fileThreshold);

        final File tmpDir = Files.createTempDirectory("extenderTest").toFile();
        tmpDir.deleteOnExit();

        // If no cache file is present in directory root, no files should be fetched from cache
        long numFilesDownloaded = dataCacheService.getCachedFiles(tmpDir);
        assertEquals(0, numFilesDownloaded);

        // Cache file parser should not be invoked
        verify(cacheFileParser, never()).parse(any(File.class));
    }

    @Test
    public void testDownload() throws IOException, ExtenderException {
        final DataCache dataCache = mock(DataCache.class);

        for (CacheEntry entry : TestUtils.MOCK_CACHE_ENTRIES) {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(entry.getPath().getBytes());
            when(dataCache.get(entry.getKey())).thenReturn(inputStream);
        }

        final DataCacheFactory dataCacheFactory = mock(DataCacheFactory.class);
        when(dataCacheFactory.createCache()).thenReturn(dataCache);

        final DataCacheService dataCacheService = new DataCacheService(new CacheKeyGenerator(), new CacheFileParser(),
                mock(CacheFileWriter.class), dataCacheFactory, fileThreshold);
        final DataCacheService spy = spy(dataCacheService);

        // Create an empty test directory
        final File tmpDownloadDir = Files.createTempDirectory("extenderTest").toFile();
        tmpDownloadDir.deleteOnExit();

        // Write with an info file to the test directory root
        final CacheFileWriter cacheFileWriter = new CacheFileWriter();
        File fileCacheInfoFile = new File(tmpDownloadDir, DataCacheService.FILE_CACHE_INFO_FILE);
        cacheFileWriter.write(Arrays.asList(TestUtils.MOCK_CACHE_ENTRIES), new FileOutputStream(fileCacheInfoFile));

        int numFilesDownloaded = spy.getCachedFiles(tmpDownloadDir);

        assertEquals(3, numFilesDownloaded);

        List<String> collect = Files
                .walk(tmpDownloadDir.toPath())
                .filter(Files::isRegularFile)
                .map(path -> path.toFile().getName())
                .collect(Collectors.toList());

        assertEquals(4, collect.size());
        assertTrue(collect.contains("file1"));
        assertTrue(collect.contains("file2"));
        assertTrue(collect.contains("file3"));
        assertTrue(collect.contains(DataCacheService.FILE_CACHE_INFO_FILE));
    }

    @Test
    public void testQuery() throws IOException, ExtenderException, URISyntaxException {
        CacheFileParser parser = new CacheFileParser();

        DataCache dataCacheMock = mock(DataCache.class);
        when(dataCacheMock.exists("LYwvbZeMohcStfbeNsnTH6jpak-l2P-LAYjfuefBcbs")).thenReturn(true);

        DataCacheFactory dataCacheFactory = mock(DataCacheFactory.class);
        when(dataCacheFactory.createCache()).thenReturn(dataCacheMock);

        DataCacheService dataCacheService = new DataCacheService(new CacheKeyGenerator(), parser,
                new CacheFileWriter(), dataCacheFactory, fileThreshold);

        FileInputStream input = new FileInputStream(new File(ClassLoader.getSystemResource("upload/"+DataCacheService.FILE_CACHE_INFO_FILE).toURI()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        dataCacheService.queryCache(input, output);

        InputStream jsonStream = new ByteArrayInputStream(output.toByteArray());
        List<CacheEntry> entries = parser.parse(jsonStream);

        assertEquals(2, entries.size());

        CacheEntry entry1 = entries.get(0);
        assertEquals("dir/test1.txt", entry1.getPath());
        assertEquals("LYwvbZeMohcStfbeNsnTH6jpak-l2P-LAYjfuefBcbs", entry1.getKey());
        assertTrue(entry1.isCached());

        CacheEntry entry2 = entries.get(1);
        assertEquals("dir2/test2.txt", entry2.getPath());
        assertEquals("fzthrrNKjqFcZ1_92qavam-90DHtl4bcsrNbNRoTKzE", entry2.getKey());
        assertFalse(entry2.isCached());
    }
}

