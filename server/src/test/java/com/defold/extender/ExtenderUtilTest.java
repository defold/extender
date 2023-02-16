package com.defold.extender;

import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.io.FileUtils;
import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.mock.web.MockMultipartFile;

import javax.servlet.http.HttpServletRequest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ExtenderUtilTest {

    private File jobDir;
    private File uploadDir;
    private File buildDir;

    @Before
    public void setUp() throws IOException {
        jobDir = Files.createTempDirectory("job123456").toFile();
        uploadDir = new File(jobDir, "upload");
        uploadDir.mkdirs();
        buildDir = new File(jobDir, "build");
        buildDir.mkdirs();
    }

    @After
    public void tearDown() throws IOException {
        if (jobDir != null) {
            FileUtils.deleteDirectory(jobDir);
            jobDir = null;
        }
    }

    private File getRelative(File dir, File path) {
        return dir.toPath().relativize(path.toPath()).toFile();
    }

    @Test
    public void testAndroidAssetFolders() throws IOException, InterruptedException, ExtenderException {
        File d;
        d = new File(uploadDir, "extension1/res/android/res/com.foo.name/res/values"); d.mkdirs(); assertTrue(d.exists());
        d = new File(uploadDir, "extension2/res/android/res/com.foo.name/values"); d.mkdirs(); assertTrue(d.exists());
        d = new File(uploadDir, "extension3/res/android/res/values"); d.mkdirs(); assertTrue(d.exists());

        // new recommended format
        d = ExtenderUtil.getAndroidResourceFolder(new File(uploadDir, "extension1/res/android/res"));
        assertEquals("extension1/res/android/res/com.foo.name/res", getRelative(uploadDir, d).getPath());

        // legacy format
        d = ExtenderUtil.getAndroidResourceFolder(new File(uploadDir, "extension2/res/android/res"));
        assertEquals("extension2/res/android/res/com.foo.name", getRelative(uploadDir, d).getPath());

        // legacy format
        d = ExtenderUtil.getAndroidResourceFolder(new File(uploadDir, "extension3/res/android/res"));
        assertEquals("extension3/res/android/res", getRelative(uploadDir, d).getPath());

        // 'project/extension/res/android/res/com.foo.name/res/<android folders>' (new)
        // 'project/extension/res/android/res/com.foo.name/<android folders>' (legacy)
        // 'project/extension/res/android/res/<android folders>' (legacy)

        d = ExtenderUtil.getAndroidResourceFolder(new File(uploadDir, "extension3/res/android/res/values"));
        assertNull(d);
    }

    @Test
    public void testChild() throws IOException, InterruptedException, ExtenderException {
        File parent = new File("upload/extension1");
        assertEquals( ExtenderUtil.isChild(parent, new File("upload/extension1")), true );
        assertEquals( ExtenderUtil.isChild(parent, new File("upload/extension1/file")), true );
        assertEquals( ExtenderUtil.isChild(parent, new File("upload")), false);
    }


    @Test
    public void testMergeList() {
        String[] a = {"1", "2", "2", "3", "4"};
        String[] b = {"3", "5", "4", "5"};

        List<String> c = ExtenderUtil.mergeLists(Arrays.asList(a), Arrays.asList(b));

        String[] expected = {"1", "2", "2", "3", "4", "3", "5", "4", "5"};

        assertArrayEquals(expected, c.toArray());
    }

    @Test
    public void testMergeContext() throws ExtenderException {
        {
            Map<String, Object> a = new HashMap<>();
            Map<String, Object> b = new HashMap<>();

            a.put("frameworks", Arrays.asList("a", "b", "b", "c"));
            a.put("defines", Arrays.asList("A", "B"));

            b.put("frameworks", Arrays.asList("a", "d"));
            b.put("symbols", Arrays.asList("S1"));

            Map<String, Object> result = ExtenderUtil.mergeContexts(a, b);

            Map<String, Object> expected = new HashMap<>();
            expected.put("frameworks", Arrays.asList("a", "b", "b", "c", "a", "d"));
            expected.put("defines", Arrays.asList("A", "B"));
            expected.put("symbols", Arrays.asList("S1"));

            assertEquals(expected, result);
        }

        // Testing issue70
        {
            Map<String, Object> a = new HashMap<>();
            Map<String, Object> b = new HashMap<>();
            a.put("value", null);
            b.put("value", null);

            Map<String, Object> result = ExtenderUtil.mergeContexts(a, b);
            assertTrue(result != null);

            Map<String, Object> expected = new HashMap<>();
            assertEquals(expected, result);
        }
        {
            Map<String, Object> a = new HashMap<>();
            Map<String, Object> b = new HashMap<>();
            a.put("value", "a");
            b.put("value", null);

            Map<String, Object> result = ExtenderUtil.mergeContexts(a, b);
            assertTrue(result != null);

            Map<String, Object> expected = new HashMap<>();
            expected.put("value", "a");
            assertEquals(expected, result);
        }
        {
            Map<String, Object> a = new HashMap<>();
            Map<String, Object> b = new HashMap<>();
            a.put("value", null);
            b.put("value", "b");

            Map<String, Object> result = ExtenderUtil.mergeContexts(a, b);
            assertTrue(result != null);

            Map<String, Object> expected = new HashMap<>();
            expected.put("value", "b");
            assertEquals(expected, result);
        }
    }

    @Test
    public void testMergeContextLists() throws IOException, InterruptedException, ExtenderException {

        Map<String, Object> a = new HashMap<>();
        a.put("a_only", Arrays.asList("a"));
        a.put("union", Arrays.asList("a"));
        a.put("override", Arrays.asList("a"));
        a.put("union2_replace", Arrays.asList("a"));

        Map<String, Object> b = new HashMap<>();
        b.put("b_only", Arrays.asList("b"));
        b.put("union", Arrays.asList("b"));
        b.put("override_replace", Arrays.asList("b"));
        b.put("union2", Arrays.asList("b"));

        Map<String, Object> context = ExtenderUtil.mergeContexts(a, b);

        assertEquals(Arrays.asList("a"), context.getOrDefault("a_only", null));
        assertEquals(Arrays.asList("b"), context.getOrDefault("b_only", null));
        assertEquals(Arrays.asList("a", "b"), context.getOrDefault("union", null));
        assertEquals(Arrays.asList("b"), context.getOrDefault("override", null));
        assertEquals(Arrays.asList("a", "b"), context.getOrDefault("union2", null));
    }

    @Test
    public void testPruneList() throws IOException, InterruptedException, ExtenderException {
        List<String> main = Arrays.asList("profile", "profile_null", "a");
        List<String> include_libs = Arrays.asList("a");
        List<String> exclude_libs = Arrays.asList("profile", "a");
        List<String> result = ExtenderUtil.pruneItems(main, include_libs, exclude_libs);

        assertEquals(Arrays.asList("profile_null", "a"), result);
    }

    @Test
    public void testMergeObjects() throws ExtenderException {
        PlatformConfig a = new PlatformConfig();
        PlatformConfig b = new PlatformConfig();
        a.env.put("common", "A");
        a.env.put("a", "A");
        a.context.put("common2", Arrays.asList("A"));
        a.libCmd = "lib_cmd_a";
        a.allowedLibs = Arrays.asList("A");
        a.allowedFlags = Arrays.asList("A");

        b.env.put("common", "B");
        b.env.put("b", "B");
        b.context.put("common2_replace", Arrays.asList("B"));
        b.libCmd = "lib_cmd_b";
        b.allowedLibs = Arrays.asList("B");
        b.allowedSymbols = Arrays.asList("B");

        ExtenderUtil.mergeObjects(a, b);

        ExtenderUtil.debugPrintObject("Merged Object", a);

        // a now contains the merged object
        assertEquals("B", a.env.get("common"));
        assertEquals("A", a.env.get("a"));
        assertEquals("B", a.env.get("b"));
        assertEquals("lib_cmd_b", a.libCmd);
        assertEquals(Arrays.asList("A", "B"), a.allowedLibs);
        assertEquals(Arrays.asList("A"), a.allowedFlags);
        assertEquals(Arrays.asList("B"), a.allowedSymbols);
        assertEquals(Arrays.asList("B"), a.context.get("common2"));
    }

    @Test
    public void testCreatePlatformConfig() throws ExtenderException {
        AppManifestPlatformConfig appConfig = new AppManifestPlatformConfig();
        appConfig.context.put("a", "valueA");
        appConfig.context.put("b", Arrays.asList("B", "b"));
        PlatformConfig config = ExtenderUtil.createPlatformConfig(appConfig);

        assertEquals("valueA", config.context.getOrDefault("a", "invalid"));
        assertEquals(Arrays.asList("B", "b"), config.context.getOrDefault("b", "invalid"));
    }
}
