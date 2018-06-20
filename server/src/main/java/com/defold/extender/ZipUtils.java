package com.defold.extender;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class ZipUtils {

    public static void unzip(InputStream inputStream, Path targetDirectory) throws IOException {
        try (ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            ZipEntry zipEntry = zipInputStream.getNextEntry();

            while (zipEntry != null) {
                File entryTargetFile = new File(targetDirectory.toFile(), zipEntry.getName());

                if (zipEntry.isDirectory()) {
                    Files.createDirectories(entryTargetFile.toPath());
                } else {
                    File parentDir = entryTargetFile.getParentFile();
                    if (!parentDir.exists()) {
                        Files.createDirectories(parentDir.toPath());
                    }
                    Files.copy(zipInputStream, entryTargetFile.toPath());
                }

                zipInputStream.closeEntry();
                zipEntry = zipInputStream.getNextEntry();
            }
        }
    }

    public static void zip(OutputStream outputStream, List<File> filesToZip) throws IOException {
        ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream);

        for (File file : filesToZip) {
            zipOutputStream.putNextEntry(new ZipEntry(file.getName()));
            Files.copy(file.toPath(), zipOutputStream);
            zipOutputStream.closeEntry();
        }

        zipOutputStream.finish();
    }

    public static File zip(List<File> filesToZip, final String zipFilename) throws IOException {
        File zipFile = new File(zipFilename);

        try (FileOutputStream fileOutputStream = new FileOutputStream(zipFile)) {
            ZipUtils.zip(fileOutputStream, filesToZip);
        }

        return zipFile;
    }
}
