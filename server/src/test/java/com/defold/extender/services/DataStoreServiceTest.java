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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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

        FileUtils.deleteDirectory(tmpDownloadDir);
    }

    private class CacheFileItem {
        String path;
        String key;
        boolean cached;
        public CacheFileItem(String path, String key, boolean cached) {
            this.path = path;
            this.key = key;
            this.cached = cached;
        }
    }
    private void verifyCacheResult(List<CacheFileItem> expectedFiles, JSONObject jsonObject) {
        JSONArray msg = (JSONArray) jsonObject.get("files");
        Iterator<JSONObject> iterator = msg.iterator();
        while (iterator.hasNext()) {
            JSONObject o = iterator.next();
            String path = (String)o.get("path");
            String key = (String)o.get("key");
            boolean cached = (boolean)o.get("cached");
            assertNotNull(path);
            assertNotNull(key);
            assertNotNull(cached);

            boolean found = false;
            for (CacheFileItem item : expectedFiles) {
                if (item.path.equals(path)) {
                    found = true;
                    assertEquals(item.key, key);
                    assertEquals(item.cached, cached);
                    break;
                }
            }

            assertTrue(found);
        }
    }

    @Test
    public void testQuery() throws IOException, ExtenderException {
        DataStoreService dataStoreService = new DataStoreService(url, maxFileSize);
        DataStoreService dataStoreServiceSpy = Mockito.spy(dataStoreService);
        Mockito.when(dataStoreServiceSpy.isCached("2b414ebf2f1734b3705990f21d1cf348495591c6b530e5cb3053738a461bdce7")).thenReturn(true);

        FileInputStream input = new FileInputStream(new File("test-data/query1/" + DataStoreService.FILE_CACHE_INFO_FILE));
        ByteArrayOutputStream output = new ByteArrayOutputStream(256 * 1024);

        dataStoreServiceSpy.queryCache(input, output);

        InputStream jsonStream = new ByteArrayInputStream(output.toByteArray());
        JSONObject json = DataStoreService.readJson(jsonStream);

        System.out.println("RESULT: " + json.toJSONString());

        List<CacheFileItem> expectedFiles = new ArrayList<>();
        expectedFiles.add(new CacheFileItem("a.txt", "9f51adb73a7fea871dcaef6838ce776853212af6ce42d0cc9ce5221d69f8af0f", false));
        expectedFiles.add(new CacheFileItem("b.txt", "2b414ebf2f1734b3705990f21d1cf348495591c6b530e5cb3053738a461bdce7", true));

        verifyCacheResult(expectedFiles, json);
    }
}
