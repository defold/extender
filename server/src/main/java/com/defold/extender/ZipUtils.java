package com.defold.extender;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Set;
import java.util.HashSet;

import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

// For reading and preserving attributes
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;

public class ZipUtils {

    private static int bufferSize = 128 * 1024;

    public static Set<PosixFilePermission> getPosixFilePermissions(int mode) {
        final PosixFilePermission[] values = {
            PosixFilePermission.OTHERS_EXECUTE, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_READ,
            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_READ,
            PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_READ
        };

        int mask = 1;

        Set<PosixFilePermission> s = EnumSet.noneOf(PosixFilePermission.class);
        for (PosixFilePermission p : values) {
            if ((mask & mode) != 0) {
                s.add(p);
            }
            mask = mask << 1;
        }
        return s;
    }

    public static void unzip(InputStream inputStream, Path targetDirectory) throws IOException {
        try (ZipArchiveInputStream zipInputStream = new ZipArchiveInputStream(inputStream)) {
            ZipArchiveEntry zipEntry = zipInputStream.getNextZipEntry();

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

                    // TODO: Find out why it doesn't return other than 0 !?
                    //int permissions = zipEntry.getUnixMode();
                    //Set<PosixFilePermission> s = getPosixFilePermissions(permissions);
                    Set<PosixFilePermission> s = new HashSet<>();
                    s.add(PosixFilePermission.OTHERS_READ);
                    s.add(PosixFilePermission.GROUP_READ);
                    s.add(PosixFilePermission.OWNER_READ);

                    // Poor man's version of making sure the stuff in the bin folder is executable
                    if (entryTargetFile.getAbsolutePath().contains("/bin/")) {
                        s.add(PosixFilePermission.GROUP_EXECUTE);
                        s.add(PosixFilePermission.OWNER_EXECUTE);
                    }

                    Files.setPosixFilePermissions(entryTargetFile.toPath(), s);
                }

                zipEntry = zipInputStream.getNextZipEntry();
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

    private static void extractFile(ZipArchiveInputStream zipIn, File file) throws IOException {
        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(file));
        byte[] bytesIn = new byte[ZipUtils.bufferSize];
        int read = 0;
        while ((read = zipIn.read(bytesIn)) != -1) {
            bos.write(bytesIn, 0, read);
        }
        bos.close();
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
