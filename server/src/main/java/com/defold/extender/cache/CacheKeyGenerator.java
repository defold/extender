package com.defold.extender.cache;

import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

@Service
public class CacheKeyGenerator {

    private static final String SHA256 = "SHA-256";

    public String generate(File file) throws IOException {
        int count;

        MessageDigest digest = getDigest();

        try(BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[8192];
            while ((count = bis.read(buffer)) > 0) {
                digest.update(buffer, 0, count);
            }
        }

        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
    }

    private MessageDigest getDigest() {
        try {
            return MessageDigest.getInstance(SHA256);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Algorithm SHA-256 is not supported", e);
        }
    }
}
