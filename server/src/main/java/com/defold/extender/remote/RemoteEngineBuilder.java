package com.defold.extender.remote;

import com.defold.extender.ExtenderException;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.ByteArrayBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

@Service
public class RemoteEngineBuilder {

    private final String remoteBuilderBaseUrl;

    @Autowired
    public RemoteEngineBuilder(@Value("${extender.remote-builder.url}") final String remoteBuilderBaseUrl) {
        this.remoteBuilderBaseUrl = remoteBuilderBaseUrl;
    }

    public byte[] build(final File projectDirectory,
                        final String platform,
                        final String sdkVersion) throws ExtenderException {

        MultipartEntity entity = new MultipartEntity();

        try {
            Files.walk(projectDirectory.toPath())
                    .filter(Files::isRegularFile)
                    .forEach(path -> {
                        String relativePath = path.toFile().getAbsolutePath().substring(projectDirectory.getAbsolutePath().length() + 1);
                        ByteArrayBody body;
                        try {
                            body = new ByteArrayBody(Files.readAllBytes(path), relativePath);
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                        entity.addPart(relativePath, body);
                    });
        } catch(IllegalStateException|IOException e) {
            throw new ExtenderException(e, "Failed to add files to multipart request");
        }

        final String serverUrl = String.format("%s/build/%s/%s", remoteBuilderBaseUrl, platform, sdkVersion);
        HttpPost request = new HttpPost(serverUrl);

        request.setEntity(entity);

        try {
            HttpClient client = new DefaultHttpClient();
            HttpResponse response = client.execute(request);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();

            response.getEntity().writeTo(bos);

            byte[] bytes = bos.toByteArray();

            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                throw new ExtenderException("Failed to build engine remotely: " + new String(bytes));
            }

            return bytes;
        } catch (IOException e) {
            throw new ExtenderException(e, "Failed to communicate with remote builder");
        }
    }
}
