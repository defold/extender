package com.defold.extender.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RealGradleServiceTest {
    @Test
    public void testBuildTemplateUsesProcessedAndroidArtifacts() throws Exception {
        String template;
        try (InputStream input = RealGradleServiceTest.class.getResourceAsStream(
                "/template.build.gradle")) {
            assertNotNull(input);
            template = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(template.contains("AndroidArtifacts.ArtifactType.PROCESSED_AAR.type"));
        assertTrue(template.contains("AndroidArtifacts.ArtifactType.PROCESSED_JAR.type"));
        assertTrue(template.contains("project.findProperty(\"android.enableJetifier\")"));
        assertTrue(template.contains("if (!useJetifier)"));
        assertTrue(template.contains("getResolvedConfiguration().getResolvedArtifacts()"));
        assertTrue(template.contains("instanceof ModuleComponentIdentifier"));
        assertEquals(2, template.split("componentFilter", -1).length - 1);
        assertFalse(template.contains("findAll(isModuleArtifact)"));
        assertTrue(template.contains("aarArtifactKeys"));
        assertTrue(template.contains("getOriginalFileName"));
        assertTrue(template.contains("identifier.originalFileName"));
        assertTrue(template.contains("artifact.file.name"));
        assertFalse(template.contains("aarComponentIds"));
        assertFalse(template.contains("Unsupported non-module Android dependency"));
    }

    @Test
    public void testProcessedDependencyCachesAreSeparatedByJetifierAndAgpVersion() {
        String jetified = RealGradleService.getDependencyCacheNamespace("8.13.0", true);
        String plain = RealGradleService.getDependencyCacheNamespace("8.13.0", false);
        String otherAgp = RealGradleService.getDependencyCacheNamespace("8.14.0", true);

        assertNotEquals(jetified, plain);
        assertNotEquals(jetified, otherAgp);
        assertTrue(jetified.contains("processed-v1"));
        assertTrue(jetified.endsWith("jetified"));
        assertTrue(plain.endsWith("plain"));
    }

    @Test
    public void testProcessedDependencyCacheNamespaceIsPathSafe() {
        String namespace = RealGradleService.getDependencyCacheNamespace("8.13.0/../../raw", true);

        assertFalse(namespace.contains("/"));
        assertFalse(namespace.contains("\\"));
        assertFalse(namespace.contains(".."));
    }

    @Test
    public void testDependencyCacheNameIncludesProcessedArtifactContent(@TempDir Path temporaryDirectory)
            throws Exception {
        Path first = temporaryDirectory.resolve("first.jar");
        Path second = temporaryDirectory.resolve("second.jar");
        Path duplicate = temporaryDirectory.resolve("duplicate.jar");
        Files.writeString(first, "first processed artifact");
        Files.writeString(second, "second processed artifact");
        Files.writeString(duplicate, "first processed artifact");

        String firstName = RealGradleService.getDependencyCacheName(
                first.toFile(),
                ".jar");
        String secondName = RealGradleService.getDependencyCacheName(
                second.toFile(),
                ".jar");
        String duplicateName = RealGradleService.getDependencyCacheName(
                duplicate.toFile(),
                ".jar");

        assertNotEquals(firstName, secondName);
        assertEquals(firstName, duplicateName);
        assertTrue(firstName.matches("[0-9a-f]{64}\\.jar"));
    }

    @Test
    public void testPublishCacheEntryKeepsExistingFileAndDirectory(@TempDir Path temporaryDirectory)
            throws Exception {
        Path fileTarget = temporaryDirectory.resolve("winner.jar");
        Path fileTemporary = temporaryDirectory.resolve("loser.jar.tmp");
        Files.writeString(fileTarget, "winner");
        Files.writeString(fileTemporary, "loser");

        RealGradleService.publishCacheEntry(fileTemporary, fileTarget);

        assertEquals("winner", Files.readString(fileTarget));
        assertFalse(Files.exists(fileTemporary));

        Path directoryTarget = temporaryDirectory.resolve("winner.aar");
        Path directoryTemporary = temporaryDirectory.resolve("loser.aar.tmp");
        Files.createDirectory(directoryTarget);
        Files.writeString(directoryTarget.resolve("winner"), "winner");
        Files.createDirectory(directoryTemporary);
        Files.writeString(directoryTemporary.resolve("loser"), "loser");

        RealGradleService.publishCacheEntry(directoryTemporary, directoryTarget);

        assertTrue(Files.exists(directoryTarget.resolve("winner")));
        assertFalse(Files.exists(directoryTemporary));
    }

    @Test
    public void testPublishCacheEntryRethrowsUnexpectedMoveFailure(@TempDir Path temporaryDirectory)
            throws Exception {
        Path temporary = temporaryDirectory.resolve("dependency.jar.tmp");
        Path target = temporaryDirectory.resolve("missing-parent/dependency.jar");
        Files.writeString(temporary, "dependency");

        assertThrows(
                IOException.class,
                () -> RealGradleService.publishCacheEntry(temporary, target));
        assertFalse(Files.exists(temporary));
        assertFalse(Files.exists(target));
    }

    @Test
    public void testConcurrentDirectoryPublishAcceptsContentAddressedWinner(@TempDir Path temporaryDirectory)
            throws Exception {
        Path first = temporaryDirectory.resolve("first.aar.tmp");
        Path second = temporaryDirectory.resolve("second.aar.tmp");
        Path target = temporaryDirectory.resolve("dependency.aar");
        Files.createDirectory(first);
        Files.writeString(first.resolve("content"), "same processed artifact");
        Files.createDirectory(second);
        Files.writeString(second.resolve("content"), "same processed artifact");

        CyclicBarrier beforeMove = new CyclicBarrier(2);
        Runnable awaitBothWriters = () -> {
            try {
                beforeMove.await();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstPublish = executor.submit(() -> {
                RealGradleService.publishCacheEntry(first, target, awaitBothWriters);
                return null;
            });
            Future<?> secondPublish = executor.submit(() -> {
                RealGradleService.publishCacheEntry(second, target, awaitBothWriters);
                return null;
            });

            firstPublish.get();
            secondPublish.get();
        } finally {
            executor.shutdownNow();
        }

        assertEquals("same processed artifact", Files.readString(target.resolve("content")));
        assertFalse(Files.exists(first));
        assertFalse(Files.exists(second));
    }
}
