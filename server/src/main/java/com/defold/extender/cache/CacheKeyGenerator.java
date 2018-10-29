package com.defold.extender.cache;

import org.springframework.stereotype.Service;
import sun.misc.BASE64Encoder;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

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

        return new BigInteger(1, digest.digest()).toString(16);
        //return new BASE64Encoder().encode(digest.digest());
    }

    private MessageDigest getDigest() {
        try {
            return MessageDigest.getInstance(SHA256);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Algorithm SHA-256 is not supported", e);
        }
    }
}
