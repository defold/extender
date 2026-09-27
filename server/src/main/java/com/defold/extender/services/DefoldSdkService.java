package com.defold.extender.services;

import com.defold.extender.ExtenderException;
import com.defold.extender.PlatformNotSupportedException;
import com.defold.extender.VersionNotSupportedException;
import com.defold.extender.ExtenderUtil;
import com.defold.extender.ZipUtils;
import com.defold.extender.log.Markers;
import com.defold.extender.metrics.MetricsWriter;
import com.defold.extender.services.data.DefoldSdk;
import com.defold.extender.services.data.ResolvedSdk;
import com.defold.extender.services.data.SdkSelection;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
    private final ConcurrentHashMap<String, CompletableFuture<Void>> operationCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> cacheReferenceCount;
    record ResolutionKey(String hash, String platform, String sourceId) {}

    private record SdkSource(URI mappingsUri, URI archiveUri) {
        String id() { return ResolvedSdk.sourceId(mappingsUri, archiveUri); }
        String label() { return "source " + id().substring(0, 12); }
        @Override public String toString() { return label(); }
    }

    // Only messages constructed here are safe to include in build errors. HTTP clients,
    // JSON parsers and URI parsers can put credentials or response contents in messages.
    private static class SourceException extends IOException {
        SourceException(String message) { super(message); }
    }

    private static String safeFailure(Throwable failure) {
        if (failure instanceof SourceException) {
            return failure.getMessage();
        }
        return failure instanceof ParseException ? "Invalid mapping JSON" : failure.getClass().getSimpleName();
    }

    private final ConcurrentHashMap<ResolutionKey, CompletableFuture<ResolvedSdk>> mappingsDownloadOperationCache = new ConcurrentHashMap<>();
    protected final LinkedHashMap<ResolutionKey, ResolvedSdk> mappingsCache;

    private static ClientHttpRequestFactory clientHttpRequestFactory = new SimpleClientHttpRequestFactory() {
        @Override
        protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
            super.prepareConnection(connection, httpMethod);
            connection.setInstanceFollowRedirects("GET".equals(httpMethod) || "HEAD".equals(httpMethod));
        }
    };

    // SimpleClientHttpRequestFactory doesn't handle reiderect in case of switching protocols (https->http->https) by default
    // so need manual handling of such kind of redirects
    private static ClientHttpResponse doRequestWithRedirects(URI url, HttpMethod method, int maxRedirects) throws IOException {
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
                response.close();
                if (location == null || location.isEmpty()) {
                    throw new SourceException("Redirect without a Location header");
                }
                URI next = URI.create(location.get(0));
                url = url.resolve(next);
                continue;
            }
            return response;
        } while(counter <= maxRedirects);
        throw new SourceException("Maximum redirect count reached");
    }

    DefoldSdkService(DefoldSdkServiceConfiguration configuration,
                     MeterRegistry meterRegistry) throws IOException {
        this.configuration = configuration;
        for (DefoldSdkServiceConfiguration.Source source : configuration.getConfiguredSources()) {
            if (source.getMappingsUrl() == null || source.getMappingsUrl().isBlank()
                    || source.getSdkUrl() == null || source.getSdkUrl().isBlank()) {
                throw new IllegalArgumentException("Each extender.sdk.sources entry must specify mappings-url and sdk-url");
            }
        }
        if (configuration.getSdkUrls() != null || configuration.getMappingsUrls() != null) {
            LOGGER.warn("Using paired legacy SDK URLs; migrate to extender.sdk.sources");
        }
        this.meterRegistry = meterRegistry;

        this.dynamoHome = System.getenv("DYNAMO_HOME") != null ? new File(System.getenv("DYNAMO_HOME")) : null;
        if (configuration.getConfiguredSources().isEmpty() && dynamoHome == null) {
            throw new IllegalArgumentException("Configure at least one extender.sdk.sources entry or set DYNAMO_HOME");
        }
        this.cacheReferenceCount = new ConcurrentHashMap<>(this.configuration.getCacheSize() + 10);

        Path sdkLocation = this.configuration.getLocation();
        LOGGER.info("SDK service using directory {} with cache size {}", sdkLocation, this.configuration.getCacheSize());

        if (!Files.exists(sdkLocation)) {
            Files.createDirectories(sdkLocation);
        }

        mappingsCache = new LinkedHashMap<ResolutionKey, ResolvedSdk>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ResolutionKey, ResolvedSdk> eldest) {
                return size() > configuration.getMappingsCacheSize();
            }
        };
    }

    public String getSdkVersion(final String version) {
        return isLocalSdk(version) ? LOCAL_VERSION : version;
    }

    public boolean isLocalSdk(final String sdkVersion) {
        return sdkVersion == null || LOCAL_VERSION.equals(sdkVersion) || dynamoHome != null;
    }

    public DefoldSdk getSdk(String hash, String platform) throws ExtenderException {
        if (isLocalSdk(hash)) {
            return getLocalSdk();
        }
        return getSdk(resolveSdk(hash, platform));
    }

    public DefoldSdk getSdk(ResolvedSdk resolved) throws ExtenderException {
        if (resolved.archiveUri() == null) {
            return getLocalSdk();
        }
        String hash = resolved.hash();
        String cacheKey = resolved.cacheKey();
        File sdkRoot = configuration.getLocation().resolve(cacheKey).resolve("defoldsdk").toFile();
        // Each caller owns a reference before it joins the download, so eviction cannot
        // remove the archive between download completion and handing it to the caller.
        DefoldSdk sdk = new DefoldSdk(sdkRoot, hash, cacheKey, this);
        try {
            CompletableFuture<Void> operation = operationCache.computeIfAbsent(cacheKey, key ->
                CompletableFuture.runAsync(() -> {
                    acquireSdk(key);
                    try {
                        downloadSdk(resolved);
                    } catch (ExtenderException e) {
                        throw new CompletionException(e);
                    } finally {
                        releaseSdk(key);
                    }
                }));
            operation.whenComplete((result, error) -> operationCache.remove(cacheKey, operation));
            try {
                operation.get();
            } finally {
                // A waiter may wake before the completion callback runs. In particular,
                // a failed operation must be removed before the next caller retries.
                if (operation.isDone()) {
                    operationCache.remove(cacheKey, operation);
                }
            }
            persistResolution(resolved);
            sdk.setVerified(true);
            evictCache();
            LOGGER.info("Using Defold SDK version {} from source {}", hash, resolved.selection().sourceId());
            return sdk;
        } catch (InterruptedException e) {
            sdk.close();
            Thread.currentThread().interrupt();
            throw new ExtenderException("Interrupted downloading SDK " + hash);
        } catch (ExecutionException e) {
            sdk.close();
            if (e.getCause() instanceof ExtenderException cause) {
                throw cause;
            }
            throw new ExtenderException("Cannot download SDK " + hash + ": " + safeFailure(e.getCause()));
        } catch (RuntimeException e) {
            sdk.close();
            throw new ExtenderException("Cannot download SDK " + hash + ": " + safeFailure(e));
        }
    }

    private void downloadSdk(ResolvedSdk resolved) throws ExtenderException {
        long methodStart = System.currentTimeMillis();
        String hash = resolved.hash();
        String cacheKey = resolved.cacheKey();
        Path sdkDirectory = configuration.getLocation().resolve(cacheKey);
        Path sdkRoot = sdkDirectory.resolve("defoldsdk");
        URI archiveUri = resolved.archiveUri();
        if (Files.isDirectory(sdkRoot)) {
            return;
        }

        String failure = "Download failed";
        for (int attempt = 0; attempt < configuration.getMaxVerificationRetryCount(); ++attempt) {
            Path archive = null;
            Path unpacked = null;
            try {
                LOGGER.info("Downloading Defold SDK {} from source {} attempt {}", hash, resolved.selection().sourceId(), attempt + 1);
                try (ClientHttpResponse response = doRequestWithRedirects(archiveUri, HttpMethod.GET, configuration.getMaxRedirectCount())) {
                    if (response.getStatusCode() != HttpStatus.OK) {
                        throw new SourceException("SDK archive returned HTTP " + response.getStatusCode().value());
                    }
                    archive = Files.createTempFile("defoldsdk-", ".zip.tmp");
                    Files.copy(response.getBody(), archive, StandardCopyOption.REPLACE_EXISTING);
                }

                if (configuration.isEnableSdkVerification()) {
                    URI checksumUri = URI.create(archiveUri.toString().replace(".zip", ".sha256"));
                    String expectedChecksum;
                    try (ClientHttpResponse response = doRequestWithRedirects(checksumUri, HttpMethod.GET, configuration.getMaxRedirectCount())) {
                        if (response.getStatusCode() != HttpStatus.OK) {
                            throw new SourceException("SDK checksum returned HTTP " + response.getStatusCode().value());
                        }
                        expectedChecksum = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8).trim();
                    }
                    try (InputStream input = Files.newInputStream(archive)) {
                        if (!expectedChecksum.equals(ExtenderUtil.calculateSHA256(input))) {
                            throw new SourceException("SDK checksum verification failed");
                        }
                    }
                }

                unpacked = Files.createTempDirectory(configuration.getLocation(), "tmp-sdk-");
                try (InputStream input = Files.newInputStream(archive)) {
                    ZipUtils.unzip(input, unpacked);
                }
                if (!Files.isDirectory(unpacked.resolve("defoldsdk"))) {
                    throw new SourceException("SDK archive does not contain a defoldsdk directory");
                }
                // Only complete, verified archives are made visible to other builds.
                Files.move(unpacked, sdkDirectory, StandardCopyOption.ATOMIC_MOVE);
                unpacked = null;
                MetricsWriter.metricsCounterIncrement(meterRegistry, "extender.service.sdk.get.download", "sdk", hash);
                MetricsWriter.metricsTimer(meterRegistry, "extender.service.sdk.get.duration", System.currentTimeMillis() - methodStart, "sdk", hash);
                return;
            } catch (IOException | NoSuchAlgorithmException e) {
                // Another process may have populated this exact archive in a shared cache.
                if (Files.isDirectory(sdkRoot)) {
                    return;
                }
                failure = safeFailure(e);
                LOGGER.warn("Failed to download SDK {} from source {}: {}", hash, resolved.selection().sourceId(), failure);
            } finally {
                if (archive != null) {
                    FileUtils.deleteQuietly(archive.toFile());
                }
                if (unpacked != null) {
                    FileUtils.deleteQuietly(unpacked.toFile());
                }
            }
        }
        throw new ExtenderException(String.format("Cannot download SDK '%s' for platform '%s' from %s: %s",
            hash, resolved.targetPlatform(), "source " + resolved.selection().sourceId(), failure));
    }

    public DefoldSdk getLocalSdk() {
        if (dynamoHome == null) {
            throw new IllegalStateException("DYNAMO_HOME must be set to use a local SDK");
        }
        LOGGER.info("Using local Defold SDK at {}", dynamoHome.toString());
        DefoldSdk sdk = new DefoldSdk(dynamoHome, LOCAL_VERSION, this);
        sdk.setVerified(true);
        return sdk;
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
        synchronized (cacheReferenceCount) {
            try (Stream<Path> entries = Files.list(configuration.getLocation())) {
                entries.filter(path -> !path.endsWith(TEST_SDK_DIRECTORY)
                            && !path.getFileName().toString().startsWith("tmp"))
                    .forEach(this::deleteCachedSdk);
            } catch (IOException e) {
                LOGGER.warn("Failed to list SDK cache directory: " + e.getMessage());
            }
        }
    }

    private ResolvedSdk readMapping(Reader reader, ResolutionKey key, URI mappingsUri, URI archiveUri)
            throws IOException, ParseException {
        Object document = new JSONParser().parse(reader);
        if (!(document instanceof JSONObject mappings)) {
            throw new SourceException("SDK mapping must be a JSON object");
        }
        Object entry = mappings.get(key.platform());
        if (!mappings.containsKey(key.platform())) {
            return null;
        }
        return resolveMappingEntry(entry, key, mappingsUri, archiveUri);
    }

    private ResolvedSdk resolveMappingEntry(Object entry, ResolutionKey key, URI mappingsUri, URI archiveUri) throws IOException {
        if (!(entry instanceof List<?> values) || values.size() != 2
                || !(values.get(0) instanceof String name) || name.isBlank()
                || !(values.get(1) instanceof String version) || version.isBlank()) {
            throw new SourceException("Invalid SDK mapping for platform '" + key.platform() + "': expected two nonempty strings");
        }
        return new ResolvedSdk(key.hash(), key.platform(), name, version, mappingsUri, archiveUri);
    }

    private List<SdkSource> configuredSources(ResolutionKey key) throws ExtenderException {
        List<SdkSource> sources = new ArrayList<>();
        try {
            for (DefoldSdkServiceConfiguration.Source config : configuration.getConfiguredSources()) {
                SdkSource source = new SdkSource(URI.create(String.format(config.getMappingsUrl(), key.hash())),
                    URI.create(String.format(config.getSdkUrl(), key.hash())));
                if (key.sourceId() == null || key.sourceId().equals(source.id())) {
                    sources.add(source);
                }
            }
        } catch (IllegalArgumentException e) {
            throw new ExtenderException("Invalid configured SDK source URL pattern");
        }
        if (sources.isEmpty() && key.sourceId() != null) {
            throw new ExtenderException("SDK source selected by the frontend is not configured on this builder");
        }
        return sources;
    }

    private Path resolutionPath(Path archiveDirectory, String sourceId, String platform) {
        return archiveDirectory.resolve("resolution-" + ResolvedSdk.digest(sourceId + "\n" + platform) + ".json");
    }

    private void persistResolution(ResolvedSdk resolved) {
        Path directory = configuration.getLocation().resolve(resolved.cacheKey());
        String sourceId = resolved.selection().sourceId();
        Path path = resolutionPath(directory, sourceId, resolved.targetPlatform());
        JSONObject metadata = new JSONObject(Map.of(
            "schema", 1, "hash", resolved.hash(), "platform", resolved.targetPlatform(), "source", sourceId,
            "sdk", List.of(resolved.sdkName(), resolved.sdkVersion())));
        Path temporary = null;
        try {
            temporary = Files.createTempFile(directory, "tmp-resolution-", ".json");
            Files.writeString(temporary, metadata.toJSONString());
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // The archive is still usable if metadata cannot be persisted.
            LOGGER.warn("Cannot persist SDK resolution for {}: {}", resolved.hash(), safeFailure(e));
        } finally {
            if (temporary != null) {
                FileUtils.deleteQuietly(temporary.toFile());
            }
        }
    }

    private ResolvedSdk cachedResolution(ResolutionKey key, List<SdkSource> sources) {
        for (SdkSource source : sources) {
            Path directory = configuration.getLocation().resolve(ResolvedSdk.cacheKey(source.archiveUri()));
            if (!Files.isDirectory(directory.resolve("defoldsdk"))) {
                continue;
            }
            Path path = resolutionPath(directory, source.id(), key.platform());
            if (!Files.isRegularFile(path)) {
                continue;
            }
            try (Reader reader = Files.newBufferedReader(path)) {
                Object document = new JSONParser().parse(reader);
                if (!(document instanceof JSONObject metadata) || !Long.valueOf(1).equals(metadata.get("schema"))
                        || !key.hash().equals(metadata.get("hash")) || !key.platform().equals(metadata.get("platform"))
                        || !source.id().equals(metadata.get("source"))) {
                    continue;
                }
                ResolvedSdk resolved = resolveMappingEntry(metadata.get("sdk"), key, source.mappingsUri(), source.archiveUri());
                LOGGER.info("Using cached SDK resolution for engine {} platform {} from {}", key.hash(), key.platform(), source.label());
                return resolved;
            } catch (IOException | ParseException | RuntimeException e) {
                LOGGER.warn("Ignoring invalid cached SDK resolution for {}: {}", key.hash(), safeFailure(e));
            }
        }
        return null;
    }

    private ResolvedSdk downloadSdkMappings(ResolutionKey key, List<SdkSource> sources) throws ExtenderException {
        synchronized (mappingsCache) {
            ResolvedSdk cached = mappingsCache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        ResolvedSdk persisted = cachedResolution(key, sources);
        if (persisted != null) {
            synchronized (mappingsCache) {
                mappingsCache.put(key, persisted);
            }
            return persisted;
        }
        boolean foundMappings = false;
        List<String> failures = new ArrayList<>();
        for (SdkSource source : sources) {
            try (ClientHttpResponse response = doRequestWithRedirects(source.mappingsUri(), HttpMethod.GET, configuration.getMaxRedirectCount())) {
                HttpStatusCode status = response.getStatusCode();
                if (status == HttpStatus.NOT_FOUND || status == HttpStatus.GONE) {
                    continue;
                }
                if (status != HttpStatus.OK) {
                    throw new SourceException("HTTP " + status.value());
                }
                ResolvedSdk resolved;
                try (Reader reader = new InputStreamReader(response.getBody(), StandardCharsets.UTF_8)) {
                    resolved = readMapping(reader, key, source.mappingsUri(), source.archiveUri());
                }
                foundMappings = true;
                if (resolved == null) {
                    LOGGER.info("SDK mapping from {} does not support {}, trying next source", source.label(), key.platform());
                    continue;
                }
                synchronized (mappingsCache) {
                    mappingsCache.put(key, resolved);
                }
                LOGGER.info("Resolved engine {} platform {} using {}", key.hash(), key.platform(), source.label());
                return resolved;
            } catch (IOException | ParseException | RuntimeException e) {
                String failure = source.label() + ": " + safeFailure(e);
                failures.add(failure);
                LOGGER.warn("Cannot read SDK mapping: {}", failure);
            }
        }
        if (!failures.isEmpty()) {
            throw new ExtenderException(String.format("Cannot resolve SDK '%s' for platform '%s': %s",
                key.hash(), key.platform(), String.join("; ", failures)));
        }
        if (foundMappings) {
            throw new PlatformNotSupportedException(key.platform(), key.hash());
        }
        throw new VersionNotSupportedException(key.hash());
    }

    public ResolvedSdk resolveSdk(String hash, String platform) throws ExtenderException {
        return resolveSdk(hash, platform, null);
    }

    public ResolvedSdk resolveSdk(String hash, String platform, SdkSelection selection) throws ExtenderException {
        ResolutionKey key = new ResolutionKey(hash, platform, selection == null ? null : selection.sourceId());
        if (isLocalSdk(hash)) {
            if (selection != null) {
                throw new ExtenderException("Cannot use a local SDK for a request selecting a remote SDK source");
            }
            if (dynamoHome == null) {
                throw new ExtenderException("DYNAMO_HOME must be set to use a local SDK");
            }
            Path path = dynamoHome.toPath().resolve("platform.sdks.json");
            try (Reader reader = Files.newBufferedReader(path)) {
                ResolvedSdk resolved = readMapping(reader, key, path.toUri(), null);
                if (resolved == null) {
                    throw new PlatformNotSupportedException(platform, LOCAL_VERSION);
                }
                return resolved;
            } catch (IOException | ParseException e) {
                throw new ExtenderException("Cannot read local SDK mapping: " + safeFailure(e));
            }
        }
        List<SdkSource> sources = configuredSources(key);
        synchronized (mappingsCache) {
            ResolvedSdk cached = mappingsCache.get(key);
            if (cached != null) {
                if (selection != null) {
                    selection.validate(cached);
                }
                return cached;
            }
        }
        CompletableFuture<ResolvedSdk> operation = mappingsDownloadOperationCache.computeIfAbsent(key, ignored ->
            CompletableFuture.supplyAsync(() -> {
                try {
                    return downloadSdkMappings(key, sources);
                } catch (ExtenderException e) {
                    throw new CompletionException(e);
                }
            }));
        operation.whenComplete((result, error) -> mappingsDownloadOperationCache.remove(key, operation));
        try {
            ResolvedSdk resolved = operation.get();
            if (selection != null) {
                selection.validate(resolved);
            }
            return resolved;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExtenderException("Interrupted resolving SDK " + hash + " for platform " + platform);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ExtenderException cause) {
                throw cause;
            }
            throw new ExtenderException("Cannot resolve SDK " + hash + " for platform " + platform + ": " + safeFailure(e.getCause()));
        } finally {
            if (operation.isDone()) {
                mappingsDownloadOperationCache.remove(key, operation);
            }
        }
    }

    public void acquireSdk(String cacheKey) {
        synchronized (cacheReferenceCount) {
            cacheReferenceCount.merge(cacheKey, 1, Integer::sum);
        }
    }

    public void releaseSdk(String cacheKey) {
        synchronized (cacheReferenceCount) {
            cacheReferenceCount.compute(cacheKey, (key, value) -> value == 1 ? null : value - 1);
        }
    }

    public Integer getSdkRefCount(String cacheKey) {
        return cacheReferenceCount.getOrDefault(cacheKey, 0);
    }
}
