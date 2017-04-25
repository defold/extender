package com.defold.extender.client;

import org.apache.commons.io.FileUtils;
import org.junit.Test;

import javax.xml.bind.annotation.adapters.HexBinaryAdapter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

public class ExtenderClientTest {

    private void writeToFile(String path, String msg) throws IOException {
        File f = new File(path);
        FileWriter fwr = new FileWriter(f);
        fwr.write(msg);
        fwr.flush();
        fwr.close();
        f.setLastModified(Instant.now().toEpochMilli() + 23);
    }

    @Test
    public void testClientCacheHash() throws IOException, InterruptedException, ExtenderClientException {
        writeToFile("build/a", "a");
        writeToFile("build/b", "a");
        writeToFile("build/c", "b");

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

        writeToFile("build/b", "b");

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

        writeToFile("build/a", "a");
        writeToFile("build/b", "b");

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

        writeToFile("build/a", "a");
        writeToFile("build/b", "b");
        writeToFile("build/c", "c");

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

        writeToFile(build.getAbsolutePath(), (new Date()).toString());
        cache.put(platform, key, build);

        // It should exist now, so true
        assertEquals(true, cache.isCached(platform, key));

        // Changing a source file should invalidate the file
        Thread.sleep(1000);
        writeToFile("build/b", "bb");
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
        return (new HexBinaryAdapter()).marshal(digest);
    }

    @Test
    public void testClientCachePersistence() throws IOException, InterruptedException, ExtenderClientException, NoSuchAlgorithmException {
        File a = new File("build/a");
        FileExtenderResource aRes = new FileExtenderResource(a);
        a.deleteOnExit();
        writeToFile("build/a", "a");

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
            writeToFile(build.getAbsolutePath(), (new Date()).toString());
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

    @Test
    public void testClientGetSource() throws IOException {
        File root = new File("test-data/testproject/a");
        List<ExtenderResource> files = null;

        String platform = "x86-osx";
        files = getExtensionSource(new File("../server/test-data/testproject/a"), platform);
        assertEquals(0, files.size());

        files = getExtensionSource(new File("../server/test-data/testproject/b"), platform);
        assertEquals(5, files.size());

        files = getExtensionSource(new File("../server/test-data/testproject"), platform);
        assertEquals(5, files.size());
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

    @Test
    public void testClientHasExtensions() throws IOException, InterruptedException {
        assertFalse(hasExtensions(new File("../server/test-data/testproject/a")));
        assertTrue(hasExtensions(new File("../server/test-data/testproject/b")));
        assertTrue(hasExtensions(new File("../server/test-data/testproject")));
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
}
