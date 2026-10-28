package com.defold.extender.utils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class PodBuildUtilTest {

    private static File overlay(Path dir, String name, String rootName) throws IOException {
        File file = dir.resolve(name).toFile();
        Files.writeString(file.toPath(), String.format(
                "{\"roots\":[{\"name\":\"%s\",\"type\":\"directory\",\"contents\":[]}],"
                        + "\"case-sensitive\":\"false\",\"version\":0}",
                rootName));
        return file;
    }

    @Test
    public void mergeVFSOverlaysCombinesBothRootsArrays(@TempDir Path jobDir) throws IOException {
        File overlayA = overlay(jobDir, "a.yaml", "a");
        File overlayB = overlay(jobDir, "b.yaml", "b");

        File merged = PodBuildUtil.mergeVFSOverlays(jobDir.toFile(), overlayA, overlayB);

        String content = Files.readString(merged.toPath());
        assertTrue(content.contains("\"a\""), content);
        assertTrue(content.contains("\"b\""), content);
    }

    @Test
    public void mergeVFSOverlaysThrowsOnMalformedJsonInsteadOfSwallowingIt(@TempDir Path jobDir) throws IOException {
        File overlayA = jobDir.resolve("a.yaml").toFile();
        Files.writeString(overlayA.toPath(), "not json");
        File overlayB = overlay(jobDir, "b.yaml", "b");

        assertThrows(IOException.class, () -> PodBuildUtil.mergeVFSOverlays(jobDir.toFile(), overlayA, overlayB));
    }

    @Test
    public void mergeVFSOverlaysThrowsInsteadOfSwallowingASymlinkEscape(@TempDir Path root) throws IOException {
        Path jobDir = Files.createDirectory(root.resolve("job"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        // an earlier step of the same job replaced a job-relative directory with a link out
        Files.createSymbolicLink(jobDir.resolve("escape"), outside);
        File overlayA = overlay(jobDir.resolve("escape"), "a.yaml", "a");
        File overlayB = overlay(jobDir, "b.yaml", "b");

        assertThrows(IOException.class, () -> PodBuildUtil.mergeVFSOverlays(jobDir.toFile(), overlayA, overlayB));
    }
}
