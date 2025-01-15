package com.defold.extender.services;

import com.defold.extender.ExtenderException;
import com.defold.extender.ZipUtils;
import com.defold.extender.log.Markers;
import com.defold.extender.metrics.MetricsWriter;
import com.defold.extender.services.data.DefoldSdk;

import org.apache.commons.io.FileUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

@Service
public class DefoldSdkService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefoldSdkService.class);
    private static final String TEST_SDK_DIRECTORY = "a";
    private static final String LOCAL_VERSION = "local";

    private final String[] REMOTE_SDK_URL_PATTERNS;
    private final String[] REMOTE_MAPPINGS_URL_PATTERNS;
    private final Path baseSdkDirectory;
    private final File dynamoHome;
    private final int cacheSize;
    private final boolean cacheClearOnExit;

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, CompletableFuture<DefoldSdk>> operationCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> cacheReferenceCount;
    private final ConcurrentHashMap<String, CompletableFuture<JSONObject>> mappingsDownloadOperationCache = new ConcurrentHashMap<>();
    protected final LinkedHashMap<String, JSONObject> mappingsCache;

    DefoldSdkService(@Value("${extender.sdk.location}") String baseSdkDirectory,
                     @Value("${extender.sdk.cache-size}") int cacheSize,
                     @Value("${extender.sdk.cache-clear-on-exit}") boolean cacheClearOnExit,
                     @Value("${extender.sdk.sdk-urls}") String[] sdkUrlPatterns,
                     @Value("${extender.sdk.mappings-urls}") String[] mappingsUrlPatterns,
                     @Value("${extender.sdk.mappings-cache-size:20}") int mappingsCacheSize,
                     MeterRegistry meterRegistry) throws IOException {
        this.baseSdkDirectory = new File(baseSdkDirectory).toPath();
        this.cacheSize = cacheSize;
        this.meterRegistry = meterRegistry;
        this.cacheClearOnExit = cacheClearOnExit;
        this.REMOTE_SDK_URL_PATTERNS = sdkUrlPatterns;
        this.REMOTE_MAPPINGS_URL_PATTERNS = mappingsUrlPatterns;

        this.dynamoHome = System.getenv("DYNAMO_HOME") != null ? new File(System.getenv("DYNAMO_HOME")) : null;
        this.cacheReferenceCount = new ConcurrentHashMap<>(this.cacheSize + 10);

        LOGGER.info("SDK service using directory {} with cache size {}", baseSdkDirectory, cacheSize);

        if (!Files.exists(this.baseSdkDirectory)) {
            Files.createDirectories(this.baseSdkDirectory);
        }

        mappingsCache = new LinkedHashMap<String, JSONObject>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, JSONObject> eldest) {
                return size() > mappingsCacheSize;
            }
        };
    }

    public String getSdkVersion(final String version) {
        return isLocalSdk(version) ? LOCAL_VERSION : version;
    }

    public boolean isLocalSdk(final String sdkVersion) {
        return sdkVersion == null || LOCAL_VERSION.equals(sdkVersion) || System.getenv("DYNAMO_HOME") != null;
    }

    public DefoldSdk getSdk(String hash) throws ExtenderException {
        if (isLocalSdk(hash)){
            return getLocalSdk();
        }
        // use ConcurrentHashMap with CompletableFuture here to avoid situation when
        // several builds needs the same defoldsdk which doesn't exist locally. So one build job starts downloading,
        // all other jobs wait for download complete and all of then continue running.
        CompletableFuture<DefoldSdk> operation = operationCache.computeIfAbsent(hash, this::getRemoteSdk);
        try (DefoldSdk sdk = operation.get()){
            if (sdk == null) {
                throw new ExtenderException(String.format("The given sdk does not exist: %s", hash));
            }
            evictCache();
            LOGGER.info("Using Defold SDK version {}", hash);
            return DefoldSdk.copyOf(sdk);
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error(String.format("The given sdk cannot be downloaded: %s", hash), e);
            throw new ExtenderException(String.format("The given sdk cannot be downloaded: %s", hash));
        } finally {
            operationCache.remove(hash);
        }
    }

    public CompletableFuture<DefoldSdk> getRemoteSdk(String hash) {
        return CompletableFuture.supplyAsync(() -> {
            long methodStart = System.currentTimeMillis();
            // Define SDK directory for this version
            File sdkDirectory = new File(baseSdkDirectory.toFile(), hash);
            File sdkRootDirectory = new File(sdkDirectory, "defoldsdk");
            DefoldSdk sdk = new DefoldSdk(sdkRootDirectory, hash, this);
            // If directory does not exist, create it and download SDK
            if (!Files.exists(sdkDirectory.toPath())) {
                boolean sdkFound = false;
                for (String url_pattern : REMOTE_SDK_URL_PATTERNS) {
                    try {
                        URL url = URI.create(String.format(url_pattern, hash)).toURL();

                        ClientHttpRequestFactory clientHttpRequestFactory = new SimpleClientHttpRequestFactory();
                        ClientHttpRequest request = clientHttpRequestFactory.createRequest(url.toURI(), HttpMethod.GET);

                        // Connect and copy to file
                        try (ClientHttpResponse response = request.execute()) {
                            if (response.getStatusCode() != HttpStatus.OK) {
                                LOGGER.info("The given sdk does not exist: {} {}", url, response.getStatusCode().toString());
                                continue;
                            }

                            LOGGER.info("Downloading Defold SDK from {} ...", url);
                            Path tempDirectoryPath = Files.createTempDirectory(baseSdkDirectory, "tmp" + hash);
                            File tmpSdkDirectory = tempDirectoryPath.toFile(); // Either moved or deleted later by Move()

                            Files.createDirectories(tempDirectoryPath);
                            InputStream body = response.getBody();
                            ZipUtils.unzip(body, tmpSdkDirectory.toPath());

                            Files.move(tmpSdkDirectory.toPath(), sdkDirectory.toPath(), StandardCopyOption.ATOMIC_MOVE);
                            sdkFound = true;
                            break;
                        }
                    } catch (IOException|URISyntaxException exc) {
                        LOGGER.error("Error downloading defoldsdk", exc);
                    }
                }

                if (!sdkFound) {
                    sdk.close();
                    return null;
                }

                MetricsWriter.metricsCounterIncrement(meterRegistry, "extender.service.sdk.get.download", "sdk", hash);
            }
            MetricsWriter.metricsTimer(meterRegistry, "extender.service.sdk.get.duration", System.currentTimeMillis() - methodStart, "sdk", hash);
            return sdk;
        });
    }

    public DefoldSdk getLocalSdk() {
        LOGGER.info("Using local Defold SDK at {}", dynamoHome.toString());
        return new DefoldSdk(dynamoHome, LOCAL_VERSION, this);
    }

    public boolean isLocalSdkSupported() {
        return dynamoHome != null;
    }

    protected void evictCache() {
        synchronized (cacheReferenceCount) {
            try {
                LOGGER.info("Cache eviction called");
                // Delete old SDK:s
                Comparator<Path> refCountComparator = Comparator.comparing(path -> getSdkRefCount(path.getFileName().toString()));

                Files
                        .list(baseSdkDirectory)
                        .filter(path -> !path.getFileName().toString().startsWith("tmp")
                                    && !path.toString().endsWith(".delete")
                                    && !path.getFileName().toString().equals(TEST_SDK_DIRECTORY))
                        .sorted(refCountComparator.reversed())
                        .skip(cacheSize)
                        .forEach(this::deleteCachedSdk);
            } catch (IOException exc) {
                LOGGER.error("Error during cache eviction", exc);
            }
        }
    }

    private void deleteCachedSdk(Path path) {
        String sdkHash = path.getFileName().toString();
        if (getSdkRefCount(sdkHash) != 0) {
            LOGGER.warn(String.format("Sdk %s remove skipped due to non-zero ref count", sdkHash));
            return;
        }
        try {
            LOGGER.info(String.format("Cleanup sdk %s", path));
            File tmpDir = new File(path.toString() + ".delete");
            Files.move(path, tmpDir.toPath(), StandardCopyOption.ATOMIC_MOVE);
            FileUtils.deleteDirectory(tmpDir);
        } catch (IOException e) {
            LOGGER.error(Markers.CACHE_ERROR, "Failed to delete cached SDK at " + path.toAbsolutePath().toString(), e);
        }
    }

    @PreDestroy
    public void destroy() {
        if (!this.cacheClearOnExit) {
            LOGGER.info("Skipping cleanup of SDK cache");
            return;
        }
        LOGGER.info("Cleaning up SDK cache");
        try {
            Files.list(baseSdkDirectory)
                    .filter(path -> ! path.endsWith(TEST_SDK_DIRECTORY))
                    .forEach(this::deleteCachedSdk);
        } catch(IOException e) {
            LOGGER.warn("Failed to list SDK cache directory: " + e.getMessage());
        }
    }

    private JSONObject getLocalPlatformSdkMappings(String hash) throws IOException, ParseException {
        JSONParser parser = new JSONParser();
        return (JSONObject)parser.parse(new FileReader(Path.of(getLocalSdk().toFile().getAbsolutePath(), "platform.sdks.json").toFile()));
    }

    private CompletableFuture<JSONObject> downloadSdkMappings(String hash) {
        return CompletableFuture.supplyAsync(() -> {
            JSONObject result = null;
            synchronized(mappingsCache) {
                if (mappingsCache.containsKey(hash)) {
                    result = mappingsCache.get(hash);
                }
            }
            if (result == null) {
                for (String url_pattern : REMOTE_MAPPINGS_URL_PATTERNS) {
                    try {
                        URL url = URI.create(String.format(url_pattern, hash)).toURL();
            
                        ClientHttpRequestFactory clientHttpRequestFactory = new SimpleClientHttpRequestFactory();
                        ClientHttpRequest request = clientHttpRequestFactory.createRequest(url.toURI(), HttpMethod.GET);
            
                        // Connect and copy to file
                        try (ClientHttpResponse response = request.execute()) {
                            if (response.getStatusCode() != HttpStatus.OK) {
                                LOGGER.info("The given sdk does not exist: {} {}", url, response.getStatusCode().toString());
                                continue;
                            }
            
                            LOGGER.info("Downloading platform sdks mappings from {} ...", url);
                            InputStream body = response.getBody();
                            JSONParser parser = new JSONParser();
                            result = (JSONObject)parser.parse(new InputStreamReader(body));
                            break;
                        }
                    } catch(URISyntaxException|IOException|ParseException exc) {
                        LOGGER.error(String.format("Error during loading sdk mappings for %s", hash), exc);
                    }
                }
            }
            if (result != null) {
                synchronized(mappingsCache) {
                    mappingsCache.put(hash, result);
                }
            }
            return result;
        });
    }

    private JSONObject getRemotePlatformSdkMappings(String hash) throws IOException, URISyntaxException, ExtenderException {
        CompletableFuture<JSONObject> operation = mappingsDownloadOperationCache.computeIfAbsent(hash, this::downloadSdkMappings);
        try {
            JSONObject result = operation.get();
            if (result == null) {
                throw new ExtenderException(String.format("Cannot find or parse platform sdks mappings for hash: %s", hash));
            }
            return result;
        } catch (InterruptedException|ExecutionException exc) {
            LOGGER.error(String.format("Mappings downloading %s was interrupted", hash), exc);
        }
        throw new ExtenderException(String.format("Cannot find platform sdks mappings for hash: %s", hash));
    }

    public JSONObject getPlatformSdkMappings(String hash) throws IOException, URISyntaxException, ExtenderException, ParseException {
        return isLocalSdk(hash) ? getLocalPlatformSdkMappings(hash) : getRemotePlatformSdkMappings(hash);
    }

    public void acquireSdk(String hash) {
        LOGGER.info(String.format("Acquire sdk %s", hash));
        cacheReferenceCount.compute(hash, ( key, value) -> value == null ? 1 : value + 1);
    }

    public void releaseSdk(String hash) {
        LOGGER.info(String.format("Release sdk %s", hash));
        cacheReferenceCount.compute(hash, (key, value) -> value - 1);
    }

    public Integer getSdkRefCount(String hash) {
        return cacheReferenceCount.getOrDefault(hash, 0);
    }
}
