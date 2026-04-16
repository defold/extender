package com.defold.extender.services;

import com.defold.extender.ExtenderException;
import com.defold.extender.ExtenderUtil;
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
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

@Service
public class DefoldSdkService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefoldSdkService.class);
    private static final String TEST_SDK_DIRECTORY = "a";
    private static final String LOCAL_VERSION = "local";
    private final File dynamoHome;

    private final DefoldSdkServiceConfiguration configuration;
    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, CompletableFuture<DefoldSdk>> operationCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> cacheReferenceCount;
    private final ConcurrentHashMap<String, CompletableFuture<JSONObject>> mappingsDownloadOperationCache = new ConcurrentHashMap<>();
    protected final LinkedHashMap<String, JSONObject> mappingsCache;

    private static ClientHttpRequestFactory clientHttpRequestFactory = new SimpleClientHttpRequestFactory() {
        @Override
        protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
            super.prepareConnection(connection, httpMethod);
            connection.setInstanceFollowRedirects("GET".equals(httpMethod) || "HEAD".equals(httpMethod));
        }
    };

    // SimpleClientHttpRequestFactory doesn't handle reiderect in case of switching protocols (https->http->https) by default
    // so need manual handling of such kind of redirects
    private static ClientHttpResponse doRequestWithRedirects(URI url, HttpMethod method, int maxRedirects) throws IOException, NullPointerException {
        ClientHttpResponse response = null;
        int counter = 0;
        do {
            ++counter;
            ClientHttpRequest request = clientHttpRequestFactory.createRequest(url, method);

            // Connect and copy to file
            response = request.execute();
            HttpStatusCode responseCode = response.getStatusCode();
            if (responseCode.is3xxRedirection()) {
                List<String> location = response.getHeaders().get(HttpHeaders.LOCATION);
                if (location == null || location.isEmpty()) {
                    break;
                }
                URI next = URI.create(location.get(0));
                url = url.resolve(next);
                response.close();
                continue;
            }
            return response;
        } while(counter <= maxRedirects);
        throw new NullPointerException(String.format("Max redirect count reached for request %s", url.toString()));
    }

    DefoldSdkService(DefoldSdkServiceConfiguration configuration,
                     MeterRegistry meterRegistry) throws IOException {
        this.configuration = configuration;
        this.meterRegistry = meterRegistry;

        this.dynamoHome = System.getenv("DYNAMO_HOME") != null ? new File(System.getenv("DYNAMO_HOME")) : null;
        this.cacheReferenceCount = new ConcurrentHashMap<>(this.configuration.getCacheSize() + 10);

        Path sdkLocation = this.configuration.getLocation();
        LOGGER.info("SDK service using directory {} with cache size {}", sdkLocation, this.configuration.getCacheSize());

        if (!Files.exists(sdkLocation)) {
            Files.createDirectories(sdkLocation);
        }

        mappingsCache = new LinkedHashMap<String, JSONObject>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, JSONObject> eldest) {
                return size() > configuration.getMappingsCacheSize();
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
            if (!sdk.isValid()) {
                throw new ExtenderException(String.format("Sdk verification failed: %s", hash));
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
            File sdkDirectory = new File(this.configuration.getLocation().toFile(), hash);
            File sdkRootDirectory = new File(sdkDirectory, "defoldsdk");
            DefoldSdk sdk = new DefoldSdk(sdkRootDirectory, hash, this);
            boolean isVerified = false;
            // If directory does not exist, create it and download SDK
            if (Files.exists(sdkDirectory.toPath())) {
                // if sdk already exists in cache - it means that sdk already verified
                isVerified = true;
            } else  {
                boolean sdkFound = false;
                String url = null;
                for (String urlPattern : configuration.getSdkUrls()) {
                    try {
                        url = String.format(urlPattern, hash);
                        URI sdkURI = URI.create(url);
                        try (ClientHttpResponse response = doRequestWithRedirects(sdkURI, HttpMethod.HEAD, configuration.getMaxRedirectCount())) {
                            if (response.getStatusCode() != HttpStatus.OK) {
                                LOGGER.info("The given sdk does not exist: {} {}", url, response.getStatusCode().toString());
                                continue;
                            } else {
                                // mark sdk as found for download
                                sdkFound = true;
                                break;
                            }
                        } catch (IOException exc) {
                            LOGGER.warn(String.format("HEAD for %s failed", url), exc);
                        }
                    }  catch (Exception exc) {
                        LOGGER.warn("Can't create HEAD request", exc);
                    }
                }
                if (sdkFound) {
                    int attempt = 0;
                    while (attempt < configuration.getMaxVerificationRetryCount()) {
                        LOGGER.info("Downloading Defold SDK from {} attempt {} ...", url, attempt + 1);
                        File tmpResponseBody = null;
                        // Connect and copy to file
                        try (ClientHttpResponse response = doRequestWithRedirects(URI.create(url), HttpMethod.GET, configuration.getMaxRedirectCount())) {
                            InputStream body = response.getBody();
                            tmpResponseBody = File.createTempFile(hash, ".zip.tmp");
                            Files.copy(body, tmpResponseBody.toPath(), StandardCopyOption.REPLACE_EXISTING);

                            if (this.configuration.isEnableSdkVerification()) {
                                LOGGER.info("Download checksum for sdk {}", hash);
                                URI checksumURI = URI.create(url.replace(".zip", ".sha256"));
                                String expectedChecksum = null;
                                try (ClientHttpResponse checksumResponse = doRequestWithRedirects(checksumURI, HttpMethod.GET, configuration.getMaxRedirectCount())) {
                                    if (checksumResponse.getStatusCode() == HttpStatus.OK) {
                                        expectedChecksum = new String(checksumResponse.getBody().readAllBytes(), StandardCharsets.UTF_8);
                                    }
                                } catch (IOException exc) {
                                    LOGGER.warn(String.format("Can't download checksum for sdk %s", hash), exc);
                                }
                                if (expectedChecksum == null) {
                                    LOGGER.warn(String.format("No checksum for sdk %s. Verification failed.", hash));
                                    break;
                                }

                                boolean isChecksumValid = false;
                                LOGGER.info("Verify checksum for downloaded sdk {}", hash);
                                try (InputStream is = new FileInputStream(tmpResponseBody)) {
                                    String actualChecksum = ExtenderUtil.calculateSHA256(is);
                                    isChecksumValid = expectedChecksum.equals(actualChecksum);
                                    LOGGER.info("Checksum verification result {}", isChecksumValid);
                                } catch(NoSuchAlgorithmException|IOException exc) {
                                    LOGGER.warn(String.format("Error during checksum calculation"), exc);
                                }
                                if (!isChecksumValid) {
                                    ++attempt;
                                    continue;
                                }
                            } else {
                                LOGGER.info("Sdk checksum verification is disabled");
                            }
                            Path tempDirectoryPath = Files.createTempDirectory(configuration.getLocation(), "tmp" + hash);
                            File tmpSdkDirectory = tempDirectoryPath.toFile(); // Either moved or deleted later by Move()

                            Files.createDirectories(tempDirectoryPath);
                            try (InputStream is = new FileInputStream(tmpResponseBody)) {
                                ZipUtils.unzip(is, tmpSdkDirectory.toPath());
                            }

                            Files.move(tmpSdkDirectory.toPath(), sdkDirectory.toPath(), StandardCopyOption.ATOMIC_MOVE);
                            isVerified = true;
                            break;
                        } catch (IOException exc) {
                            LOGGER.error("Error downloading defoldsdk", exc);
                        } finally {
                            if (tmpResponseBody != null && tmpResponseBody.exists()) {
                                tmpResponseBody.delete();
                            }
                        }
                    }
                } else {
                    sdk.close();
                    return null;
                }

                MetricsWriter.metricsCounterIncrement(meterRegistry, "extender.service.sdk.get.download", "sdk", hash);
            }
            MetricsWriter.metricsTimer(meterRegistry, "extender.service.sdk.get.duration", System.currentTimeMillis() - methodStart, "sdk", hash);
            if (!isVerified) {
                LOGGER.warn("Sdk {} verification failed", hash);
            }
            sdk.setVerified(isVerified);
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
            try (Stream<Path> entries = Files.list(configuration.getLocation())) {
                LOGGER.info("Cache eviction called");
                // Delete old SDK:s
                Comparator<Path> refCountComparator = Comparator.comparing(path -> getSdkRefCount(path.getFileName().toString()));
                        entries.filter(path -> !path.getFileName().toString().startsWith("tmp")
                                    && !path.toString().endsWith(".delete")
                                    && !path.getFileName().toString().equals(TEST_SDK_DIRECTORY))
                        .sorted(refCountComparator.reversed())
                        .skip(configuration.getCacheSize())
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
        if (!configuration.isCacheClearOnExit()) {
            LOGGER.info("Skipping cleanup of SDK cache");
            return;
        }
        LOGGER.info("Cleaning up SDK cache");
        try (Stream<Path> entries = Files.list(configuration.getLocation())) {
                    entries.filter(path -> ! path.endsWith(TEST_SDK_DIRECTORY))
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
                for (String url_pattern : configuration.getMappingsUrls()) {
                    try {
                        URI url = URI.create(String.format(url_pattern, hash));
                        try (ClientHttpResponse response = doRequestWithRedirects(url, HttpMethod.GET, configuration.getMaxRedirectCount())) {
                            HttpStatusCode responseCode = response.getStatusCode();
                            if (responseCode != HttpStatus.OK) {
                                LOGGER.info("The given sdk does not exist: {} {}", url, responseCode.toString());
                                continue;
                            }
            
                            LOGGER.info("Downloading platform sdks mappings from {} ...", url);
                            InputStream body = response.getBody();
                            JSONParser parser = new JSONParser();
                            try (Reader reader = new InputStreamReader(body)) {
                                result = (JSONObject)parser.parse(reader);
                            }
                            break;
                        }
                    } catch(IOException|ParseException exc) {
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

    private JSONObject getRemotePlatformSdkMappings(String hash) throws IOException, ExtenderException {
        CompletableFuture<JSONObject> operation = mappingsDownloadOperationCache.computeIfAbsent(hash, this::downloadSdkMappings);
        try {
            JSONObject result = operation.get();
            if (result == null) {
                String msg = String.format("Cannot find or parse platform sdks mappings for hash: %s", hash);
                LOGGER.error(msg);
                throw new ExtenderException(msg);
            }
            return result;
        } catch (InterruptedException|ExecutionException exc) {
            LOGGER.error(String.format("Mappings downloading %s was interrupted", hash), exc);
        } finally {
            mappingsDownloadOperationCache.remove(hash);
        }
        throw new ExtenderException(String.format("Cannot find platform sdks mappings for hash: %s", hash));
    }

    public JSONObject getPlatformSdkMappings(String hash) throws IOException, ExtenderException, ParseException {
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
