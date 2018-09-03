package com.defold.extender.services;

import com.defold.extender.ExtenderException;
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
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

public class DataStoreServiceTest {

    private static final String url = "http://localhost/fake";
    private static final int maxFileSize = 8 * 1024;


    @Test
    public void testDownloadNoCacheInfoFile() throws IOException, ExtenderException {
        DataStoreService dataStoreService = new DataStoreService(url, maxFileSize);

        File tmpDir = Files.createTempDirectory("extenderTest").toFile();
        tmpDir.deleteOnExit();

        long numFilesDownloaded = dataStoreService.downloadFilesFromCache(tmpDir);
        assertEquals(0, numFilesDownloaded);
    }

    private long createDummyFile(String key, File file) throws IOException {
        FileUtils.writeStringToFile(file, file.getName(), Charset.forName("UTF-8"));
        return file.length();
    }

    @Test
    public void testDownload() throws IOException, ExtenderException {
        DataStoreService dataStoreService = new DataStoreService(url, maxFileSize);
        DataStoreService spy = spy(dataStoreService);

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
            File file = new File(tmpDownloadDir, DataStoreService.FILE_CACHE_INFO_FILE);
            FileUtils.writeStringToFile(file, json, Charset.forName("UTF-8"));

        } catch (IOException e) {
            e.printStackTrace();
        }

        long numFilesDownloaded = spy.downloadFilesFromCache(tmpDownloadDir);
        assertEquals(3, numFilesDownloaded);

        List<String> collect = Files.walk(tmpDownloadDir.toPath()).filter(Files::isRegularFile).map(path -> path.toFile().getName()).collect(Collectors.toList());

        assertEquals(4, collect.size());
        assertTrue(collect.contains("file1"));
        assertTrue(collect.contains("file2"));
        assertTrue(collect.contains("file3"));
        assertTrue(collect.contains(DataStoreService.FILE_CACHE_INFO_FILE));
    }
}
