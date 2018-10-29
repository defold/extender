package com.defold.extender.services;

import com.defold.extender.ExtenderException;
import com.defold.extender.cache.CacheEntry;
import com.defold.extender.cache.file.CacheFileParser;
import com.defold.extender.cache.file.CacheFileWriter;
import com.defold.extender.cache.CacheKeyGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FileUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

public class DataCacheServiceTest {

    private static final int maxFileSize = 8 * 1024;

    @Test
    public void testDownloadNoCacheInfoFile() throws IOException, ExtenderException {
        DataCacheService dataCacheService = new DataCacheService(new CacheKeyGenerator(), new CacheFileParser(), new CacheFileWriter(),
                "S3", "defold-extender-s3-test", maxFileSize);

        File tmpDir = Files.createTempDirectory("extenderTest").toFile();
        tmpDir.deleteOnExit();

        long numFilesDownloaded = dataCacheService.getCachedFiles(tmpDir);
        assertEquals(0, numFilesDownloaded);
    }

    private long createDummyFile(String key, File file) throws IOException {
        FileUtils.writeStringToFile(file, file.getName(), Charset.forName("UTF-8"));
        return file.length();
    }

    @Test
    public void testDownload() throws IOException, ExtenderException {
        DataCacheService dataCacheService = new DataCacheService(new CacheKeyGenerator(), new CacheFileParser(), new CacheFileWriter(),
                "S3", "defold-extender-s3-test", maxFileSize);
        DataCacheService spy = spy(dataCacheService);

        File tmpDownloadDir = Files.createTempDirectory("extenderTest").toFile();

        Mockito.doAnswer(invocation ->
                createDummyFile(invocation.getArgumentAt(0, String.class), invocation.getArgumentAt(1, File.class))
        ).when(spy).downloadFile(Mockito.anyString(), Mockito.any(File.class));

        try {
            JSONObject root = new JSONObject();
            JSONObject file1 = new JSONObject();
            file1.put("path", "path/to/file1");
            file1.put("key", "wUfvz8LX6mZqnk9Rh7EVyQkD8PyJalbfmm712PP8nzE="); // the SHA-256 of "file1"
            JSONObject file2 = new JSONObject();
            file2.put("path", "another/path/to/file2");
            file2.put("key", "M3eHDf6qp633mjdNJwKj/bE+Xl6g3YqpWoAq05BEqS8=");
            JSONObject file3 = new JSONObject();
            file3.put("path", "last/path/for/file3");
            file3.put("key", "bz/vbcUceZanSZK3DQw18yjtkJpeB2Rs8LqzODyVuwI=");

            JSONArray files = new JSONArray();
            files.add(file1);
            files.add(file2);
            files.add(file3);
            root.put("files", files);

            String json = root.toJSONString();
            File file = new File(tmpDownloadDir, DataCacheService.FILE_CACHE_INFO_FILE);
            FileUtils.writeStringToFile(file, json, Charset.forName("UTF-8"));

        } catch (IOException e) {
            e.printStackTrace();
        }

        long numFilesDownloaded = spy.getCachedFiles(tmpDownloadDir);
        assertEquals(3, numFilesDownloaded);

        List<String> collect = Files.walk(tmpDownloadDir.toPath()).filter(Files::isRegularFile).map(path -> path.toFile().getName()).collect(Collectors.toList());

        assertEquals(4, collect.size());
        assertTrue(collect.contains("file1"));
        assertTrue(collect.contains("file2"));
        assertTrue(collect.contains("file3"));
        assertTrue(collect.contains(DataCacheService.FILE_CACHE_INFO_FILE));

        FileUtils.deleteDirectory(tmpDownloadDir);
    }

    @Test
    public void testQuery() throws IOException, ExtenderException {
        CacheFileParser parser = new CacheFileParser();
        DataCacheService dataCacheService = new DataCacheService(new CacheKeyGenerator(), parser, new CacheFileWriter(),
                "S3", "defold-extender-s3-test", maxFileSize);
        DataCacheService dataCacheServiceSpy = Mockito.spy(dataCacheService);
        Mockito.when(dataCacheServiceSpy.isCached("LYwvbZeMohcStfbeNsnTH6jpak+l2P+LAYjfuefBcbs=")).thenReturn(true);

        FileInputStream input = new FileInputStream(new File("test-data/query1/" + DataCacheService.FILE_CACHE_INFO_FILE));
        ByteArrayOutputStream output = new ByteArrayOutputStream(256 * 1024);

        dataCacheServiceSpy.queryCache(input, output);

        InputStream jsonStream = new ByteArrayInputStream(output.toByteArray());
        List<CacheEntry> entries = parser.parse(jsonStream);

        assertEquals(2, entries.size());

        CacheEntry entry1 = entries.get(0);
        assertEquals("dir/test1.txt", entry1.getPath());
        assertEquals("LYwvbZeMohcStfbeNsnTH6jpak+l2P+LAYjfuefBcbs=", entry1.getKey());
        assertTrue(entry1.isCached());

        CacheEntry entry2 = entries.get(1);
        assertEquals("dir/test2.txt", entry2.getPath());
        assertEquals("sP8bj1xw4xwbBaetvug4PDzHuK8ukoKiJdc9EVXgc28=", entry2.getKey());
        assertFalse(entry1.isCached());
    }
}

