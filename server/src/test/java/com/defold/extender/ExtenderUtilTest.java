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
        System.out.printf("MAWE: testAndroidAssetFolders\n");
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
}
