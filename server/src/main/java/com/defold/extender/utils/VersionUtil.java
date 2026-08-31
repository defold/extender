package com.defold.extender.utils;

import com.defold.extender.ExtenderException;

public class VersionUtil {

    // Strip semver pre-release and build metadata (everything from the first '-' or '+'),
    // e.g. "1.0.0-beta" -> "1.0.0", "2.0.0+build.7" -> "2.0.0". Comparison ignores these segments.
    private static String stripVersionSuffix(String version) {
        int cut = version.length();
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (c == '-' || c == '+') {
                cut = i;
                break;
            }
        }
        return version.substring(0, cut);
    }

    public static int compareVersions(String version1, String version2) throws ExtenderException {
        int result = 0;
        String[] parts1 = stripVersionSuffix(version1).split("\\.");
        String[] parts2 = stripVersionSuffix(version2).split("\\.");
        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            try {
                int v1 = i < parts1.length && !parts1[i].isEmpty() ? Integer.parseInt(parts1[i]) : 0;
                int v2 = i < parts2.length && !parts2[i].isEmpty() ? Integer.parseInt(parts2[i]) : 0;
                if (v1 < v2) {
                    result = -1;
                    break;
                } else if (v1 > v2) {
                    result = 1;
                    break;
                }
            } catch (NumberFormatException exc) {
                throw new ExtenderException(exc,
                    String.format("Failed to compare versions '%s' and '%s'", version1, version2));
            }
        }
        return result;
    }
}
