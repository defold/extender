package com.defold.extender;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ZipUtilsTest {

    @Test
    public void zipAndUnzipFiles() throws IOException {
        Path sourceFile1 = Files.createTempFile("zipTest", "tmp");
        Path sourceFile2 = Files.createTempFile("zipTest", "tmp");
        Path destinationFile = Files.createTempFile("archive", "zip");
        Path targetDirectory = Files.createTempDirectory("target");

        List<File> files = new ArrayList<>();
        files.add(sourceFile1.toFile());
        files.add(sourceFile2.toFile());

        ZipUtils.zip(new FileOutputStream(destinationFile.toFile()), files);

        ZipUtils.unzip(new FileInputStream(destinationFile.toFile()), targetDirectory);

        assertEquals(2, targetDirectory.toFile().listFiles().length);
    }

    @Test
    public void zipFilesToFile() throws IOException {
        File targetDirectory = Files.createTempDirectory("test").toFile();
        String zipFilename = targetDirectory.getAbsolutePath() + "/archive.zip";

        Path sourceFile1 = Files.createTempFile("zipTest", "tmp");
        Path sourceFile2 = Files.createTempFile("zipTest", "tmp");

        List<File> files = new ArrayList<>();
        files.add(sourceFile1.toFile());
        files.add(sourceFile2.toFile());

        File zipFile = ZipUtils.zip(files, zipFilename);
        File[] filesInTarget = targetDirectory.listFiles();

        assertNotNull(filesInTarget);
        assertEquals(1, filesInTarget.length);
        assertEquals(zipFile.length(), filesInTarget[0].length());
        assertTrue(filesInTarget[0].length() > 0);
    }
}
