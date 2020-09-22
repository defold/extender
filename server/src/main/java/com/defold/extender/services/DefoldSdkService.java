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
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

@Service
public class DefoldSdkService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefoldSdkService.class);
    private static final String REMOTE_SDK_URL_PATTERNS[] = {"http://d.defold.com/archive/%s/engine/defoldsdk.zip", "http://d.defold.com/archive-switch/%s/engine/defoldsdk.zip"};
    private static final String TEST_SDK_DIRECTORY = "a";
    private static final String LOCAL_VERSION = "local";

    private final Path baseSdkDirectory;
    private final File dynamoHome;
    private final int cacheSize;

    private final CounterService counterService;
    private final GaugeService gaugeService;

    @Autowired
    DefoldSdkService(@Value("${extender.sdk.location}") String baseSdkDirectory,
                     @Value("${extender.sdk.cache-size}") int cacheSize,
                     CounterService counterService,
                     GaugeService gaugeService) throws IOException {
        this.baseSdkDirectory = new File(baseSdkDirectory).toPath();
        this.cacheSize = cacheSize;
        this.counterService = counterService;
        this.gaugeService = gaugeService;
        this.dynamoHome = System.getenv("DYNAMO_HOME") != null ? new File(System.getenv("DYNAMO_HOME")) : null;

        LOGGER.info("SDK service using directory {} with cache size {}", baseSdkDirectory, cacheSize);

        if (!Files.exists(this.baseSdkDirectory)) {
            Files.createDirectories(this.baseSdkDirectory);
        }
    }

    // Helper function to move the SDK directories
    public static void Move(Path source, Path target) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // If the target path suddenly exists, and the source path still exists,
            // then we failed with the atomic move, and we assume another job succeeded with the download
            if (Files.exists(source) && Files.exists(target)) {
                LOGGER.info("Defold SDK version {} was downloaded by another job in the meantime", source.toString());
                try {
                    FileUtils.deleteDirectory(source.toFile());
                } catch (IOException e2) {
                    LOGGER.error("Failed to delete temp sdk directory {}: {}", source.toString(), e2.getMessage());
                }
            }
        }
    }

    public String getSdkVersion(final String version) {
        return isLocalSdk(version) ? LOCAL_VERSION : version;
    }

    public boolean isLocalSdk(final String sdkVersion) {
        return sdkVersion == null || LOCAL_VERSION.equals(sdkVersion) || System.getenv("DYNAMO_HOME") != null;
    }

    public File getSdk(String hash) throws IOException, URISyntaxException, ExtenderException {
        return isLocalSdk(hash) ? getLocalSdk() : getRemoteSdk(hash);
    }

    public File getRemoteSdk(String hash) throws IOException, URISyntaxException, ExtenderException {
        long methodStart = System.currentTimeMillis();

        // Define SDK directory for this version
        File sdkDirectory = new File(baseSdkDirectory.toFile(), hash);

        // If directory does not exist, create it and download SDK
        if (!Files.exists(sdkDirectory.toPath())) {
            File lockFile = new File(baseSdkDirectory.toFile(), "tmp" + hash + ".lock");

            if (lockFile.createNewFile()) { // atomic creation of lock file
                try {
                    boolean sdkFound = false;
                    for (String url_pattern : REMOTE_SDK_URL_PATTERNS) {

                        URL url = new URL(String.format(url_pattern, hash));

                        ClientHttpRequestFactory clientHttpRequestFactory = new SimpleClientHttpRequestFactory();
                        ClientHttpRequest request = clientHttpRequestFactory.createRequest(url.toURI(), HttpMethod.GET);

                        // Connect and copy to file
                        try (ClientHttpResponse response = request.execute()) {
                            if (response.getStatusCode() != HttpStatus.OK) {
                                LOGGER.info("The given sdk does not exist: {} {}", hash, response.getStatusCode().toString());
                                continue;
                            }

                            LOGGER.info("Downloading Defold SDK from {} ...", url);
                            Path tempDirectoryPath = Files.createTempDirectory(baseSdkDirectory, "tmp" + hash);
                            File tmpSdkDirectory = tempDirectoryPath.toFile(); // Either moved or deleted later by Move()

                            Files.createDirectories(tempDirectoryPath);
                            InputStream body = response.getBody();
                            ZipUtils.unzip(body, tmpSdkDirectory.toPath());

                            Move(tmpSdkDirectory.toPath(), sdkDirectory.toPath());

                            sdkFound = true;
                            break;
                        }
                    }

                    if (!sdkFound) {
                        throw new ExtenderException(String.format("The given sdk does not exist: %s", hash));
                    }

                    // Delete old SDK:s
                    Comparator<Path> lastModifiedComparator = Comparator.comparing(path -> path.toFile().lastModified());

                    Files
                            .list(baseSdkDirectory)
                            .filter(path -> !path.getFileName().toString().startsWith("tmp"))
                            .sorted(lastModifiedComparator.reversed())
                            .skip(cacheSize)
                            .forEach(this::deleteCachedSdk);

                    counterService.increment("counter.service.sdk.get.download");
                } finally {
                    lockFile.delete();
                }
            } else {
                LOGGER.info("Waiting for Defold SDK version {} to download ...", hash);
                // We have to wait for the lock file to disappear
                int seconds = 120; // Downloading an sdk takes ~30-50 seconds
                while (Files.exists(lockFile.toPath()) && seconds > 0) {
                    int step = 2;
                    seconds -= step;
                    try {
                        Thread.sleep(step * 1000);
                    } catch (InterruptedException e) {
                        // pass
                    }
                }

                // Now check again that the SDK was downloaded
                if (!Files.exists(sdkDirectory.toPath())) {
                    throw new ExtenderException(String.format("The given sdk still does not exist: %s", hash));
                }
            }
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
        try {
            File tmpDir = new File(path.toString() + ".delete");
            Move(path, tmpDir.toPath());
            FileUtils.deleteDirectory(tmpDir);
        } catch (IOException e) {
            LOGGER.error("Failed to delete cached SDK at " + path.toAbsolutePath().toString(), e);
        }
    }

    @PreDestroy
    @SuppressWarnings("unused")
    public void destroy() {
        LOGGER.info("Cleaning up SDK cache");
        try {
            Files.list(baseSdkDirectory)
                    .filter(path -> ! path.endsWith(TEST_SDK_DIRECTORY))
                    .forEach(this::deleteCachedSdk);
        } catch(IOException e) {
            LOGGER.warn("Failed to list SDK cache directory: " + e.getMessage());
        }
    }
}
