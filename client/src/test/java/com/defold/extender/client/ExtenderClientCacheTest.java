package com.defold.extender.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;

public class ExtenderClientCacheTest {
    @Test
    public void testClientCacheHash() throws IOException, InterruptedException, ExtenderClientException {
        TestUtils.writeToFile("build/a", "a");
        TestUtils.writeToFile("build/b", "a");
        TestUtils.writeToFile("build/c", "b");

        ExtenderClientCache cache = new ExtenderClientCache(new File("."));

        {
            File file1 = new File("build/a");
            File file2 = new File("build/b");
            File file3 = new File("build/c");
            FileExtenderResource file1Res = new FileExtenderResource(file1);
            FileExtenderResource file2Res = new FileExtenderResource(file2);
            FileExtenderResource file3Res = new FileExtenderResource(file3);
            assertEquals(cache.getHash(file1Res), cache.getHash(file2Res));
            assertNotEquals(cache.getHash(file1Res), cache.getHash(file3Res));
        }

        Thread.sleep(1000);

        TestUtils.writeToFile("build/b", "b");

        {
            File file1 = new File("build/a");
            File file2 = new File("build/b");
            FileExtenderResource file1Res = new FileExtenderResource(file1);
            FileExtenderResource file2Res = new FileExtenderResource(file2);

            assertNotEquals(cache.getHash(file1Res), cache.getHash(file2Res));
        }

        FileUtils.deleteQuietly(new File("build/a"));
        FileUtils.deleteQuietly(new File("build/b"));
        FileUtils.deleteQuietly(new File("build/c"));
    }

    @Test
    public void testClientCacheSignatureHash() throws IOException, ExtenderClientException {
        File a = new File("build/a");
        File b = new File("build/b");
        FileExtenderResource aRes = new FileExtenderResource(a);
        FileExtenderResource bRes = new FileExtenderResource(b);

        TestUtils.writeToFile("build/a", "a");
        TestUtils.writeToFile("build/b", "b");

        List<ExtenderResource> files1 = new ArrayList<>();
        files1.add(aRes);
        files1.add(bRes);


        List<ExtenderResource> files2 = new ArrayList<>();
        files2.add(bRes);
        files2.add(aRes);

        String platform = "osx";
        String sdkVersion = "abc456";
        ExtenderClientCache cache = new ExtenderClientCache(new File("."));

        assertEquals(cache.getHash(files1), cache.getHash(files2));
        assertEquals(cache.calcKey(platform, sdkVersion, files1), cache.calcKey(platform, sdkVersion, files2));

        files2.add(aRes);

        assertNotEquals(cache.getHash(files1), cache.getHash(files2));
        assertNotEquals(cache.calcKey(platform, sdkVersion, files1), cache.calcKey(platform, sdkVersion, files2));

        FileUtils.deleteQuietly(new File("build/a"));
        FileUtils.deleteQuietly(new File("build/b"));
    }

    @Test
    public void testClientCacheValidBuild() throws IOException, InterruptedException, ExtenderClientException {
        File a = new File("build/a");
        File b = new File("build/b");
        File c = new File("build/c");
        FileExtenderResource aRes = new FileExtenderResource(a);
        FileExtenderResource bRes = new FileExtenderResource(b);
        FileExtenderResource cRes = new FileExtenderResource(c);

        a.deleteOnExit();
        b.deleteOnExit();
        c.deleteOnExit();

        TestUtils.writeToFile("build/a", "a");
        TestUtils.writeToFile("build/b", "b");
        TestUtils.writeToFile("build/c", "c");

        List<ExtenderResource> files = new ArrayList<>();
        files.add(aRes);
        files.add(bRes);

        String platform = "osx";
        String sdkVersion = "abc456";
        ExtenderClientCache cache = new ExtenderClientCache(new File("."));

        if (cache.getCacheFile().exists()) {
            cache.getCacheFile().delete();
        }

        String key = null;
        // Is doesn't exist yet, so false
        key = cache.calcKey(platform, sdkVersion, files);
        assertEquals(false, cache.isCached(platform, key));

        File build = cache.getCachedBuildFile(platform);
        build.deleteOnExit();

        TestUtils.writeToFile(build.getAbsolutePath(), (new Date()).toString());
        cache.put(platform, key, build);

        // It should exist now, so true
        assertEquals(true, cache.isCached(platform, key));

        // Changing a source file should invalidate the file
        Thread.sleep(1000);
        TestUtils.writeToFile("build/b", "bb");
        key = cache.calcKey(platform, sdkVersion, files);

        assertEquals(false, cache.isCached(platform, key));

        // If we update the build, is should be cached
        cache.put(platform, key, build);
        assertEquals(true, cache.isCached(platform, key));

        // Add a "new" file to the list, but let it have an old timestamp
        files.add(cRes);
        key = cache.calcKey(platform, sdkVersion, files);

        assertEquals(false, cache.isCached(platform, key));

        // If we update the build, is should be cached
        cache.put(platform, key, build);
        assertEquals(true, cache.isCached(platform, key));

        // Remove one file
        files.remove(0);
        key = cache.calcKey(platform, sdkVersion, files);

        assertEquals(false, cache.isCached(platform, key));

        // If we update the build, is should be cached
        cache.put(platform, key, build);
        assertEquals(true, cache.isCached(platform, key));
    }

    private static String calcChecksum(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] data = Files.readAllBytes(file.toPath());
        md.update(data);
        byte[] digest = md.digest();
        return new BigInteger(1, digest).toString(16);
    }

    @Test
    public void testClientCachePersistence() throws IOException, ExtenderClientException, NoSuchAlgorithmException {
        File a = new File("build/a");
        FileExtenderResource aRes = new FileExtenderResource(a);
        a.deleteOnExit();
        TestUtils.writeToFile("build/a", "a");

        List<ExtenderResource> files = new ArrayList<>();
        files.add(aRes);

        String platform = "osx";
        String sdkVersion = "abc456";
        File cacheDir = new File(".");


        String key;
        {
            ExtenderClientCache cache = new ExtenderClientCache(cacheDir);
            key = cache.calcKey(platform, sdkVersion, files);

            if (cache.getCacheFile().exists()) {
                cache.getCacheFile().delete();
            }
            assertFalse(cache.getCacheFile().exists());
        }

        // Start with an empty cache
        String checksum = null;
        {
            ExtenderClientCache cache = new ExtenderClientCache(cacheDir);

            assertEquals(false, cache.isCached(platform, key));

            // Write the build, and update the cache
            File build = File.createTempFile("test", "build");
            build.deleteOnExit();
            TestUtils.writeToFile(build.getAbsolutePath(), (new Date()).toString());
            cache.put(platform, key, build);

            checksum = calcChecksum(build);
        }

        // Now, lets create another cache, and check that we get a cached version
        {
            ExtenderClientCache cache = new ExtenderClientCache(cacheDir);

            assertEquals(true, cache.isCached(platform, key));

            File build = File.createTempFile("test2", "build");
            cache.get(platform, key, build);

            String checksum2 = calcChecksum(build);

            assertEquals(checksum, checksum2);
        }
    }
}
