package com.defold.extender.client;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.ByteArrayBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.Iterator;
import java.util.Base64;

public class ExtenderClient {
    private final String extenderBaseUrl;
    private ExtenderClientCache cache;

    public static final String appManifestFilename = "app.manifest";
    public static final String extensionFilename = "ext.manifest";
    public static final Pattern extensionPattern = Pattern.compile(extensionFilename);

    /**
     * Creates a local build cache
     *
     * @param extenderBaseUrl The build server url (e.g. https://build.defold.com)
     * @param cacheDir        A directory where the cache files are located (it must exist beforehand)
     */
    public ExtenderClient(String extenderBaseUrl, File cacheDir) throws IOException {
        this.extenderBaseUrl = extenderBaseUrl;
        this.cache = new ExtenderClientCache(cacheDir);
    }

    private static String createKey(ExtenderResource resource) throws ExtenderClientException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new ExtenderClientException("Didn't find SHA-256 implementation: " + e.getMessage(), e);
        }

        try {
            byte[] data = resource.getContent();
            digest.update(data);
        } catch (IOException e) {
            throw new ExtenderClientException("Failed to read file: " + e.getMessage(), e);
        }

        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
    }

    String queryCache(List<ExtenderResource> sourceResources) throws ExtenderClientException {
        JSONArray files = new JSONArray();
        for (ExtenderResource resource : sourceResources) {
            JSONObject item = new JSONObject();
            item.put("path", resource.getPath());
            item.put("key", createKey(resource));
            files.add(item);
        }
        JSONObject root = new JSONObject();
        root.put("files", files);

        String data = root.toJSONString().replace("\\/", "/");

        String url = String.format("%s/query", extenderBaseUrl);
        HttpPost request = new HttpPost(url);
        request.setEntity(new ByteArrayEntity(data.getBytes()));
        request.setHeader("Accept", "application/json");
        request.setHeader("Content-type", "application/json");

        try {
            HttpClient client = new DefaultHttpClient();
            HttpResponse response = client.execute(request);

            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                return EntityUtils.toString(response.getEntity()).replace("\\/", "/");
            } else {
                return null; // Caching is not supported
            }
        } catch (IOException e) {
            throw new ExtenderClientException("Failed to communicate with Extender service.", e);
        }
    }

    // Gets a set of files that are currently cached
    private static Set<String> getCachedFiles(String json) throws ExtenderClientException {
        Set<String> cachedFiles = new HashSet<>();

        JSONObject root = null;
        try {
            JSONParser parser = new JSONParser();
            root = (JSONObject)parser.parse(json);
        } catch (ParseException e) {
            e.printStackTrace();
            throw new ExtenderClientException("Failed to parse json: " + e.getMessage(), e);
        }

        JSONArray files = (JSONArray) root.get("files");
        Iterator<JSONObject> iterator = files.iterator();
        while (iterator.hasNext()) {
            JSONObject o = iterator.next();
            boolean cached = (boolean)o.get("cached");
            if (cached) {
                cachedFiles.add((String)o.get("path"));
            }
        }
        return cachedFiles;
    }

    /**
     * Builds a new engine given a platform and an sdk version plus source files.
     * The result is a .zip file
     *
     * @param platform        E.g. "arm64-ios", "armv7-android", "x86_64-osx"
     * @param sdkVersion      Sha1 of defold version
     * @param sourceResources List of resources that should be build on server (.cpp, .a, etc)
     * @param destination     The output where the returned zip file is copied
     * @param log             A log file
     * @throws ExtenderClientException
     */
    public void build(String platform, String sdkVersion, List<ExtenderResource> sourceResources, File destination, File log) throws ExtenderClientException {
        String cacheKey = cache.calcKey(platform, sdkVersion, sourceResources);
        boolean isCached = cache.isCached(platform, cacheKey);
        if (isCached) {
            cache.get(platform, cacheKey, destination);
            return;
        }

        MultipartEntity entity = new MultipartEntity();

        // Now, let's ask the server what files it already has
        String cacheInfoName = "ne-cache-info.json";
        String cacheInfoJson = queryCache(sourceResources);
        Set<String> cachedFiles = new HashSet<>();
        if (cacheInfoJson != null) {
            cachedFiles = getCachedFiles(cacheInfoJson);

            // add the updated info to the file
            entity.addPart(cacheInfoName, new ByteArrayBody(cacheInfoJson.getBytes(), cacheInfoName)); // Same as specified in DataStoreService.java
        }

        for (ExtenderResource s : sourceResources) {
            // If the file was already cached, don't upload it
            if (cachedFiles.contains(s.getPath())) {
                continue;
            }

            ByteArrayBody bin;
            try {
                bin = new ByteArrayBody(s.getContent(), s.getPath());
            } catch (IOException e) {
                throw new ExtenderClientException("Error while getting content for " + s.getPath() + ": " + e.getMessage());
            }
            entity.addPart(s.getPath(), bin);
        }

        String url = String.format("%s/build/%s/%s", extenderBaseUrl, platform, sdkVersion);
        HttpPost request = new HttpPost(url);

        request.setEntity(entity);

        try {
            HttpClient client = new DefaultHttpClient();
            HttpResponse response = client.execute(request);

            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                response.getEntity().writeTo(new FileOutputStream(destination));
            } else {
                response.getEntity().writeTo(new FileOutputStream(log));
                throw new ExtenderClientException("Failed to build source.");
            }
        } catch (IOException e) {
            throw new ExtenderClientException("Failed to communicate with Extender service.", e);
        }

        // Store the new build
        cache.put(platform, cacheKey, destination);
    }

    private static final String getRelativePath(File base, File path) {
        return base.toURI().relativize(path.toURI()).getPath();
    }

    public boolean health() throws IOException {

        HttpClient client = new DefaultHttpClient();
        HttpResponse response = client.execute(new HttpGet(extenderBaseUrl));

        if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
            return true;
        }
        return false;
    }
}
