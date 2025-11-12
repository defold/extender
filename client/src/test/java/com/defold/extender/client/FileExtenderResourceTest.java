package com.defold.extender.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

import org.junit.jupiter.api.Test;

public class FileExtenderResourceTest {
        @Test
    public void testClientGetSource() throws IOException {
        List<ExtenderResource> files = null;

        String platform = "x86-osx";
        files = getExtensionSource(new File("../server/test-data/testproject/a"), platform);
        assertEquals(0, files.size());

        files = getExtensionSource(new File("../server/test-data/testproject/b"), platform);
        assertEquals(4, files.size());

        files = getExtensionSource(new File("../server/test-data/testproject"), platform);
        assertEquals(4, files.size());
    }

    private static List<ExtenderResource> getExtensionSource(File root, String platform) throws IOException {
        List<ExtenderResource> source = new ArrayList<>();
        List<File> extensions = listExtensionFolders(root);

        for (File f : extensions) {

            source.add(new FileExtenderResource(f.getAbsolutePath() + File.separator + ExtenderClient.extensionFilename));
            source.addAll(listFilesRecursive(new File(f.getAbsolutePath() + File.separator + "include")));
            source.addAll(listFilesRecursive(new File(f.getAbsolutePath() + File.separator + "src")));
            source.addAll(listFilesRecursive(new File(f.getAbsolutePath() + File.separator + "lib" + File.separator + platform)));

            String[] platformParts = platform.split("-");
            if (platformParts.length == 2) {
                source.addAll(listFilesRecursive(new File(f.getAbsolutePath() + File.separator + "lib" + File.separator + platformParts[1])));
            }
        }
        return source;
    }

    private static List<File> listExtensionFolders(File dir) throws IOException {
        if (!dir.isDirectory()) {
            throw new IOException("Path is not a directory: " + dir.getAbsolutePath());
        }

        List<File> folders = new ArrayList<>();

        File[] files = dir.listFiles();
        for (File f : files) {
            Matcher m = ExtenderClient.extensionPattern.matcher(f.getName());
            if (m.matches()) {
                folders.add(dir);
                return folders;
            }
            if (f.isDirectory()) {
                folders.addAll(listExtensionFolders(f));
            }
        }
        return folders;
    }

    private static List<ExtenderResource> listFilesRecursive(File dir) {
        List<ExtenderResource> output = new ArrayList<>();
        if (!dir.isDirectory()) {
            return output; // the extensions doesn't have to have all folders that we look for
        }

        File[] files = dir.listFiles();
        for (File f : files) {
            if (f.isFile()) {
                output.add(new FileExtenderResource(f));
            } else {
                output.addAll(listFilesRecursive(f));
            }
        }
        return output;
    }


    /*
        Scans a directory and returns true if there are extensions available
    */
    private static boolean hasExtensions(File dir) {
        File[] files = dir.listFiles();
        if (!dir.exists()) {
            return false;
        }
        for (File f : files) {
            Matcher m = ExtenderClient.extensionPattern.matcher(f.getName());
            if (m.matches()) {
                return true;
            }

            if (f.isDirectory()) {
                if (hasExtensions(f)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    public void testClientHasExtensions() {
        assertFalse(hasExtensions(new File("../server/test-data/testproject/a")));
        assertTrue(hasExtensions(new File("../server/test-data/testproject/b")));
        assertTrue(hasExtensions(new File("../server/test-data/testproject")));
    }
}
