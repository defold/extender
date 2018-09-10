package com.defold.extender.services;

import com.defold.extender.ExtenderException;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClients;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import sun.misc.BASE64Encoder;

import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;

@Service
public class DataStoreService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataStoreService.class);
    public static final String FILE_CACHE_INFO_FILE = "ne-cache-info.json";

    private static String url;
    private static int fileSizeThreshold;

    private long totalUploadSize = 0;

    @Autowired
    DataStoreService(@Value("${extender.data-store.url}") String url,
                     @Value("${extender.data-store.file-size-threshold}") int fileSizeThreshold) {
        this.url = url;
        this.fileSizeThreshold = fileSizeThreshold;
    }

    public static String createKey(File file) throws ExtenderException {
        int count;

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new ExtenderException(e, "Didn't find SHA-256 implementation: " + e.getMessage());
        }

        try {
            byte[] buffer = new byte[8192];
            BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file));
            while ((count = bis.read(buffer)) > 0) {
                digest.update(buffer, 0, count);
            }
            bis.close();
        } catch (IOException e) {
            throw new ExtenderException(e, "Failed to read file: " + e.getMessage());
        }

        byte[] hash = digest.digest();
        return new BASE64Encoder().encode(hash);
    }

    private long upload(File file) throws ExtenderException {
        // Check the size of the file
        if (file.length() < fileSizeThreshold) {
            LOGGER.debug(String.format("Skipping upload of %s due to small file size", file.getName()));
            return 0;
        }

        String key = DataStoreService.createKey(file);

        LOGGER.debug(String.format("Caching %s as %s in data store [NOT YET!]", file.getName(), key));
        return file.length();
    }

    private void incrementUploadSize(long uploadSize) {
        totalUploadSize += uploadSize;
    }

    // Step through all files in the directory and try to cache them onto the key-value server
    public long uploadFilesToCache(File directory) throws IOException {
        totalUploadSize = 0;
        Files.walk(directory.toPath())
                .filter(Files::isRegularFile)
                .forEach(path -> {
                    try {
                        long size = upload(path.toFile());
                        incrementUploadSize(size);
                    } catch (ExtenderException e) {
                        LOGGER.error("Could not upload data: " + e.getMessage());
                    }
                });
        return totalUploadSize;
    }

    private void verifyKey(File file, String originalKey) throws ExtenderException {
        String newKey = DataStoreService.createKey(file);
        if (!newKey.equals(originalKey)) {
            throw new ExtenderException(String.format("The checksum of the file '%s' differs from the one in the cache: %s != %s", file.getAbsolutePath(), originalKey, newKey));
        }
    }

    public void downloadFile(String key, File dst) throws IOException, ExtenderException {
        HttpGet request = new HttpGet("http://localhost:8000/get/" + key);
        HttpClient httpclient = HttpClients.createDefault();
        HttpResponse response = httpclient.execute(request);

        if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
            throw new ExtenderException("Failed to build source.");
        }
        InputStream in = new BufferedInputStream(response.getEntity().getContent());
        Files.copy(in, dst.toPath());
    }

    // Step through the entries in the json and download them from the key-value server
    public long downloadFilesFromCache(File directory) throws IOException, ExtenderException{
        File cacheInfoFile = new File(directory, FILE_CACHE_INFO_FILE);
        if (!cacheInfoFile.exists()) {
            return 0;
        }
        LOGGER.info("Downloading cached files");

        JSONParser parser = new JSONParser();
        JSONObject jsonObject = null;
        try {
            Object obj = parser.parse(new FileReader(cacheInfoFile));
            jsonObject = (JSONObject) obj;
        } catch (ParseException e) {
            e.printStackTrace();
        }

        long count = 0;

        JSONArray msg = (JSONArray) jsonObject.get("files");
        Iterator<JSONObject> iterator = msg.iterator();
        while (iterator.hasNext()) {
            JSONObject o = iterator.next();
            String path = (String)o.get("path");
            String key = (String)o.get("key");
            if (path == null) {
                throw new ExtenderException("Corrupt json, missing 'path' field");
            }
            if (key == null) {
                throw new ExtenderException("Corrupt json, missing 'key' field");
            }

            File dst = new File(directory, path);
            if (!dst.getParentFile().exists())
                dst.getParentFile().mkdirs();

            downloadFile(key, dst);

            if (!dst.exists()) {
                throw new ExtenderException(String.format("Failed downloading '%s' from cache", path));
            }

            verifyKey(dst, key);

            ++count;
        }

        return count;
    }
}