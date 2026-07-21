package com.defold.extender;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;

import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

// For reading and preserving attributes
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;

public class ZipUtils {
    // use those flag only for real filesystem paths (not for zip archive or virtual FS)
    public static final boolean IS_POSIX_SUPPORTED =
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private static int bufferSize = 128 * 1024;

    public static void unzip(InputStream inputStream, Path targetDirectory) throws IOException {
        try (ZipArchiveInputStream zipInputStream = new ZipArchiveInputStream(inputStream)) {
            ZipArchiveEntry zipEntry = zipInputStream.getNextEntry();

            while (zipEntry != null) {
                if (zipEntry.isUnixSymlink()) {
                    throw new IOException("Zip entry is a symlink (rejected): " + zipEntry.getName());
                }

                File entryTargetFile;
                try {
                    entryTargetFile = SandboxedPath.resolve(targetDirectory.toFile(), zipEntry.getName());
                } catch (ExtenderException e) {
                    throw new IOException("Unsafe zip entry: " + e.getMessage(), e);
                }

                Path entryTargetPath = entryTargetFile.toPath();
                if (zipEntry.isDirectory()) {
                    Files.createDirectories(entryTargetPath);
                } else {
                    File parentDir = entryTargetFile.getParentFile();
                    if (!parentDir.exists()) {
                        Files.createDirectories(parentDir.toPath());
                    }
                    extractFile(zipInputStream, entryTargetFile);

                    entryTargetFile.setReadable(true);

                    // Poor man's version of making sure the stuff in the bin folder is executable
                    if (entryTargetFile.getAbsolutePath().contains("/bin/")) {
                        if (IS_POSIX_SUPPORTED) {
                            Set<PosixFilePermission> s = new HashSet<>();

                            s.add(PosixFilePermission.GROUP_EXECUTE);
                            s.add(PosixFilePermission.OWNER_EXECUTE);
                            Files.setPosixFilePermissions(entryTargetPath, s);
                        } else {
                            entryTargetFile.setExecutable(true);
                        }
                    }
                }

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
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
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

    private static void extractFile(ZipArchiveInputStream zipIn, File file) throws IOException {
        try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(file))) {
            byte[] bytesIn = new byte[ZipUtils.bufferSize];
            int read = 0;
            while ((read = zipIn.read(bytesIn)) != -1) {
                bos.write(bytesIn, 0, read);
            }
        }
    }

    public static List<String> getEntries(String path) throws IOException {
        List<String> entries = new ArrayList<String>();
        try (ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(path))) {
            for (ZipEntry entry = zipInputStream.getNextEntry(); entry != null; entry = zipInputStream.getNextEntry()) {
                entries.add(entry.getName());
            }
        }
        return entries;
    }
}
