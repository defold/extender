package com.defold.extender;

import com.android.aapt.Resources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R8ResourceArchiveTest {
    private static final String KEPT = "res/raw/com.example.keep.example.keep.xml";
    private static final String UNUSED = "res/raw/com.example.unused.example.unused.bin";
    private static final String QUALIFIED = "res/raw-fr/com.example.data.example.data.bin";
    private static final byte[] CONTENT = "original raw payload".getBytes(StandardCharsets.UTF_8);

    private static Resources.Entry entry(String name, String path, Resources.FileReference.Type fileType) {
        return Resources.Entry.newBuilder().setName(name)
                .addConfigValue(Resources.ConfigValue.newBuilder().setValue(Resources.Value.newBuilder()
                        .setItem(Resources.Item.newBuilder().setFile(Resources.FileReference.newBuilder()
                                .setPath(path).setType(fileType)))))
                .build();
    }

    private static byte[] table(String type, Resources.Entry... entries) {
        return Resources.ResourceTable.newBuilder()
                .addPackage(Resources.Package.newBuilder().setPackageName("com.example")
                        .setPackageId(Resources.PackageId.newBuilder().setId(127))
                        .addType(Resources.Type.newBuilder().setName(type)
                                .setTypeId(Resources.TypeId.newBuilder().setId(1))
                                .addAllEntry(List.of(entries))))
                .build().toByteArray();
    }

    private static Resources.Entry raw(String name, String path) {
        return entry(name, path, Resources.FileReference.Type.UNKNOWN);
    }

    private static Path archive(Path path, byte[] table, Map<String, byte[]> resources, int method) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("AndroidManifest.xml", new byte[]{1});
        files.put("resources.pb", table);
        files.putAll(resources);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                ZipEntry entry = new ZipEntry(file.getKey());
                entry.setMethod(method);
                entry.setSize(file.getValue().length);
                CRC32 crc = new CRC32();
                crc.update(file.getValue());
                entry.setCrc(crc.getValue());
                output.putNextEntry(entry);
                output.write(file.getValue());
                output.closeEntry();
            }
        }
        return path;
    }

    // Verifies #13398 restoration follows surviving table references, preserves ZIP storage, and never revives unused files.
    @ParameterizedTest
    @ValueSource(ints = {ZipEntry.STORED, ZipEntry.DEFLATED})
    void restoresOnlyReferencedDottedRawFiles(int method, @TempDir Path temporary) throws Exception {
        Resources.Entry kept = raw("com.example.keep", KEPT);
        Resources.Entry qualified = raw("com.example.data", QUALIFIED);
        Resources.Entry unused = raw("com.example.unused", UNUSED);
        Path original = archive(temporary.resolve("original.apk"), table("raw", kept, qualified, unused),
                Map.of(KEPT, CONTENT, QUALIFIED, CONTENT, UNUSED, CONTENT, "res/raw/existing.bin", CONTENT), method);
        byte[] originalBytes = Files.readAllBytes(original);
        byte[] shrunkTable = table("raw", kept, qualified);
        byte[] existing = "R8 output must remain unchanged".getBytes(StandardCharsets.UTF_8);
        Path optimized = archive(temporary.resolve("optimized.apk"), shrunkTable,
                Map.of("res/raw/existing.bin", existing), ZipEntry.DEFLATED);

        R8ResourceArchive.validateAndRestoreRawResources(original.toFile(), optimized.toFile());

        assertArrayEquals(originalBytes, Files.readAllBytes(original));
        try (ZipFile result = new ZipFile(optimized.toFile())) {
            assertArrayEquals(shrunkTable, result.getInputStream(result.getEntry("resources.pb")).readAllBytes());
            assertArrayEquals(existing, result.getInputStream(result.getEntry("res/raw/existing.bin")).readAllBytes());
            for (String path : List.of(KEPT, QUALIFIED)) {
                ZipEntry restored = result.getEntry(path);
                assertNotNull(restored, path);
                assertEquals(method, restored.getMethod(), path);
                assertArrayEquals(CONTENT, result.getInputStream(restored).readAllBytes(), path);
            }
            assertNull(result.getEntry(UNUSED));
        }
        try (var files = Files.list(temporary)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith("r8-resources-")));
        }
    }

    // Verifies an archive already consistent with its resource table is returned byte-for-byte unchanged.
    @Test
    void leavesValidArchiveUnchanged(@TempDir Path temporary) throws Exception {
        Path optimized = archive(temporary.resolve("optimized.apk"), table("raw", raw("com.example.keep", KEPT)),
                Map.of(KEPT, CONTENT), ZipEntry.DEFLATED);
        byte[] before = Files.readAllBytes(optimized);

        R8ResourceArchive.validateAndRestoreRawResources(temporary.resolve("unused-input.apk").toFile(), optimized.toFile());

        assertArrayEquals(before, Files.readAllBytes(optimized));
    }

    // Verifies unexpected missing files fail without overwriting either archive or copying potentially rewritten compiled XML.
    @ParameterizedTest
    @ValueSource(strings = {"not-raw", "compiled-xml", "not-dotted", "missing-original-file", "not-originally-referenced", "original-compiled-xml"})
    void rejectsMissingFilesOutsideTheWorkaround(String failure, @TempDir Path temporary) throws Exception {
        String type = failure.equals("not-raw") ? "layout" : "raw";
        String name = failure.equals("not-dotted") ? "keep" : "com.example.keep";
        Resources.FileReference.Type outputType = failure.equals("compiled-xml")
                ? Resources.FileReference.Type.PROTO_XML : Resources.FileReference.Type.UNKNOWN;
        Resources.FileReference.Type inputType = failure.equals("original-compiled-xml")
                ? Resources.FileReference.Type.PROTO_XML : outputType;
        byte[] inputTable = failure.equals("not-originally-referenced")
                ? table(type) : table(type, entry(name, KEPT, inputType));
        Path original = archive(temporary.resolve("original.apk"), inputTable,
                failure.equals("missing-original-file") ? Map.of() : Map.of(KEPT, CONTENT), ZipEntry.DEFLATED);
        Path optimized = archive(temporary.resolve("optimized.apk"), table(type, entry(name, KEPT, outputType)),
                Map.of(), ZipEntry.DEFLATED);
        byte[] originalBefore = Files.readAllBytes(original);
        byte[] optimizedBefore = Files.readAllBytes(optimized);

        ExtenderException error = assertThrows(ExtenderException.class,
                () -> R8ResourceArchive.validateAndRestoreRawResources(original.toFile(), optimized.toFile()));

        assertTrue(error.getMessage().contains(KEPT));
        assertArrayEquals(originalBefore, Files.readAllBytes(original));
        assertArrayEquals(optimizedBefore, Files.readAllBytes(optimized));
    }
}
