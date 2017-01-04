package com.defold.extender.client;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

public class ExtenderClient {

    private final String extenderBaseUrl;

    public ExtenderClient(String extenderBaseUrl) {
        this.extenderBaseUrl = extenderBaseUrl;
    }

    public void build(String platform, String sdkVersion, List<String> sourceFiles, File destination, File log) throws ExtenderClientException {
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();

        for (String s : sourceFiles) {
            builder.addBinaryBody(s, new File(s));
        }

        HttpEntity entity = builder.build();
        String url = String.format("%s/build/%s/%s", extenderBaseUrl, platform, sdkVersion);
        HttpPost request = new HttpPost(url);

        request.setEntity(entity);

        try {
            try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
                HttpResponse response = client.execute(request);

                if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                    response.getEntity().writeTo(new FileOutputStream(destination));
                } else {
                    response.getEntity().writeTo(new FileOutputStream(log));
                    throw new ExtenderClientException("Failed to build source.");
                }

            }
        } catch (IOException e) {
            throw new ExtenderClientException("Failed to communicate with Extender service.", e);
        }
    }
}
