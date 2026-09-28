package com.defold.extender.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FileCloneUtilTest {

    @TempDir
    Path tempDir;

    private Path createSourceTree() throws IOException {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src.resolve("a/b"));
        Files.createDirectories(src.resolve("empty"));
        Files.writeString(src.resolve("root.txt"), "root");
        Files.writeString(src.resolve("a/b/nested.txt"), "nested");
        Files.createSymbolicLink(src.resolve("link"), Path.of("a/b/nested.txt"));
        Files.createSymbolicLink(src.resolve("dirlink"), Path.of("a"));
        return src;
    }

    @Test
    public void testCloneTreeCopiesFilesDirsAndSymlinks() throws IOException {
        Path src = createSourceTree();
        Path dst = tempDir.resolve("dst");

        FileCloneUtil.cloneTree(src, dst, false);

        assertEquals("root", Files.readString(dst.resolve("root.txt")));
        assertEquals("nested", Files.readString(dst.resolve("a/b/nested.txt")));
        assertTrue(Files.isDirectory(dst.resolve("empty")));
        assertTrue(Files.isSymbolicLink(dst.resolve("link")));
        assertEquals(Path.of("a/b/nested.txt"), Files.readSymbolicLink(dst.resolve("link")));
        assertTrue(Files.isSymbolicLink(dst.resolve("dirlink")));
        assertEquals(Path.of("a"), Files.readSymbolicLink(dst.resolve("dirlink")));
    }

    @Test
    public void testCloneTreeIntoExistingDirFailsOnExistingFile() throws IOException {
        Path src = createSourceTree();
        Path dst = tempDir.resolve("dst");
        Files.createDirectories(dst);
        Files.writeString(dst.resolve("root.txt"), "old");

        assertThrows(FileAlreadyExistsException.class, () -> FileCloneUtil.cloneTree(src, dst, false));
    }

    @Test
    public void testCloneTreeSkipExistingKeepsExistingFiles() throws IOException {
        Path src = createSourceTree();
        Path dst = tempDir.resolve("dst");
        Files.createDirectories(dst.resolve("a/b"));
        Files.writeString(dst.resolve("root.txt"), "shared");
        Files.writeString(dst.resolve("a/b/other.txt"), "other");

        FileCloneUtil.cloneTree(src, dst, true);

        assertEquals("shared", Files.readString(dst.resolve("root.txt")));
        assertEquals("other", Files.readString(dst.resolve("a/b/other.txt")));
        assertEquals("nested", Files.readString(dst.resolve("a/b/nested.txt")));
        assertTrue(Files.isSymbolicLink(dst.resolve("link")));
    }
}
