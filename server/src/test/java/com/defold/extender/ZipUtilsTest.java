package com.defold.extender;

import org.junit.Test;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;

public class ZipUtilsTest {

    @Test
    public void zipAndUnzipFiles() throws IOException {
        Path sourceFile1 = Files.createTempFile("zipTest", "tmp");
        Path sourceFile2 = Files.createTempFile("zipTest", "tmp");
        Path destinationFile = Files.createTempFile("archive", "zip");
        Path targetDirectory = Files.createTempDirectory("target");

        ZipUtils.zip(new FileOutputStream(destinationFile.toFile()), sourceFile1.toFile(), sourceFile2.toFile());

        ZipUtils.unzip(new FileInputStream(destinationFile.toFile()), targetDirectory);

        assertEquals(2, targetDirectory.toFile().listFiles().length);
    }
}
