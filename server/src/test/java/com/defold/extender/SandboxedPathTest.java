package com.defold.extender;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class SandboxedPathTest {

    private File tempDir;

    @BeforeEach
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("sandboxed-path-test").toFile();
    }

    @AfterEach
    public void tearDown() {
        FileUtils.deleteQuietly(tempDir);
    }

    // --- resolve() tests ---

    @Test
    public void resolveNormalRelativePath() throws ExtenderException {
        File result = SandboxedPath.resolve(tempDir, "a/b/c.txt");
        Path expected = tempDir.toPath().resolve("a/b/c.txt").normalize();
        assertEquals(expected.toFile().getAbsolutePath(), result.getAbsolutePath());
    }

    @Test
    public void resolvePathWithDoubleDotStaysWithinRoot() throws ExtenderException {
        File result = SandboxedPath.resolve(tempDir, "a/b/../c.txt");
        Path expected = tempDir.toPath().resolve("a/c.txt").normalize();
        assertEquals(expected.toFile().getAbsolutePath(), result.getAbsolutePath());
    }

    @Test
    public void resolvePathTraversalEscapesRoot() {
        ExtenderException ex = assertThrows(ExtenderException.class, () ->
            SandboxedPath.resolve(tempDir, "../../etc/passwd"));
        assertTrue(ex.getMessage().contains("Path traversal detected"));
    }

    @Test
    public void resolveAbsolutePathRejected() {
        ExtenderException ex = assertThrows(ExtenderException.class, () ->
            SandboxedPath.resolve(tempDir, "/etc/passwd"));
        assertTrue(ex.getMessage().contains("Absolute paths are not allowed"));
    }

    @Test
    public void resolveEmptyPathRejected() {
        ExtenderException ex = assertThrows(ExtenderException.class, () ->
            SandboxedPath.resolve(tempDir, ""));
        assertTrue(ex.getMessage().contains("null or empty"));
    }

    @Test
    public void resolveNullPathRejected() {
        ExtenderException ex = assertThrows(ExtenderException.class, () ->
            SandboxedPath.resolve(tempDir, null));
        assertTrue(ex.getMessage().contains("null or empty"));
    }

    @Test
    public void resolveJustDoubleDotRejected() {
        assertThrows(ExtenderException.class, () ->
            SandboxedPath.resolve(tempDir, ".."));
    }

    @Test
    public void resolveDeepTraversalRejected() {
        assertThrows(ExtenderException.class, () ->
            SandboxedPath.resolve(tempDir, "a/b/c/../../../../etc/passwd"));
    }

    @Test
    public void resolveSymlinkOutsideRootRejected() throws IOException {
        // Create a symlink inside tempDir that points outside
        File outsideDir = Files.createTempDirectory("outside").toFile();
        try {
            File secretFile = new File(outsideDir, "secret.txt");
            Files.writeString(secretFile.toPath(), "secret");

            Path symlink = tempDir.toPath().resolve("link");
            Files.createSymbolicLink(symlink, outsideDir.toPath());

            assertThrows(ExtenderException.class, () ->
                SandboxedPath.resolve(tempDir, "link/secret.txt"));
        } finally {
            FileUtils.deleteQuietly(outsideDir);
        }
    }

    // --- assertWithin() tests ---

    @Test
    public void assertWithinValidChild() throws ExtenderException {
        File child = new File(tempDir, "a/b.txt");
        SandboxedPath.assertWithin(tempDir, child); // should not throw
    }

    @Test
    public void assertWithinEscapedChild() {
        File child = new File(tempDir, "../../etc/passwd");
        assertThrows(ExtenderException.class, () ->
            SandboxedPath.assertWithin(tempDir, child));
    }

    // --- validateName() tests ---

    @Test
    public void validateNameAcceptsValid() throws ExtenderException {
        assertEquals("myext", SandboxedPath.validateName("myext"));
        assertEquals("my-ext", SandboxedPath.validateName("my-ext"));
        assertEquals("MyExt123", SandboxedPath.validateName("MyExt123"));
        assertEquals("a", SandboxedPath.validateName("a"));
        assertEquals("ext_name", SandboxedPath.validateName("ext_name"));
    }

    @Test
    public void validateNameRejectsPathTraversal() {
        assertThrows(ExtenderException.class, () ->
            SandboxedPath.validateName("../../evil"));
    }

    @Test
    public void validateNameRejectsSlash() {
        assertThrows(ExtenderException.class, () ->
            SandboxedPath.validateName("a/b"));
    }

    @Test
    public void validateNameRejectsBackslash() {
        assertThrows(ExtenderException.class, () ->
            SandboxedPath.validateName("a\\b"));
    }

    @Test
    public void validateNameRejectsEmpty() {
        assertThrows(ExtenderException.class, () ->
            SandboxedPath.validateName(""));
    }

    @Test
    public void validateNameRejectsNull() {
        assertThrows(ExtenderException.class, () ->
            SandboxedPath.validateName(null));
    }

    @Test
    public void validateNameRejectsDots() {
        assertThrows(ExtenderException.class, () ->
            SandboxedPath.validateName(".."));
    }

    @Test
    public void validateNameRejectsStartingWithDigit() {
        assertThrows(ExtenderException.class, () ->
            SandboxedPath.validateName("1abc"));
    }

    // --- Zip slip integration tests ---

    @Test
    public void unzipRejectsPathTraversalEntry() throws IOException {
        byte[] maliciousZip = createZipWithEntry("../../escape.txt", "malicious content");
        ByteArrayInputStream bais = new ByteArrayInputStream(maliciousZip);

        assertThrows(IOException.class, () ->
            ZipUtils.unzip(bais, tempDir.toPath()));

        // Verify the file was NOT created outside tempDir
        File escaped = new File(tempDir.getParentFile().getParentFile(), "escape.txt");
        assertTrue(!escaped.exists(), "File should not have been created outside sandbox");
    }

    @Test
    public void unzipAcceptsNormalEntry() throws IOException {
        byte[] normalZip = createZipWithEntry("normal/file.txt", "safe content");
        ByteArrayInputStream bais = new ByteArrayInputStream(normalZip);

        ZipUtils.unzip(bais, tempDir.toPath());

        File expected = new File(tempDir, "normal/file.txt");
        assertTrue(expected.exists(), "Normal file should have been extracted");
    }

    private static byte[] createZipWithEntry(String entryName, String content) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(content.getBytes());
            zos.closeEntry();
        }
        return baos.toByteArray();
    }
}
