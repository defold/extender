package com.defold.extender.cache;

import com.defold.extender.ExtenderException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ExtensionCompileCacheTest {
    private static final String FAMILY = "1".repeat(64);
    private static final String DIRECT = "2".repeat(64);
    private static final String EFFECTIVE = "3".repeat(64);

    @TempDir
    Path temporary;

    @Test
    public void requiresAnExplicitSwitchForEachPlatform() {
        MockEnvironment environment = new MockEnvironment();
        ExtensionCompileCache cache = new ExtensionCompileCache(temporary.toString(), "toolchain", 1024, 1000, environment);
        assertFalse(cache.enabledForPlatform("arm64-android"));
        assertFalse(cache.enabledForPlatform("wasm-web"));
        assertFalse(cache.enabledForPlatform("wasm_pthread-web"));

        environment.setProperty("extender.extension-cache.platforms.wasm-web", "true");
        assertTrue(cache.enabledForPlatform("wasm-web"));
        assertFalse(cache.enabledForPlatform("arm64-android"));
        assertFalse(cache.enabledForPlatform("wasm_pthread-web"));

        environment.setProperty("extender.extension-cache.platforms.wasm-web", "false");
        environment.setProperty("extender.extension-cache.platforms.wasm_pthread-web", "true");
        assertFalse(cache.enabledForPlatform("wasm-web"));
        assertTrue(cache.enabledForPlatform("wasm_pthread-web"));
    }

    @Test
    public void restoresAnArchiveAcrossJobsAndServiceRecreation() throws Exception {
        ExtensionCompileCache cache = cache(1024);
        Path first = temporary.resolve("job-a/libExtension.a");
        Files.createDirectories(first.getParent());
        assertFalse(cache.buildOrRestore(FAMILY, DIRECT, null, first,
                () -> Files.writeString(first, "compiled archive")));

        ExtensionCompileCache restarted = cache(1024);
        Path second = temporary.resolve("job-b/libExtension_123.a");
        assertTrue(restarted.buildOrRestore(FAMILY, DIRECT, null, second,
                () -> { throw new AssertionError("The second project must reuse the archive"); }));
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
    }

    @Test
    public void remembersAnotherEnvironmentWithTheSameEffectiveInput() throws Exception {
        ExtensionCompileCache cache = cache(1024);
        Path first = temporary.resolve("first.a");
        cache.buildOrRestore(FAMILY, DIRECT, EFFECTIVE, first, () -> Files.writeString(first, "archive"));
        String anotherDirect = "4".repeat(64);
        Path second = temporary.resolve("second.a");
        assertTrue(cache.buildOrRestore(FAMILY, anotherDirect, EFFECTIVE, second,
                () -> { throw new AssertionError("The preprocessed fingerprint already has an archive"); }));
        assertTrue(cache(1024).restore(FAMILY, anotherDirect, temporary.resolve("third.a")));
    }

    @Test
    public void concurrentJobsShareOneSuccessfulCompilation() throws Exception {
        ExtensionCompileCache cache = cache(1024);
        AtomicInteger compilations = new AtomicInteger();
        CountDownLatch compiling = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Path first = temporary.resolve("first.a");
            Future<Boolean> firstJob = executor.submit(() -> cache.buildOrRestore(FAMILY, DIRECT, EFFECTIVE, first, () -> {
                compilations.incrementAndGet();
                compiling.countDown();
                assertTrue(finish.await(5, TimeUnit.SECONDS));
                Files.writeString(first, "archive");
            }));
            assertTrue(compiling.await(5, TimeUnit.SECONDS));
            Path second = temporary.resolve("second.a");
            Future<Boolean> secondJob = executor.submit(() -> cache.buildOrRestore(FAMILY, DIRECT, EFFECTIVE, second,
                    () -> { compilations.incrementAndGet(); Files.writeString(second, "unexpected archive"); }));
            finish.countDown();
            assertFalse(firstJob.get(5, TimeUnit.SECONDS));
            assertTrue(secondJob.get(5, TimeUnit.SECONDS));
            assertEquals(1, compilations.get());
        } finally {
            finish.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void failedCompilationCanBeRetried() throws Exception {
        ExtensionCompileCache cache = cache(1024);
        Path output = temporary.resolve("retry.a");
        assertThrows(ExtenderException.class, () -> cache.buildOrRestore(FAMILY, DIRECT, null, output,
                () -> { throw new ExtenderException("compiler error"); }));
        assertFalse(cache.restore(FAMILY, DIRECT, output));
        assertFalse(cache.buildOrRestore(FAMILY, DIRECT, null, output, () -> Files.writeString(output, "archive")));
        assertTrue(cache.restore(FAMILY, DIRECT, temporary.resolve("restored.a")));
    }

    @Test
    public void evictsTheLeastRecentlyUsedArchive() throws Exception {
        ExtensionCompileCache cache = cache(12);
        Path first = temporary.resolve("first.a");
        cache.buildOrRestore(FAMILY, DIRECT, null, first, () -> Files.writeString(first, "12345678"));
        Path cachedFirst = temporary.resolve("cache/native-archives-v1").resolve(FAMILY).resolve(DIRECT + ".a");
        Files.setLastModifiedTime(cachedFirst, FileTime.fromMillis(1));
        Path second = temporary.resolve("second.a");
        cache.buildOrRestore(FAMILY, EFFECTIVE, null, second, () -> Files.writeString(second, "abcdefgh"));
        assertFalse(cache.restore(FAMILY, DIRECT, temporary.resolve("old.a")));
        assertTrue(cache.restore(FAMILY, EFFECTIVE, temporary.resolve("new.a")));
    }

    @Test
    public void disabledAndOversizedCachesStillProduceTheRequestedArchive() throws Exception {
        MockEnvironment environment = new MockEnvironment().withProperty("extender.extension-cache.platforms.arm64-android", "true");
        ExtensionCompileCache disabled = new ExtensionCompileCache("", "toolchain", 1024, 1000, environment);
        assertFalse(disabled.enabledForPlatform("arm64-android"));
        Path output = temporary.resolve("disabled.a");
        assertFalse(disabled.buildOrRestore(FAMILY, DIRECT, null, output, () -> Files.writeString(output, "archive")));
        assertEquals("archive", Files.readString(output));
        ExtensionCompileCache small = cache(1);
        assertFalse(small.buildOrRestore(FAMILY, DIRECT, null, output, () -> Files.writeString(output, "archive")));
        assertFalse(small.restore(FAMILY, DIRECT, temporary.resolve("oversized-hit.a")));
    }

    private ExtensionCompileCache cache(long size) {
        return new ExtensionCompileCache(temporary.resolve("cache").toString(), "android-ndk25", size, 1000,
                new MockEnvironment());
    }
}
