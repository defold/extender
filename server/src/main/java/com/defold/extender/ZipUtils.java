package com.defold.extender;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class ZipUtils {

    private static int bufferSize = 128 * 1024;

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
                    extractFile(zipInputStream, entryTargetFile);
                }

                zipInputStream.closeEntry();
                zipEntry = zipInputStream.getNextEntry();
            }
        }
    }

    private static void getFilesFromFolder(File file, List<File> output) {
        if (file.isFile()) {
            output.add(file);
        }

        if (file.isDirectory()) {
            File[] listOfFiles = file.listFiles();
            for (File child: listOfFiles) {
                getFilesFromFolder(child, output);
            }
        }
    }

    public static void zip(OutputStream outputStream, File baseFolder, List<File> filesToZip) throws IOException {
        ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream);

        for (File file : filesToZip) {
            if (baseFolder != null) {
                String relative = baseFolder.toURI().relativize(file.toURI()).getPath();
                zipOutputStream.putNextEntry(new ZipEntry(relative));
            }
            else {
                zipOutputStream.putNextEntry(new ZipEntry(file.getName()));
            }
            Files.copy(file.toPath(), zipOutputStream);
            zipOutputStream.closeEntry();
        }

        zipOutputStream.finish();
    }

    public static File zip(List<File> filesToZip, final File baseFolder, final String zipFilename) throws IOException {
        File zipFile = new File(zipFilename);

        List<File> allFiles = new ArrayList<>();

        for (File file : filesToZip) {
            getFilesFromFolder(file, allFiles);
        }

        try (FileOutputStream fileOutputStream = new FileOutputStream(zipFile)) {
            ZipUtils.zip(fileOutputStream, baseFolder, allFiles);
        }

        return zipFile;
    }

    private static void extractFile(ZipInputStream zipIn, File file) throws IOException {
        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(file));
        byte[] bytesIn = new byte[ZipUtils.bufferSize];
        int read = 0;
        while ((read = zipIn.read(bytesIn)) != -1) {
            bos.write(bytesIn, 0, read);
        }
        bos.close();
    }
}
