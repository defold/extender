package com.defold.extender.services.data;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record ResolvedSdk(String hash, String targetPlatform, String sdkName, String sdkVersion,
                          URI mappingsUri, URI archiveUri) {
    public String cacheKey() {
        return cacheKey(archiveUri);
    }

    public static String cacheKey(URI archiveUri) {
        return "sdk-" + digest(archiveUri.toString());
    }

    public static String sourceId(URI mappingsUri, URI archiveUri) {
        return digest(mappingsUri + "\n" + archiveUri);
    }

    public SdkSelection selection() {
        return archiveUri == null ? null : new SdkSelection(sourceId(mappingsUri, archiveUri), sdkName, sdkVersion);
    }

    // Diagnostics must not include configured URL credentials or signed query strings.
    @Override
    public String toString() {
        return "ResolvedSdk[hash=" + hash + ", platform=" + targetPlatform + ", selection=" + selection() + "]";
    }

    public static String digest(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
