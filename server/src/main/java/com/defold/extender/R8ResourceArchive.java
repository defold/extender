package com.defold.extender;

import com.android.aapt.Resources;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Validates the R8 resource archive and works around dropped dotted raw resources. */
final class R8ResourceArchive {
    private static final Logger LOGGER = LoggerFactory.getLogger(R8ResourceArchive.class);

    private R8ResourceArchive() {}

    // R8 8.13.19 guesses a resource name from the part of the filename before the first dot.
    // This can remove a dotted raw file even though its resource-table entry is still live.
    // Restore only these uncompiled files, using the exact paths in both resource tables.
    // Compiled XML must never be restored: R8 may have rewritten its class names or references.
    // https://github.com/defold/defold/issues/13398
    static void validateAndRestoreRawResources(File linkedResources, File optimizedResources)
            throws IOException, ExtenderException {
        Path repaired = null;
        try {
            try (ZipFile optimized = ZipFile.builder().setFile(optimizedResources).get()) {
                if (!hasFile(optimized, "AndroidManifest.xml") || !hasFile(optimized, "resources.pb")) {
                    throw new ExtenderException(
                            "R8 produced an invalid proto resource archive: expected AndroidManifest.xml and resources.pb");
                }
                Map<String, Boolean> references = readFileReferences(optimized);
                Set<String> missing = new TreeSet<>();
                for (String path : references.keySet()) {
                    if (!hasFile(optimized, path)) {
                        missing.add(path);
                    }
                }
                if (missing.isEmpty()) {
                    return;
                }

                try (ZipFile original = ZipFile.builder().setFile(linkedResources).get()) {
                    Map<String, Boolean> originalReferences = readFileReferences(original);
                    for (String path : missing) {
                        if (!references.get(path) || !Boolean.TRUE.equals(originalReferences.get(path))
                                || !hasFile(original, path)) {
                            throw new ExtenderException(
                                    "R8 resource table references missing file '" + path
                                            + "', which cannot be restored as a dotted raw resource from the linked archive");
                        }
                    }
                    repaired = Files.createTempFile(optimizedResources.toPath().getParent(), "r8-resources-", ".apk");
                    try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(repaired)) {
                        Enumeration<ZipArchiveEntry> entries = optimized.getEntriesInPhysicalOrder();
                        while (entries.hasMoreElements()) {
                            copyEntry(optimized, entries.nextElement(), output);
                        }
                        for (String path : missing) {
                            copyEntry(original, original.getEntry(path), output);
                        }
                    }
                    LOGGER.info("Restored {} dotted raw resource files omitted by R8: {}", missing.size(), missing);
                }
            }
            // Close all archives before replacing the output, including on Windows.
            Files.move(repaired, optimizedResources.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (repaired != null) {
                Files.deleteIfExists(repaired);
            }
        }
    }

    private static boolean hasFile(ZipFile archive, String path) {
        ZipArchiveEntry entry = archive.getEntry(path);
        return entry != null && !entry.isDirectory();
    }

    // A shared path is restorable only if all its references are to uncompiled dotted raw resources.
    private static Map<String, Boolean> readFileReferences(ZipFile archive) throws IOException {
        Map<String, Boolean> references = new TreeMap<>();
        ZipArchiveEntry tableEntry = archive.getEntry("resources.pb");
        if (tableEntry == null || tableEntry.isDirectory()) {
            throw new IOException("Missing resources.pb in " + archive);
        }
        try (InputStream input = archive.getInputStream(tableEntry)) {
            Resources.ResourceTable table = Resources.ResourceTable.parseFrom(input);
            for (Resources.Package resourcePackage : table.getPackageList()) {
                for (Resources.Type type : resourcePackage.getTypeList()) {
                    for (Resources.Entry entry : type.getEntryList()) {
                        for (Resources.ConfigValue config : entry.getConfigValueList()) {
                            Resources.Item item = config.getValue().getItem();
                            if (item.hasFile()) {
                                Resources.FileReference file = item.getFile();
                                boolean restorable = type.getName().equals("raw")
                                        && entry.getName().contains(".")
                                        && file.getType() == Resources.FileReference.Type.UNKNOWN;
                                references.merge(file.getPath(), restorable, (left, right) -> left && right);
                            }
                        }
                    }
                }
            }
        }
        return references;
    }

    private static void copyEntry(ZipFile source, ZipArchiveEntry entry, ZipArchiveOutputStream output)
            throws IOException {
        try (InputStream input = source.getRawInputStream(entry)) {
            output.addRawArchiveEntry(new ZipArchiveEntry(entry), input);
        }
    }
}
