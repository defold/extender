package com.defold.extender.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.defold.extender.ZipUtils;

public class JobFilesTest {

    @Test
    public void writeReplacesAPlantedLinkInsteadOfFollowingIt(@TempDir Path root) throws IOException {
        Path job = Files.createDirectory(root.resolve("job"));
        Path victim = Files.writeString(root.resolve("user.env"), "ORIGINAL");
        Path log = job.resolve("log.txt");
        Files.createSymbolicLink(log, victim);

        JobFiles.write(job, log, "build output".getBytes(StandardCharsets.UTF_8));

        assertEquals("ORIGINAL", Files.readString(victim));
        assertFalse(Files.isSymbolicLink(log));
        assertEquals("build output", Files.readString(log));
    }

    @Test
    public void writeDoesNotWriteThroughAHardLink(@TempDir Path root) throws IOException {
        Path job = Files.createDirectory(root.resolve("job"));
        Path victim = Files.writeString(root.resolve("victim"), "ORIGINAL");
        Path log = job.resolve("log.txt");
        Files.createLink(log, victim);

        JobFiles.write(job, log, "x".getBytes(StandardCharsets.UTF_8));

        assertEquals("ORIGINAL", Files.readString(victim));
    }

    @Test
    public void writeRefusesADirectoryLinkedOutOfTheRoot(@TempDir Path root) throws IOException {
        Path job = Files.createDirectory(root.resolve("job"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.createSymbolicLink(job.resolve("spm"), outside);

        assertThrows(IOException.class,
                () -> JobFiles.write(job, job.resolve("spm/build.log"), new byte[] {1}));
        assertFalse(Files.exists(outside.resolve("build.log")));
    }

    @Test
    public void copyRefusesALinkOutOfTheRootAndKeepsInternalOnes(@TempDir Path root) throws IOException {
        Path job = Files.createDirectory(root.resolve("job"));
        Path secret = Files.writeString(root.resolve("credentials.json"), "SECRET");
        Path framework = Files.createDirectories(job.resolve("Pods/Foo.framework/Versions/A"));
        Files.writeString(framework.resolve("Foo"), "binary");
        Files.createSymbolicLink(job.resolve("Pods/Foo.framework/Versions/Current"), Path.of("A"));
        Path target = job.resolve("out");

        JobFiles.copyDirectory(job.toFile(), job.resolve("Pods").toFile(), target.toFile(), null);
        assertEquals("binary", Files.readString(target.resolve("Foo.framework/Versions/Current/Foo")));

        Files.createSymbolicLink(framework.resolve("leak.txt"), secret);
        assertThrows(IOException.class,
                () -> JobFiles.copyDirectory(job.toFile(), job.resolve("Pods").toFile(), job.resolve("out2").toFile(), null));
        assertThrows(IOException.class,
                () -> JobFiles.copyFile(job.toFile(), framework.resolve("leak.txt").toFile(), job.resolve("leak").toFile()));
    }

    @Test
    public void copyFileRefusesATargetDirectoryLinkedOutOfTheJob(@TempDir Path root) throws IOException {
        Path job = Files.createDirectory(root.resolve("job"));
        Path sdk = Files.createDirectory(root.resolve("sdk"));
        Path header = Files.writeString(job.resolve("foo.h"), "planted");
        Files.createDirectories(job.resolve("build/Foo.framework"));
        Files.createSymbolicLink(job.resolve("build/Foo.framework/Headers"), sdk);

        assertThrows(IOException.class, () -> JobFiles.copyFileToDirectory(job.toFile(), header.toFile(),
                job.resolve("build/Foo.framework/Headers").toFile()));
        assertThrows(IOException.class, () -> JobFiles.copyFile(job.toFile(), header.toFile(),
                job.resolve("build/Foo.framework/Headers/include/foo.h").toFile()));
        assertFalse(Files.exists(sdk.resolve("foo.h")));
        assertFalse(Files.exists(sdk.resolve("include")));
    }

    @Test
    public void copyFileRefusesADanglingLinkOnTheWayToTheTarget(@TempDir Path root) throws IOException {
        Path job = Files.createDirectory(root.resolve("job"));
        Path header = Files.writeString(job.resolve("foo.h"), "planted");
        Files.createSymbolicLink(job.resolve("jni"), root.resolve("sdk"));

        assertThrows(IOException.class,
                () -> JobFiles.copyFile(job.toFile(), header.toFile(), job.resolve("jni/lib/foo.h").toFile()));
        assertFalse(Files.exists(root.resolve("sdk")));
    }

    @Test
    public void copyDirectoryRefusesALinkPlantedInTheTargetTree(@TempDir Path root) throws IOException {
        Path job = Files.createDirectory(root.resolve("job"));
        Path sdk = Files.createDirectory(root.resolve("sdk"));
        Path source = Files.createDirectories(job.resolve("ext/res/values"));
        Files.writeString(source.resolve("strings.xml"), "planted");
        Path target = Files.createDirectories(job.resolve("build/res"));
        Files.createSymbolicLink(target.resolve("values"), sdk);

        assertThrows(IOException.class,
                () -> JobFiles.copyDirectory(job.toFile(), job.resolve("ext/res").toFile(), target.toFile(), null));
        assertFalse(Files.exists(sdk.resolve("strings.xml")));

        Files.createSymbolicLink(job.resolve("build/jni"), sdk);
        assertThrows(IOException.class,
                () -> JobFiles.copyDirectory(job.toFile(), job.resolve("ext/res").toFile(), job.resolve("build/jni").toFile(), null));
        assertFalse(Files.exists(sdk.resolve("values")));
    }

    @Test
    public void copyDirectoryChecksASourceRootAndTheJobSeparately(@TempDir Path root) throws IOException {
        Path job = Files.createDirectory(root.resolve("job"));
        Path cache = Files.createDirectory(root.resolve("gradle"));
        Path jni = Files.createDirectories(cache.resolve("transforms/foo/jni/arm64-v8a"));
        Files.writeString(jni.resolve("libfoo.so"), "lib");
        Path target = job.resolve("build/jni");

        JobFiles.copyDirectory(job.toFile(), cache.toFile(), cache.resolve("transforms/foo/jni").toFile(), target.toFile(), null);
        assertEquals("lib", Files.readString(target.resolve("arm64-v8a/libfoo.so")));

        Path sdk = Files.createDirectory(root.resolve("sdk"));
        Files.createSymbolicLink(job.resolve("build/jni2"), sdk);
        assertThrows(IOException.class, () -> JobFiles.copyDirectory(job.toFile(), cache.toFile(),
                cache.resolve("transforms/foo/jni").toFile(), job.resolve("build/jni2").toFile(), null));
        assertFalse(Files.exists(sdk.resolve("arm64-v8a")));
    }

    @Test
    public void createDirectoriesMakesTheMissingTail(@TempDir Path root) throws IOException {
        Path job = Files.createDirectory(root.resolve("job"));
        Path dir = job.resolve("Wrapper/Sources");

        JobFiles.createDirectories(job.toFile(), dir.toFile());

        assertTrue(Files.isDirectory(dir));
    }

    @Test
    public void createDirectoriesRefusesAnAncestorLinkedOutOfTheJob(@TempDir Path root) throws IOException {
        Path job = Files.createDirectory(root.resolve("job"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.createSymbolicLink(job.resolve("SwiftPackageManagerService"), outside);

        assertThrows(IOException.class, () -> JobFiles.createDirectories(job.toFile(),
                job.resolve("SwiftPackageManagerService/Package/Sources/SpmDeps").toFile()));
        assertFalse(Files.exists(outside.resolve("Package")));
    }

    @Test
    public void zipRefusesAnOutputLinkedOutOfTheBuildDirectory(@TempDir Path root) throws IOException {
        Path build = Files.createDirectory(root.resolve("build"));
        Path secret = Files.writeString(root.resolve("credentials.json"), "SECRET");
        File good = Files.writeString(build.resolve("engine"), "ok").toFile();
        Path leak = build.resolve("leak.txt");
        Files.createSymbolicLink(leak, secret);

        ZipUtils.zip(new ByteArrayOutputStream(), build.toFile(), List.of(good));
        IOException e = assertThrows(IOException.class,
                () -> ZipUtils.zip(new ByteArrayOutputStream(), build.toFile(), List.of(good, leak.toFile())));
        assertTrue(e.getMessage().contains("outside"), e.getMessage());
    }
}
