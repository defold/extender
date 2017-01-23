package com.defold.extender.client;

import javax.xml.bind.annotation.adapters.HexBinaryAdapter;
import java.io.File;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;

import java.security.MessageDigest;
import java.util.List;

public class ExtenderClientCache {

    private final File cacheDir;
    private static final String hashFn = "SHA-256";

    private HashMap<String, Timestamp> timestamps = new HashMap<>();
    private HashMap<String, String> hashes = new HashMap<>();

    public ExtenderClientCache(File cacheDir) {
        this.cacheDir = cacheDir;
    }

    private static String hashToString(byte[] digest)
    {
        String hex = (new HexBinaryAdapter()).marshal(digest);
        return hex;
    }

    private static String hash(File file) {
        try{
            MessageDigest md = MessageDigest.getInstance(hashFn);
            byte[] data = Files.readAllBytes(file.toPath());
            md.update(data);
            return hashToString(md.digest());
        } catch(Exception e){
            throw new RuntimeException(e);
        }
     }

    public String getHash(File file) {
        String path = file.getAbsolutePath();
        Timestamp fileTimestamp = new Timestamp(file.lastModified());
        Timestamp timestamp = this.timestamps.getOrDefault(path, null);

        if (timestamp != null && fileTimestamp.equals(timestamp) ) {
            String hash = this.hashes.getOrDefault(path, null);
            if (hash != null) {
                return hash;
            }
        }

        // Create a new hash
        String hash = ExtenderClientCache.hash(file);
        this.timestamps.put(path, fileTimestamp);
        this.hashes.put(path, hash);
        return hash;
    }

    public String getHash(List<File> files) {
        if (files.isEmpty()) {
            throw new RuntimeException("The list of files must not be empty");
        }

        files.sort(new Comparator<File>() {
            @Override
            public int compare(File o1, File o2) {
                return o1.getAbsolutePath().compareTo(o2.getAbsolutePath());
            }
        });

        MessageDigest md = null;
        try {
            md = MessageDigest.getInstance(hashFn);
        } catch(Exception e){
            throw new RuntimeException("Could not create hash function: " + hashFn, e);
        }

        for (File file : files) {
            String fileHash = getHash(file);
            md.update(fileHash.getBytes());
        }

        return hashToString(md.digest());
    }

    private static Timestamp getNewestTimestamp(List<File> files) {
        Timestamp newest = Timestamp.from(Instant.EPOCH);
        for (File file : files) {
            Timestamp ts = new Timestamp(file.lastModified());
            if (ts.after(newest)) {
                newest = ts;
            }
        }
        return newest;
    }

    public File getCachedBuildFile(String platform) {
        return new File(cacheDir + File.separator + platform + File.separator + "build.zip" );
    }

    public File isCachedBuildValid(String platform, List<File> files) {
        File f = getCachedBuildFile(platform);
        if (f.exists()) {
            Timestamp cachedBuildTimestamp = new Timestamp(f.lastModified());
            Timestamp newestSource = getNewestTimestamp(files);
            if (cachedBuildTimestamp.after(newestSource)) {
                return f;
            }
        }
        return null;
    }
}