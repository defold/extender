package com.defold.extender.services;

import com.defold.extender.ExtenderException;
import com.defold.extender.ZipUtils;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.metrics.CounterService;
import org.springframework.boot.actuate.metrics.GaugeService;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@Service
public class DefoldSdkService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefoldSdkService.class);
    private static final String REMOTE_SDK_URL_PATTERN = "http://d.defold.com/archive/%s/engine/defoldsdk.zip";
    private static final List<String> KEEP_SDK_DIRECTORIES = Collections.singletonList("a");

    private final Path baseSdkDirectory;
    private final File dynamoHome;
    private final int cacheSize;

    private final CounterService counterService;
    private final GaugeService gaugeService;

    @Autowired
    DefoldSdkService(@Value("${extender.sdk-location}") String baseSdkDirectory,
                     @Value("${extender.sdk-cache-size}") int cacheSize,
                     CounterService counterService,
                     GaugeService gaugeService) {
        this.baseSdkDirectory = new File(baseSdkDirectory).toPath();
        this.cacheSize = cacheSize;
        this.counterService = counterService;
        this.gaugeService = gaugeService;
        this.dynamoHome = System.getenv("DYNAMO_HOME") != null ? new File(System.getenv("DYNAMO_HOME")) : null;
    }

    public File getSdk(String hash) throws IOException, URISyntaxException, ExtenderException {
        long methodStart = System.currentTimeMillis();

        // Define SDK directory for this version
        File sdkDirectory = new File(baseSdkDirectory.toFile(),  hash);

        // If directory does not exist, create it and download SDK
        if (!Files.exists(sdkDirectory.toPath())) {
            LOGGER.info("Downloading Defold SDK version {} ...", hash);

            URL url = new URL(String.format(REMOTE_SDK_URL_PATTERN, hash));
            ClientHttpRequestFactory clientHttpRequestFactory = new SimpleClientHttpRequestFactory();
            ClientHttpRequest request = clientHttpRequestFactory.createRequest(url.toURI(), HttpMethod.GET);

            // Connect and copy to file
            try (ClientHttpResponse response = request.execute()) {
                if (response.getStatusCode() != HttpStatus.OK) {
                    throw new ExtenderException(String.format("The given sdk does not exist: %s (%s)", hash, response.getStatusCode().toString()));
                }

                Files.createDirectories(sdkDirectory.toPath());
                InputStream body = response.getBody();
                ZipUtils.unzip(body, sdkDirectory.toPath());
            }

            // Delete old SDK:s
            Comparator<Path> lastModifiedComparator = Comparator.comparing(path -> path.toFile().lastModified());
            Files
                    .list(baseSdkDirectory)
                    .sorted(lastModifiedComparator.reversed())
                    .skip(cacheSize)
                    .forEach(this::deleteCachedSdk);

            counterService.increment("counter.service.sdk.get.download");
        }

        LOGGER.info("Using Defold SDK version {}", hash);

        gaugeService.submit("gauge.service.sdk.get", System.currentTimeMillis() - methodStart);
        counterService.increment("counter.service.sdk.get");

        return new File(sdkDirectory, "defoldsdk");
    }

    public File getLocalSdk() {
        LOGGER.info("Using local Defold SDK at {}", dynamoHome.toString());
        return dynamoHome;
    }

    public boolean isLocalSdkSupported() {
        return dynamoHome != null;
    }

    private void deleteCachedSdk(Path path) {
        final String directoryToDelete = path.toAbsolutePath().toString();

        if (KEEP_SDK_DIRECTORIES.contains(directoryToDelete)) {
            return;
        }

        try {
            FileUtils.deleteDirectory(path.toFile());
        } catch (IOException e) {
            LOGGER.error("Failed to delete cached SDK at " + directoryToDelete, e);
        }
    }

    @PreDestroy
    @SuppressWarnings("unused")
    public void destroy() {
        LOGGER.info("Cleaning up SDK cache");
        try {
            Files.list(baseSdkDirectory).forEach(this::deleteCachedSdk);
        } catch(IOException e) {
            LOGGER.warn("Failed to list SDK cache directory: " + e.getMessage());
        }
    }
}
