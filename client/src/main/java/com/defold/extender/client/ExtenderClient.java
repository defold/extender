package com.defold.extender.client;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.message.AbstractHttpMessage;
import org.apache.http.message.BasicHeader;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.ByteArrayBody;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.apache.http.client.CookieStore;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.BasicCookieStore;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.Iterator;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ExtenderClient {
    private static Logger logger = Logger.getLogger(ExtenderClient.class.getName());

    private static final String TRACE_ID_HEADER_NAME = "X-TraceId";
    static final String SOURCE_CODE_ARCHIVE_MAGIC_NAME = "__source_code__.zip";
    static final String CACHE_INFO_NAME = "ne-cache-info.json";

    private final String extenderBaseUrl;
    private ExtenderClientCache cache;
    private long buildSleepTimeout;
    private long buildResultWaitTimeout;
    private List<BasicHeader> headers;
    private HttpClient httpClient;

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
        this(new BasicCookieStore(), extenderBaseUrl, cacheDir);
    }

    public ExtenderClient(CookieStore cookieStore,
            String extenderBaseUrl,
            File cacheDir) throws IOException {
        this(new ExtenderClientCache(cacheDir), cookieStore,
        HttpClientBuilder.create()
            .setDefaultRequestConfig(
                RequestConfig.custom()
                .setExpectContinueEnabled(true)
                .build()
            )
            .setDefaultCookieStore(cookieStore)
            .build()
        , extenderBaseUrl);
    }

    public ExtenderClient(ExtenderClientCache cache,
            CookieStore cookieStore,
            HttpClient httpClient,
            String extenderBaseUrl) {
        this.extenderBaseUrl = extenderBaseUrl;
        this.cache = cache;
        this.buildSleepTimeout = Long.parseLong(System.getProperty("com.defold.extender.client.build-sleep-timeout", "5000"));
        this.buildResultWaitTimeout = Long.parseLong(System.getProperty("com.defold.extender.client.build-wait-timeout", "1200000"));
        this.headers = new ArrayList<BasicHeader>();
        this.httpClient = httpClient;
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
            HttpPost request = createPostRequest(url);
            request.setEntity(new ByteArrayEntity(data.getBytes()));
            request.setHeader("Accept", "application/json");
            request.setHeader("Content-type", "application/json");

            HttpResponse response = httpClient.execute(request);

            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                return EntityUtils.toString(response.getEntity()).replace("\\/", "/");
            } else {
                EntityUtils.consumeQuietly(response.getEntity());
                return null; // Caching is not supported
            }
        } catch (Exception e) {
            throw new ExtenderClientException("Failed to communicate with Extender service.", e);
        }
    }

    // Gets a set of files that are currently cached
    static Set<String> getCachedFiles(String json) throws ExtenderClientException {
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


    private void build_async(String platform, String sdkVersion, HttpEntity entity, File destination, File log) throws ExtenderClientException {
        try {
            String url = String.format("%s/build_async/%s/%s", extenderBaseUrl, platform, sdkVersion);
            HttpPost request = createPostRequest(url);
            request.setEntity(entity);

            log("Sending async build request to %s", url);

            HttpResponse response = httpClient.execute(request);

            StatusLine statusLine = response.getStatusLine();
            final int statusCode = statusLine.getStatusCode();
            if (statusCode == HttpStatus.SC_OK) {
                String jobId = EntityUtils.toString(response.getEntity());
                String traceId = response.getFirstHeader(TRACE_ID_HEADER_NAME).getValue();
                log("Async build request was accepted as job %s (traceId: %s)", jobId, traceId == null ? "null" : traceId);
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
                    throw new TimeoutException(String.format("Job %s (traceId %s) did not complete in time (timeout: %d ms)", jobId, traceId == null ? "null" : traceId, buildResultWaitTimeout));
                }

                log("Checking job result for job %s", jobId);
                HttpGet resultRequest = new HttpGet(String.format("%s/job_result?jobId=%s", extenderBaseUrl, jobId));
                response = httpClient.execute(resultRequest);
                if (jobStatus == 1) {
                    log("Job %s completed successfully. Writing result to %s", jobId, destination);
                    response.getEntity().writeTo(new FileOutputStream(destination));
                } else {
                    log("Job %s did not complete successfully. Writing log to %s", jobId, log);
                    OutputStream os = new FileOutputStream(log);
                    os.write(String.format("Job id: %s; traceId: %s", jobId, traceId == null ? "null" : traceId).getBytes());
                    response.getEntity().writeTo(os);
                    os.close();
                    throw new ExtenderClientException(String.format("Failed to build source: jobId - %s, traceId - %s", jobId, traceId == null ? "null" : traceId));
                }
            } else {
                String result = String.format("Async build request failed with status code %d %s", statusCode, statusLine.getReasonPhrase());
                log(result);
                OutputStream os = new FileOutputStream(log);
                os.write(result.getBytes());
                response.getEntity().writeTo(os);
                os.close();
                throw new ExtenderClientException("Failed to build source.");
            }
        } catch (Exception e) {
            throw new ExtenderClientException("Failed to communicate with Extender service.", e);
        }
    }

    HttpEntity createBuildRequestPayload(List<ExtenderResource> sourceResources) throws ExtenderClientException {
        MultipartEntityBuilder entityBuilder = MultipartEntityBuilder.create();
        entityBuilder.setStrictMode();

        // // Now, let's ask the server what files it already has
        String cacheInfoJson = queryCache(sourceResources);
        final Set<String> cachedFiles = cacheInfoJson != null ? getCachedFiles(cacheInfoJson) : Set.of();
        if (cacheInfoJson != null) {
            // add the updated info to the file
            entityBuilder.addPart(CACHE_INFO_NAME, new ByteArrayBody(cacheInfoJson.getBytes(), CACHE_INFO_NAME)); // Same as specified in DataStoreService.java
        }

        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        try (ZipOutputStream zipStream = new ZipOutputStream(byteStream)) {
            sourceResources.stream()
            .filter(res -> {
                return !cachedFiles.contains(res.getPath());
            })
            .forEach(res -> {
                Path path = Path.of(res.getPath());
                try {
                    ZipEntry entry = new ZipEntry(path.toString());
                    zipStream.putNextEntry(entry);
                    zipStream.write(res.getContent());
                    zipStream.closeEntry();
                } catch (IOException e) { }
            });
        } catch (IOException exc) {
            throw new ExtenderClientException("Failed to create source code archive", exc);
        }
        entityBuilder.addPart(SOURCE_CODE_ARCHIVE_MAGIC_NAME, new ByteArrayBody(byteStream.toByteArray(), SOURCE_CODE_ARCHIVE_MAGIC_NAME));
        return entityBuilder.build();
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

        HttpEntity payload = createBuildRequestPayload(sourceResources);
        build_async(platform, sdkVersion, payload, destination, log);

        // Store the new build
        cache.put(platform, cacheKey, destination);
    }

    // Left for future debugging
    public String httpRequestToString(HttpRequestBase request) {
        StringBuilder sb = new StringBuilder();
        sb.append("Request " + request.getMethod() + " " + request.getURI() + "\n");
        sb.append("Headers = {\n");
        for (Header header : request.getAllHeaders()) {
            sb.append("  " + header.getName() + " = " + header.getValue() + ",\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    public boolean health() throws IOException {
        HttpGet request = createGetRequest(extenderBaseUrl);
        HttpResponse response = httpClient.execute(request);
        EntityUtils.consumeQuietly(response.getEntity());
        if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
            return true;
        }
        return false;
    }

    public HttpGet createGetRequest(String url) throws UnsupportedEncodingException {
        HttpGet request = new HttpGet(extenderBaseUrl);
        addAuthorizationHeader(request);
        addHeaders(request);
        return request;
    }

    public HttpPost createPostRequest(String url) throws UnsupportedEncodingException {
        HttpPost request = new HttpPost(url);

        addAuthorizationHeader(request);
        addHeaders(request);
        return request;
    }
}
