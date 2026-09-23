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
