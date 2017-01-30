package com.defold.extender.client;

import javax.xml.bind.annotation.adapters.HexBinaryAdapter;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

import java.security.MessageDigest;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class ExtenderClientCache {

    private static final String hashFn = "SHA-256";
    private static final String cacheFile = ".buildcache";

    private HashMap<String, Long> timestamps = new HashMap<>();        // Time stamps for input files
    private HashMap<String, String> hashes = new HashMap<>();               // Hashes for input files
    private HashMap<String, String> persistentHashes = new HashMap<>();     // Only the build artifacts

    private final File cacheDir;

    public ExtenderClientCache(File cacheDir) throws IOException {
        this.cacheDir = cacheDir;
        if (!this.cacheDir.exists()) {
            throw new IOException("Cache directory does not exist: " + cacheDir.getAbsolutePath());
        }
        if (!this.cacheDir.isDirectory()) {
            throw new IOException("Wanted cache directory is not a directory: " + cacheDir.getAbsolutePath());
        }
        loadCache();
    }

    /** Calculates (if needed) a hash from a resource (Public for unit tests)
     */
    public String getHash(ExtenderResource extenderResource) throws ExtenderClientException {
        String path = extenderResource.getAbsPath();
        Long fileTimestamp = extenderResource.getLastModified();
        Long timestamp = this.timestamps.get(path);

        if (timestamp != null && fileTimestamp.equals(timestamp) ) {
            String hash = this.hashes.get(path);
            if (hash != null) {
                return hash;
            }
        }

        // Create a new hash
        String hash = ExtenderClientCache.hash(extenderResource);
        this.timestamps.put(path, fileTimestamp);
        this.hashes.put(path, hash);
        return hash;
    }

    private void getHash(List<ExtenderResource> extenderResources, MessageDigest md) throws ExtenderClientException {
        if (extenderResources.isEmpty()) {
            throw new ExtenderClientException("The list of resources must not be empty");
        }

        extenderResources.sort(Comparator.comparing(ExtenderResource::getAbsPath));

        for (ExtenderResource extenderResource : extenderResources) {
            String fileHash = getHash(extenderResource);
            md.update(fileHash.getBytes());
        }
    }

    /** Gets the combined hash from a list of resources (Public for unit tests)
     */
    public String getHash(List<ExtenderResource> extenderResources) throws ExtenderClientException {
        MessageDigest md = ExtenderClientCache.getHasher();
        getHash(extenderResources, md);
        return hashToString(md.digest());
    }

    /** Gets the platform specific build artifact filename
     */
    public File getCachedBuildFile(String platform) {
        return new File(cacheDir + File.separator + platform + File.separator + "build.zip" );
    }

    /** Gets the cache storage file
     */
    public File getCacheFile() {
        return new File(cacheDir.getAbsolutePath() + File.separator + this.cacheFile);
    }

    /** Calculates a key to identify a build
     * @param platform          E.g. "armv7-ios"
     * @param sdkVersion        A sha1 of the defold sdk (i.e. engine version)
     * @param extenderResources A list of resources affecting the build
     * @return The calculated key
     */
    public String calcKey(String platform, String sdkVersion, List<ExtenderResource> extenderResources) throws ExtenderClientException {
        MessageDigest md = ExtenderClientCache.getHasher();
        md.update(platform.getBytes());
        md.update(sdkVersion.getBytes());
        getHash(extenderResources, md);
        return hashToString(md.digest());
    }

    /** Checks if a cached build is still valid.
     * @param platform  E.g. "armv7-ios"
     * @param key       The calculated key (see calcKey())
     * @return True if the cached version is still valid
     */
    public boolean isCached(String platform, String key) throws ExtenderClientException {
        File cachedFile = getCachedBuildFile(platform);
        String previousHash = this.persistentHashes.get(cachedFile.getAbsolutePath());
        return key.equals(previousHash);
    }


    /** After a successful build, the client has to store the "key" in the cache.
     * This will persist the cache between sessions
     * @param platform  E.g. "armv7-ios"
     * @param key       The calculated key (see calcKey())
     * @param source    The file to be copied into the cache
     */
    public void put(String platform, String key, File source) throws ExtenderClientException {
        File cachedFile = getCachedBuildFile(platform);
        File parentDir = cachedFile.getParentFile();

        if (!parentDir.exists()) {
            parentDir.mkdirs();
        }
        if (!parentDir.exists()) {
            throw new ExtenderClientException(String.format("Failed to create cache dir %s", parentDir.getAbsolutePath()));
        }

        try {
            Files.copy(new FileInputStream(source), cachedFile.toPath(), REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ExtenderClientException(String.format("Failed to copy %s to %s", source.getAbsolutePath(), cachedFile.getAbsolutePath()), e);
        }

        this.persistentHashes.put(cachedFile.getAbsolutePath(), key);
        saveCache();
    }

    /** Gets a previously cached build
     * @param platform      E.g. "armv7-ios"
     * @param key           The calculated key (see calcKey())
     * @param destination   Where to copy the data to
     * @return
     * @throws ExtenderClientException
     */
    public void get(String platform, String key, File destination) throws ExtenderClientException {
        File cachedFile = getCachedBuildFile(platform);

        if (!key.equals(this.persistentHashes.get(cachedFile.getAbsolutePath())) ) {
            throw new ExtenderClientException(String.format("The file %s wasn't cached with key %s", cachedFile.getAbsolutePath(), key));
        }

        try {
            Files.copy(new FileInputStream(cachedFile), destination.toPath(), REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ExtenderClientException(String.format("Failed to copy %s to %s", cachedFile.getAbsolutePath(), destination.getAbsolutePath()), e);
        }
    }

    //

    private static MessageDigest getHasher() throws ExtenderClientException {
        MessageDigest md = null;
        try {
            md = MessageDigest.getInstance(hashFn);
        } catch(Exception e){
            throw new ExtenderClientException("Could not create hash function: " + hashFn, e);
        }
        return md;
    }

    private static String hashToString(byte[] digest) {
        return (new HexBinaryAdapter()).marshal(digest);
    }

    private static String hash(ExtenderResource extenderResource) throws ExtenderClientException {
        try{
            MessageDigest md = ExtenderClientCache.getHasher();
            byte[] data = extenderResource.getContent();
            md.update(data);
            return hashToString(md.digest());
        } catch(Exception e){
            throw new ExtenderClientException(String.format("Failed to hash resource: ", extenderResource.getAbsPath()), e);
        }
    }

    private void saveCache() {
        Properties properties = new Properties();
        properties.putAll(this.persistentHashes);
        try {
            properties.store(new FileOutputStream(getCacheFile()), null);
        } catch (IOException e) {
            System.out.println(String.format("Could not store cache to '%s'", getCacheFile().getAbsolutePath()));
        }
    }

    private void loadCache() {
        Properties properties = new Properties();
        try {
            properties.load(new FileInputStream(getCacheFile()));
        } catch (IOException e) {
            return;
        }

        for (String key : properties.stringPropertyNames()) {
            this.persistentHashes.put(key, properties.get(key).toString());
        }
    }
}
