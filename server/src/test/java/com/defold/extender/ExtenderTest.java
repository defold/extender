package com.defold.extender;

import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.fileUpload;

public class ExtenderTest {

    @Test
    public void testExtender() throws IOException, InterruptedException, ExtenderException {
        File uploadDir = new File("/tmp/tmpUpload");
        uploadDir.mkdirs();
        uploadDir.deleteOnExit();
        File buildDir = new File("/tmp/tmpBuild");
        buildDir.mkdirs();
        buildDir.deleteOnExit();
        File sdk = new File("test-data/sdk/a/defoldsdk");
        Extender extender = new Extender("x86-osx", uploadDir, sdk, buildDir.getAbsolutePath());

        uploadDir.delete();
        assertTrue(true);
    }

    @Test
    public void testReceiveFiles() throws IOException, InterruptedException, ExtenderException {

        MockMultipartHttpServletRequestBuilder builder;
        MockHttpServletRequest request;
        File uploadDirectory;
        String filename;
        String expectedContent;

        // Should be fine
        uploadDirectory = Files.createTempDirectory("upload").toFile();
        uploadDirectory.deleteOnExit();
        builder = fileUpload("/tmpUpload");
        filename = "include/test.h";
        expectedContent = "//ABcdEFgh";
        builder.file(filename, expectedContent.getBytes());
        request = builder.buildRequest(null);
        {
            ExtenderController.receiveUpload((MockMultipartHttpServletRequest) request, uploadDirectory);
            File file = new File(uploadDirectory.getAbsolutePath() + "/" + filename);
            file.deleteOnExit();
            assertTrue(file.exists());
            String fileContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            assertTrue(expectedContent.equals(fileContent));
        }

        // Mustn't upload files outside of the folder!
        uploadDirectory = Files.createTempDirectory("upload").toFile();
        uploadDirectory.deleteOnExit();
        builder = fileUpload("/tmpUpload");
        filename = "../include/test.h";
        expectedContent = "//invalidfile";
        builder.file(filename, expectedContent.getBytes());
        request = builder.buildRequest(null);
        {
            boolean thrown = false;
            try {
                ExtenderController.receiveUpload((MockMultipartHttpServletRequest) request, uploadDirectory);
            } catch (ExtenderException e) {
                thrown = true;
            }
            assertTrue(thrown);
            File file = new File(uploadDirectory.getAbsolutePath() + "/" + filename);
            assertFalse(file.exists());
        }

        // Should be fine (Windows back slashes)
        uploadDirectory = Files.createTempDirectory("upload").toFile();
        uploadDirectory.deleteOnExit();
        builder = fileUpload("/tmpUpload");
        filename = "src/foo/bar/test.cpp";
        expectedContent = "//ABcdEFgh";
        builder.file("src\\foo\\bar\\test.cpp", expectedContent.getBytes());
        request = builder.buildRequest(null);
        {
            ExtenderController.receiveUpload((MockMultipartHttpServletRequest) request, uploadDirectory);
            File file = new File(uploadDirectory.getAbsolutePath() + "/" + filename);
            file.deleteOnExit();
            assertTrue(file.exists());
            String fileContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            assertTrue(expectedContent.equals(fileContent));
        }
    }

    @Test
    public void testValidateFilenames() throws IOException, InterruptedException, ExtenderException {
        MockMultipartHttpServletRequestBuilder builder;

        // Should be fine
        builder = fileUpload("/tmpUpload");
        builder.file("include/test.h", "// test.h".getBytes());
        MockHttpServletRequest request = builder.buildRequest(null);
        {
            boolean thrown = false;
            try {
                ExtenderController.validateFilenames((MockMultipartHttpServletRequest) request);
            } catch (ExtenderException e) {
                thrown = true;
            }
            assertFalse(thrown);
        }

        // Should be fine
        builder = fileUpload("/tmpUpload");
        builder.file("include/test+framework.h", "// test.h".getBytes());
        request = builder.buildRequest(null);
        {
            boolean thrown = false;
            try {
                ExtenderController.validateFilenames((MockMultipartHttpServletRequest) request);
            } catch (ExtenderException e) {
                thrown = true;
            }
            assertFalse(thrown);
        }

        // Should be fine
        builder = fileUpload("/tmpUpload");
        builder.file("src/test.c++", "// test".getBytes());
        request = builder.buildRequest(null);
        {
            boolean thrown = false;
            try {
                ExtenderController.validateFilenames((MockMultipartHttpServletRequest) request);
            } catch (ExtenderException e) {
                thrown = true;
            }
            assertFalse(thrown);
        }

        // Should throw error
        builder = fileUpload("/tmpUpload");
        builder.file("+foobar.h", "// test".getBytes());
        request = builder.buildRequest(null);
        {
            boolean thrown = false;
            try {
                ExtenderController.validateFilenames((MockMultipartHttpServletRequest) request);
            } catch (ExtenderException e) {
                thrown = true;
            }
            assertTrue(thrown);
        }

        // Should throw error
        builder = fileUpload("/tmpUpload");
        builder.file("include/foo;echo foo;.h", "// trying to sneak in an echo command".getBytes());
        request = builder.buildRequest(null);
        {
            boolean thrown = false;
            try {
                ExtenderController.validateFilenames((MockMultipartHttpServletRequest) request);
            } catch (ExtenderException e) {
                thrown = true;
            }
            assertTrue(thrown);
        }

        // Should throw error
        builder = fileUpload("/tmpUpload");
        builder.file("../../etc/passwd", "// trying to sneak in a new system file".getBytes());
        request = builder.buildRequest(null);
        {
            boolean thrown = false;
            try {
                ExtenderController.validateFilenames((MockMultipartHttpServletRequest) request);
            } catch (ExtenderException e) {
                thrown = true;
            }
            assertTrue(thrown);
        }
    }

    @Test
    public void testFilterFiles() throws IOException, InterruptedException, ExtenderException {

        String[] arr = {
                "a.cpp", "a.inl", "a.h",
                "a.cxx", "a.hpp",
                "a.CPP", "a.hxx",
                "a.CC", "a.CXX",
                "a.txt", "a.o", "a.obj",
                "a.cpp.bak", "a.cpp_",
                "a.m", "a.bogus", "a.mm"
        };

        Collection<File> src = new ArrayList<>();
        for (String k : arr) {
            src.add(new File(k));
        }

        String[] expectedNames = {
                "a.cpp", "a.cxx",
                "a.CPP", "a.CC", "a.CXX",
                "a.m", "a.mm"
        };

        List<File> expected = new ArrayList<>();
        for (String k : expectedNames) {
            expected.add(new File(k));
        }

        List<File> result = Extender.filterFiles(src, "(?i).*(.cpp|.c|.cc|.cxx|.c++|.mm|.m)");

        assertEquals(expected, result);
    }

    @Test
    public void testMergeList() throws IOException, InterruptedException, ExtenderException {
        String[] a = {"1", "2", "2", "3", "4"};
        String[] b = {"3", "5", "4", "5"};

        List<String> c = Extender.mergeLists(Arrays.asList(a), Arrays.asList(b));

        String[] expected = {"1", "2", "3", "4", "5"};

        assertArrayEquals(expected, c.toArray());
    }

    @Test
    public void testMergeContext() throws IOException, InterruptedException, ExtenderException {
        Map<String, Object> a = new HashMap<>();
        Map<String, Object> b = new HashMap<>();

        String[] a_frameworks = {"a", "b", "b", "c"};
        a.put("frameworks", Arrays.asList(a_frameworks));
        String[] a_defines = {"A", "B"};
        a.put("defines", Arrays.asList(a_defines));

        String[] b_frameworks = {"a", "d"};
        b.put("frameworks", Arrays.asList(b_frameworks));

        Map<String, Object> result = Extender.mergeContexts(a, b);

        Map<String, Object> expected = new HashMap<>();
        String[] expected_frameworks = {"a", "b", "c", "d"};
        expected.put("frameworks", Arrays.asList(expected_frameworks));
        String[] expected_defines = {"A", "B"};
        expected.put("defines", Arrays.asList(expected_defines));

        assertEquals(expected, result);
    }

    @Test
    public void testListTypes() {
        List<Object> a = new ArrayList<>();
        a.add("a");
        a.add("b");
        a.add("c");
        a.add("d");
        assertTrue(Extender.isListOfStrings(a));

        List<Object> b = new ArrayList<>();
        b.add("a");
        b.add("b");
        b.add(1);
        b.add(2);
        assertTrue(!Extender.isListOfStrings(b));
    }

    @Test
    public void testCollectLibraries() {
        // The folder contains a library and a text file
        {
            List<String> result = Extender.collectFilesByName(new File("test-data/ext/lib/x86-osx"), "lib(.+).a");
            String[] expected = {"alib"};
            assertArrayEquals(expected, result.toArray());
        }
        {
            List<String> result = Extender.collectFilesByName(new File("test-data/ext/lib/x86-osx"), "(.+).framework");
            String[] expected = {"blib"};
            assertArrayEquals(expected, result.toArray());
        }
    }

    @Test
    public void testCollectJars() {
        List<String> result = Extender.collectFilesByPath(new File("test-data/ext/lib/armv7-android"), "(.+\\.jar)");
        assertEquals(1, result.size());
        assertTrue(result.get(0).endsWith("test-data/ext/lib/armv7-android/Dummy.jar"));
    }


    @Test
    public void testExcludeItems() throws IOException, InterruptedException, ExtenderException {

        File appManifestFile = new File("/tmp/app.manifest");
        BufferedWriter writer = new BufferedWriter(new FileWriter(appManifestFile));
        writer.write("platforms:\n    common:\n        context:\n            excludeSymbols: [\"SymbolA\"]\n    x86-osx:\n        context:\n            excludeSymbols: [\"SymbolB\"]\n    x86-win32:\n        context:\n            excludeSymbols: [\"SymbolC\"]");
        writer.close();

        AppManifestConfiguration appManifest = Extender.loadYaml(appManifestFile, AppManifestConfiguration.class);

        assertTrue(appManifest != null);

        // Make sure it handles platforms
        {
            List<String> items = Extender.getAppManifestItems(appManifest, "x86-osx", "excludeSymbols");
            assertTrue( items.contains("SymbolA") );
            assertTrue( items.contains("SymbolB") );
            assertFalse( items.contains("SymbolC") );
        }

        {
            List<String> includePatterns = Extender.getAppManifestItems(appManifest, "x86-osx", "includeSymbols");
            List<String> excludePatterns = Extender.getAppManifestItems(appManifest, "x86-osx", "excludeSymbols");
            List<String> allItems = new ArrayList<>();
            allItems.add("SymbolA");
            allItems.add("SymbolB");
            allItems.add("SymbolC");

            List<String> items = ExtenderUtil.pruneItems(allItems, includePatterns, excludePatterns);
            assertEquals( 1, items.size() );
            assertTrue( items.contains("SymbolC") );
        }

        {
            List<String> includePatterns = new ArrayList<>();;
            List<String> excludePatterns = new ArrayList<>();
            excludePatterns.add(".*/google-play-services.jar");

            List<String> allItems = new ArrayList<>();
            allItems.add("{{dynamo_home}}/ext/share/java/facebooksdk.jar");
            allItems.add("{{dynamo_home}}/ext/share/java/google-play-services.jar");

            List<String> items = ExtenderUtil.pruneItems(allItems, includePatterns, excludePatterns);
            assertEquals( 1, items.size() );
            assertTrue( items.contains("{{dynamo_home}}/ext/share/java/facebooksdk.jar") );
        }

        {
            List<String> includePatterns = new ArrayList<>();;
            List<String> excludePatterns = new ArrayList<>();
            excludePatterns.add("(.*)google-play-services.jar");

            List<String> allItems = new ArrayList<>();
            allItems.add("{{dynamo_home}}/ext/share/java/facebooksdk.jar");
            allItems.add("{{dynamo_home}}/ext/share/java/google-play-services.jar");

            List<String> items = ExtenderUtil.pruneItems(allItems, includePatterns, excludePatterns);
            assertEquals( 1, items.size() );
            assertTrue( items.contains("{{dynamo_home}}/ext/share/java/facebooksdk.jar") );
        }

        {
            List<String> includePatterns = new ArrayList<>();;
            List<String> excludePatterns = new ArrayList<>();
            excludePatterns.add("(.*).jar");                // removes all jars
            includePatterns.add("(.*)facebook(.*).jar");    // keeps the facebook jars

            List<String> allItems = new ArrayList<>();
            allItems.add("{{dynamo_home}}/ext/share/java/facebooksdk.jar");
            allItems.add("{{dynamo_home}}/ext/share/java/google-play-services.jar");

            List<String> items = ExtenderUtil.pruneItems(allItems, includePatterns, excludePatterns);
            assertEquals( 1, items.size() );
            assertTrue( items.contains("{{dynamo_home}}/ext/share/java/facebooksdk.jar") );
        }

    }
}
