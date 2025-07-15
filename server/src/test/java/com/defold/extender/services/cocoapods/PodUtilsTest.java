package com.defold.extender.services.cocoapods;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

public class PodUtilsTest {

    @Test
    public void testPodNameSanitize() {
        String[] testedNames = new String[] {
            "DivKit_LayoutKit",
            "GoogleUserMessagingPlatform",
            "Google-Mobile-Ads-SDK",
            "UIAlertController+Blocks",
            "Socket.IO-Client-Swift"
        };
        String[] expectedNames = new String[] {
            "DivKit_LayoutKit",
            "GoogleUserMessagingPlatform",
            "Google-Mobile-Ads-SDK",
            "UIAlertController\\+Blocks",
            "Socket.IO-Client-Swift"
        };
        assertEquals(testedNames.length, expectedNames.length);
        for (int idx = 0; idx < testedNames.length; ++idx) {
            assertEquals(expectedNames[idx], PodUtils.sanitizePodName(testedNames[idx]));
        }
    }

    @Test
    public void testListFilesByPattern() throws IOException {
        File workDir = Files.createTempDirectory("collect-res-by-pattern").toFile();
        workDir.deleteOnExit();
        File parentDir = Path.of(workDir.toString(), "inner_folder1", "inner_folder2").toFile();
        parentDir.mkdirs();
        File bundleFile = new File(parentDir, "PodTest.bundle");
        bundleFile.createNewFile();

        File bundleDir = new File(parentDir, "PodTestBundleDir.bundle");
        bundleDir.mkdirs();

        List<File> files = PodUtils.listFilesAndDirsGlob(workDir, "inner_folder1/inner_folder2/PodTest.bundle");
        assertEquals(1, files.size());

        files = PodUtils.listFilesAndDirsGlob(workDir, "inner_folder1/inner_folder2/PodTestBundleDir.bundle");
        assertEquals(1, files.size());
    }
}
