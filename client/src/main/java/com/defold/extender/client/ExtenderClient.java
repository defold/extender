package com.defold.extender.client;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.impl.client.AbstractHttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.message.AbstractHttpMessage;
import org.apache.http.message.BasicHeader;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.ByteArrayBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.apache.http.client.CookieStore;
import org.apache.http.impl.client.BasicCookieStore;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.Iterator;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ExtenderClient {
    private static Logger logger = Logger.getLogger(ExtenderClient.class.getName());

    private final String extenderBaseUrl;
    private ExtenderClientCache cache;
    private CookieStore httpCookies;
    private long buildSleepTimeout;
    private long buildResultWaitTimeout;
    private List<BasicHeader> headers;
    private AbstractHttpClient httpClient;

    public static final String appManifestFilename = "app.manifest";
    public static final String extensionFilename = "ext.manifest";
    public static final Pattern extensionPattern = Pattern.compile(extensionFilename);

    /**
     * Creates an extender client
     *
     * @param extenderBaseUrl The build server url (e.g. https://build.defold.com)
     * @param cacheDir        A directory where the cache files are located (it must exist beforehand)
     */
    public ExtenderClient(String extenderBaseUrl, File cacheDir) throws IOException {
        this(new DefaultHttpClient(), extenderBaseUrl, cacheDir);
    }

    /**
     * Creates an extender client
     *
     * @param httpClient      The http client instance to use when communicating with the extender server
     * @param extenderBaseUrl The build server url (e.g. https://build.defold.com)
     * @param cacheDir        A directory where the cache files are located (it must exist beforehand)
     */
    public ExtenderClient(AbstractHttpClient httpClient, String extenderBaseUrl, File cacheDir) throws IOException {
        this.extenderBaseUrl = extenderBaseUrl;
        this.cache = new ExtenderClientCache(cacheDir);
        this.httpCookies = new BasicCookieStore();
        this.buildSleepTimeout = Long.parseLong(System.getProperty("com.defold.extender.client.build-sleep-timeout", "5000"));
        this.buildResultWaitTimeout = Long.parseLong(System.getProperty("com.defold.extender.client.build-wait-timeout", "240000"));
        this.headers = new ArrayList<BasicHeader>();
        this.httpClient = httpClient;
        this.httpClient.setCookieStore(httpCookies);
    }

    private void log(String s, Object... args) {
        logger.log(Level.INFO, String.format(s, args));
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

        // See CacheKeyGenerator.java in the server code
        // and native_extensions.clj for the equivalent parts
        byte[] bytes = digest.digest();
        return new BigInteger(1, bytes).toString(16);
    }

    /**
     * Set headers to be used in requests made by this client
     * @param headers List of headers (format: "HeaderName: HeaderValue")
     */
    public void setHeaders(List<String> headers) throws ExtenderClientException  {
        for (String header : headers) {
            setHeader(header);
        }
    }

    /**
     * Set a header to be used in requests made by this client
     * @param nameAndValue Header to set (format "HeaderName: HeaderValue")
     */
    public void setHeader(String nameAndValue) throws ExtenderClientException {
        String parts[] = nameAndValue.split("\\s*:\\s*", 2);
        if (parts.length != 2) {
            throw new ExtenderClientException("Not a valid header: " + nameAndValue);
        }
        String name = parts[0];
        String value = parts[1];
        headers.add(new BasicHeader(name, value));
    }

    /**
     * Set a header to be used in requests made by this client
     * @param name Header name
     * @param value Header value
     */
    public void setHeader(String name, String value) {
        headers.add(new BasicHeader(name, value));
    }

    // add all previously set headers to a request
    private void addHeaders(AbstractHttpMessage request) {
        for (BasicHeader header : headers) {
            request.setHeader(header);
        }
    }

    // add basic authorization header to a request if a username and password is set in system env
    private void addAuthorizationHeader(AbstractHttpMessage request) throws UnsupportedEncodingException {
        final String user = System.getenv("DM_EXTENDER_USERNAME");
        final String password = System.getenv("DM_EXTENDER_PASSWORD");
        if (user != null && password != null) {
            String encodedAuth = Base64.getEncoder().encodeToString((user + ":" + password).getBytes("UTF-8"));
            request.setHeader("Authorization", "Basic " + encodedAuth);
        }
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
        root.put("version", 1);
        root.put("hashType", "sha256");

        String data = root.toJSONString().replace("\\/", "/");

        try {
            String url = String.format("%s/query", extenderBaseUrl);
            HttpPost request = new HttpPost(url);
            request.setEntity(new ByteArrayEntity(data.getBytes()));
            request.setHeader("Accept", "application/json");
            request.setHeader("Content-type", "application/json");

            addAuthorizationHeader(request);
            addHeaders(request);

            HttpResponse response = httpClient.execute(request);

            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                return EntityUtils.toString(response.getEntity()).replace("\\/", "/");
            } else {
                return null; // Caching is not supported
            }
        } catch (Exception e) {
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


    private void build_async(String platform, String sdkVersion, MultipartEntity entity, File destination, File log) throws ExtenderClientException {
        try {
            String url = String.format("%s/build_async/%s/%s", extenderBaseUrl, platform, sdkVersion);
            HttpPost request = new HttpPost(url);
            request.setEntity(entity);

            log("Sending async build request to %s", url);

            addAuthorizationHeader(request);
            addHeaders(request);

            HttpResponse response = httpClient.execute(request);

            final int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode == HttpStatus.SC_OK) {
                String jobId = EntityUtils.toString(response.getEntity());
                log("Async build request was accepted as job %s", jobId);
                long currentTime = System.currentTimeMillis();
                Integer jobStatus = 0;
                Thread.sleep(buildSleepTimeout);
                log("Waiting for job %s to finish", jobId);
                while (System.currentTimeMillis() - currentTime < buildResultWaitTimeout) {
                    HttpGet statusRequest = new HttpGet(String.format("%s/job_status?jobId=%s", extenderBaseUrl, jobId));
                    response = httpClient.execute(statusRequest);
                    jobStatus = Integer.valueOf(EntityUtils.toString(response.getEntity()));
                    if (jobStatus != 0) {
                        break;
                    }
                    Thread.sleep(buildSleepTimeout);
                }

                if (jobStatus == 0) {
                    throw new TimeoutException(String.format("Job %s did not complete in time (timeout: %d ms)", jobId, buildResultWaitTimeout));
                }

                log("Checking job result for job %s", jobId);
                HttpGet resultRequest = new HttpGet(String.format("%s/job_result?jobId=%s", extenderBaseUrl, jobId));
                response = httpClient.execute(resultRequest);
                if (jobStatus == 1) {
                    log("Job %s completed successfully. Writing result to %s", jobId, destination);
                    response.getEntity().writeTo(new FileOutputStream(destination));
                } else {
                    log("Job %s did not complete successfully. Writing log to %s", jobId, log);
                    response.getEntity().writeTo(new FileOutputStream(log));
                    throw new ExtenderClientException("Failed to build source.");
                }
            } else {
                log("Async build request failed with status code %d", statusCode);
                response.getEntity().writeTo(new FileOutputStream(log));
                throw new ExtenderClientException("Failed to build source.");
            }
        } catch (Exception e) {
            throw new ExtenderClientException("Failed to communicate with Extender service.", e);
        }

    }

    private void build_sync(String platform, String sdkVersion, MultipartEntity entity, File destination, File log) throws ExtenderClientException {
        try {
            String url = String.format("%s/build/%s/%s", extenderBaseUrl, platform, sdkVersion);
            HttpPost request = new HttpPost(url);
            request.setEntity(entity);

            addAuthorizationHeader(request);
            addHeaders(request);

            HttpResponse response = httpClient.execute(request);

            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                response.getEntity().writeTo(new FileOutputStream(destination));
            } else {
                response.getEntity().writeTo(new FileOutputStream(log));
                throw new ExtenderClientException("Failed to build source.");
            }
        } catch (Exception e) {
            throw new ExtenderClientException("Failed to communicate with Extender service.", e);
        }
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
        build(platform, sdkVersion, sourceResources, destination, log, false);
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
     * @param async           True if build should be async and polling
     * @throws ExtenderClientException
     */
    public void build(String platform, String sdkVersion, List<ExtenderResource> sourceResources, File destination, File log, boolean async) throws ExtenderClientException {
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

        if (async) {
            build_async(platform, sdkVersion, entity, destination, log);
        }
        else {
            build_sync(platform, sdkVersion, entity, destination, log);
        }

        // Store the new build
        cache.put(platform, cacheKey, destination);
    }

    private static final String getRelativePath(File base, File path) {
        return base.toURI().relativize(path.toURI()).getPath();
    }

    public boolean health() throws IOException {
        HttpGet request = new HttpGet(extenderBaseUrl);
        addAuthorizationHeader(request);
        addHeaders(request);
        HttpResponse response = httpClient.execute(request);
        if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
            return true;
        }
        return false;
    }
}
