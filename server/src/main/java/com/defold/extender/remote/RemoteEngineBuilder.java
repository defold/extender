package com.defold.extender.remote;

import com.defold.extender.ExtenderException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

@Service
public class RemoteEngineBuilder {

    private final RestTemplate restTemplate;
    private final String remoteBuilderBaseUrl;

    @Autowired
    public RemoteEngineBuilder(final RestTemplate restTemplate,
                               @Value("${extender.remote-builder.url}") final String remoteBuilderBaseUrl) {
        this.restTemplate = restTemplate;
        this.remoteBuilderBaseUrl = remoteBuilderBaseUrl;
    }

    public byte[] build(final File projectDirectory,
                        final String platform,
                        final String sdkVersion) throws IOException, ExtenderException {

        final HttpEntity<MultiValueMap<String, Object>> requestEntity = createMultipartRequest(projectDirectory);

        // Send request to remote builder
        final String serverUrl = String.format("%s/build/%s/%s", remoteBuilderBaseUrl, platform, sdkVersion);
        final ResponseEntity<byte[]> response = restTemplate.postForEntity(serverUrl, requestEntity, byte[].class);

        if (! response.getStatusCode().is2xxSuccessful()) {
            throw new ExtenderException("Failed to build engine remotely: " + new String(response.getBody()));
        }

        return response.getBody();
    }

    HttpEntity<MultiValueMap<String, Object>> createMultipartRequest(final File projectDirectory) throws IOException {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        final MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        Files.walk(projectDirectory.toPath())
                .filter(Files::isRegularFile)
                .forEach(path -> body.add("files", new FileSystemResource(path.toFile())));

        return new HttpEntity<>(body, headers);
    }
}
