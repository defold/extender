package com.defold.extender.services;

import com.defold.extender.ZipUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class DefoldSdkService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefoldSdkService.class);
    private static final String REMOTE_SDK_URL_PATTERN = "http://d.defold.com/archive/%s/engine/defoldsdk.zip";

    private final Path baseSdkDirectory;
    private final File dynamoHome;

    public DefoldSdkService(@Value("${extender.defoldSdkPath}") String baseSdkDirectory) {
        this.baseSdkDirectory = new File(baseSdkDirectory).toPath();
        this.dynamoHome = System.getenv("DYNAMO_HOME") != null ? new File(System.getenv("DYNAMO_HOME")) : null;
    }

    public File getSdk(String hash) throws IOException, URISyntaxException {

        // Define SDK directory for this version
        File sdkDirectory = new File(baseSdkDirectory.toFile(),  hash);

        // If directory does not exist, create it and download SDK
        if (!Files.exists(sdkDirectory.toPath())) {
            LOGGER.info("Downloading Defold SDK version {} ...", hash);
            Files.createDirectories(sdkDirectory.toPath());

            URL url = new URL(String.format(REMOTE_SDK_URL_PATTERN, hash));
            ClientHttpRequestFactory clientHttpRequestFactory = new SimpleClientHttpRequestFactory();
            ClientHttpRequest request = clientHttpRequestFactory.createRequest(url.toURI(), HttpMethod.GET);

            // Connect and copy to file
            try (ClientHttpResponse response = request.execute()) {
                InputStream body = response.getBody();
                ZipUtils.unzip(body, sdkDirectory.toPath());
            }
        }

        LOGGER.info("Using Defold SDK version {}", hash);
        return new File(sdkDirectory, "defoldsdk");
    }

    public File getLocalSdk() {
        LOGGER.info("Using local Defold SDK at {}", dynamoHome.toString());
        return dynamoHome;
    }
}
