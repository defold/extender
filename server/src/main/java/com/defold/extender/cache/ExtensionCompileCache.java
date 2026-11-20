package com.defold.extender.cache;

import com.defold.extender.ExtenderException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ExtensionCompileCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExtensionCompileCache.class);
    private static final String KEY_PATTERN = "[0-9a-f]{64}";

    private final Path directory;
    private final String toolchainId;
    private final long maxSizeBytes;
    private final long probeTimeoutMillis;
    private final Environment environment;
    private final Object storageLock = new Object();
    private final ConcurrentHashMap<String, BuildLock> builds = new ConcurrentHashMap<>();

    public ExtensionCompileCache(
            @Value("${extender.extension-cache.directory:}") String directory,
            @Value("${extender.extension-cache.toolchain-id:}") String toolchainId,
            @Value("${extender.extension-cache.max-size-bytes:5368709120}") long maxSizeBytes,
            @Value("${extender.extension-cache.probe-timeout-millis:1000}") long probeTimeoutMillis,
            Environment environment) {
        this.directory = directory.isBlank() ? null : Path.of(directory).toAbsolutePath().normalize().resolve("native-archives-v1");
        this.toolchainId = toolchainId;
        this.maxSizeBytes = maxSizeBytes;
        this.probeTimeoutMillis = probeTimeoutMillis;
        this.environment = environment;
    }

    public boolean enabledForPlatform(String platform) {
        return enabled() && environment.getProperty("extender.extension-cache.platforms." + platform, Boolean.class, false);
    }

    private boolean enabled() {
        return directory != null && !toolchainId.isBlank() && maxSizeBytes > 0;
    }

    public String toolchainId() {
        return toolchainId;
    }

    public long probeTimeoutMillis() {
        return probeTimeoutMillis;
    }

    public boolean restore(String family, String lookupKey, Path output) {
        if (!enabled() || lookupKey == null) {
            return false;
        }
        synchronized (storageLock) {
            try {
                Path artifact = artifact(family, lookupKey);
                if (!Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)) {
                    Path alias = alias(family, lookupKey);
                    if (!Files.isRegularFile(alias, LinkOption.NOFOLLOW_LINKS)) {
                        return false;
                    }
                    String target = Files.readString(alias, StandardCharsets.US_ASCII);
                    if (!target.matches(KEY_PATTERN)) {
                        return false;
                    }
                    artifact = artifact(family, target);
                    if (!Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)) {
                        return false;
                    }
                }
                atomicCopy(artifact, output);
                try {
                    Files.setLastModifiedTime(artifact, FileTime.fromMillis(System.currentTimeMillis()));
                } catch (IOException | SecurityException e) {
                    LOGGER.warn("Could not update extension cache access time {}", artifact, e);
                }
                return true;
            } catch (IOException | SecurityException e) {
                LOGGER.warn("Could not restore extension cache {}, continuing with compilation", family, e);
                return false;
            }
        }
    }

    public boolean buildOrRestore(String family, String directKey, String effectiveKey, Path output, BuildAction build)
            throws IOException, InterruptedException, ExtenderException {
        if (!enabled() || directKey == null) {
            build.run();
            return false;
        }
        String artifactKey = effectiveKey == null ? directKey : effectiveKey;
        String lockKey = familyDirectory(family).resolve(checkedKey(artifactKey)).toString();
        BuildLock buildLock = builds.compute(lockKey, (key, current) -> {
            BuildLock selected = current == null ? new BuildLock() : current;
            selected.users++;
            return selected;
        });
        boolean locked = false;
        try {
            buildLock.lock.lockInterruptibly();
            locked = true;
            if (restore(family, directKey, output)) {
                return true;
            }
            if (effectiveKey != null && restore(family, effectiveKey, output)) {
                synchronized (storageLock) {
                    publishAlias(family, directKey, artifactKey);
                    trimAfterWrite(family);
                }
                return true;
            }
            build.run();
            store(family, directKey, artifactKey, output);
            return false;
        } finally {
            if (locked) {
                buildLock.lock.unlock();
            }
            builds.computeIfPresent(lockKey, (key, current) -> --current.users == 0 ? null : current);
        }
    }

    @FunctionalInterface
    public interface BuildAction {
        void run() throws IOException, InterruptedException, ExtenderException;
    }

    private static final class BuildLock {
        private final ReentrantLock lock = new ReentrantLock();
        private int users;
    }

    private void store(String family, String directKey, String artifactKey, Path output) {
        synchronized (storageLock) {
            try {
                if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) || Files.size(output) > maxSizeBytes) {
                    return;
                }
                atomicCopy(output, artifact(family, artifactKey));
                publishAlias(family, directKey, artifactKey);
                trimAfterWrite(family);
            } catch (IOException | UncheckedIOException | SecurityException e) {
                LOGGER.warn("Could not store extension cache {}", family, e);
            }
        }
    }

    private void publishAlias(String family, String directKey, String artifactKey) {
        if (directKey.equals(artifactKey)) {
            return;
        }
        Path temporary = null;
        try {
            Path target = alias(family, directKey);
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), "publish-", ".tmp");
            Files.writeString(temporary, artifactKey, StandardCharsets.US_ASCII);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | SecurityException e) {
            LOGGER.warn("Could not publish extension cache alias {}", family, e);
        } finally {
            deleteTemporary(temporary);
        }
    }

    private void trimAfterWrite(String family) {
        try {
            trim();
        } catch (IOException | UncheckedIOException | SecurityException e) {
            LOGGER.warn("Could not trim extension cache {}", family, e);
        }
    }

    // Only cold stores scan the namespace. Hits copy an archive and update its LRU timestamp.
    private void trim() throws IOException {
        List<Path> entries;
        try (Stream<Path> files = Files.walk(directory)) {
            entries = files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().matches(KEY_PATTERN + "\\.(a|ref)"))
                    .toList();
        }
        long size = 0;
        List<Path> archives = new ArrayList<>();
        for (Path entry : entries) {
            size += Files.size(entry);
            if (entry.getFileName().toString().endsWith(".a")) {
                archives.add(entry);
            }
        }
        archives.sort(Comparator.comparingLong(path -> path.toFile().lastModified()));
        for (Path archive : archives) {
            if (size <= maxSizeBytes) {
                break;
            }
            size -= Files.size(archive);
            Files.delete(archive);
            String key = archive.getFileName().toString().replace(".a", "");
            for (Path entry : entries) {
                if (entry.getParent().equals(archive.getParent()) && entry.getFileName().toString().endsWith(".ref")
                        && Files.exists(entry) && Files.readString(entry, StandardCharsets.US_ASCII).equals(key)) {
                    size -= Files.size(entry);
                    Files.delete(entry);
                }
            }
        }
    }

    private Path familyDirectory(String family) {
        return directory.resolve(checkedKey(family));
    }

    private Path artifact(String family, String key) {
        return familyDirectory(family).resolve(checkedKey(key) + ".a");
    }

    private Path alias(String family, String key) {
        return familyDirectory(family).resolve(checkedKey(key) + ".ref");
    }

    private static String checkedKey(String key) {
        if (key == null || !key.matches(KEY_PATTERN)) {
            throw new IllegalArgumentException("Extension cache keys must be SHA-256 hex strings");
        }
        return key;
    }

    private static void atomicCopy(Path source, Path target) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), "publish-", ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            deleteTemporary(temporary);
        }
    }

    private static void deleteTemporary(Path temporary) {
        if (temporary != null) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException | SecurityException e) {
                LOGGER.warn("Could not clean extension cache temporary file {}", temporary, e);
            }
        }
    }
}
