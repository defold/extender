package com.defold.extender.client;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.ByteArrayBody;
import org.apache.http.impl.client.DefaultHttpClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ExtenderClient {

    private final String extenderBaseUrl;
    private ExtenderClientCache cache;

    /** Creates a local build cache
     * @param extenderBaseUrl   The build server url (e.g. https://build.defold.com)
     * @param cacheDir          A directory where the cache files are located (it must exist beforehand)
     */
    public ExtenderClient(String extenderBaseUrl, File cacheDir) throws IOException {
        this.extenderBaseUrl = extenderBaseUrl;
        this.cache = new ExtenderClientCache(cacheDir);
    }

    /** Builds a new engine given a platform and an sdk version plus source files.
     * The result is a .zip file
     *
     * @param platform      E.g. "arm64-ios", "armv7-android", "x86_64-osx"
     * @param sdkVersion    Sha1 of defold version
     * @param root          Folder where to start creating relative paths from
     * @param sourceFiles   List of files that should be build on server (.cpp, .a, etc)
     * @param destination   The output where the returned zip file is copied
     * @param log           A log file
     * @throws ExtenderClientException
     */
    public void build(String platform, String sdkVersion, List<IExtenderResource> sourceFiles, File destination, File log) throws ExtenderClientException {
        String cacheKey = cache.calcKey(platform, sdkVersion, sourceFiles);
        boolean isCached = cache.isCached(platform, cacheKey);
        if (isCached) {
            cache.get(platform, cacheKey, destination);
            return;
        }

        MultipartEntity entity = new MultipartEntity();

        for (IExtenderResource s : sourceFiles) {
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

    public static final String extensionFilename = "ext.manifest";
    public static final Pattern extensionPattern = Pattern.compile(extensionFilename);

    private static final String getRelativePath(File base, File path) {
        return base.toURI().relativize(path.toURI()).getPath();
    }
}