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

    public ExtenderClient(String extenderBaseUrl) {
        this.extenderBaseUrl = extenderBaseUrl;
    }

    public void build(String platform, String sdkVersion, List<IExtenderResource> sourceFiles, File destination, File log) throws ExtenderClientException {
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
    }

    public static final String extensionFilename = "ext.manifest";
    public static final Pattern extensionPattern = Pattern.compile(extensionFilename);

    private static final String getRelativePath(File base, File path) {
        return base.toURI().relativize(path.toURI()).getPath();
    }
}