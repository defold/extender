package com.defold.extender.services.cocoapods;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import com.defold.extender.ExtenderUtil;

public class PodUtils {

    /*
     * @dir File Directory where files will be searched
     * @patter String Path pattern
    */
    static List<File> listFilesGlob(File dir, String pattern) {
        String absPathPattern = Path.of(dir.getAbsolutePath(), pattern).toString();
        List<File> files = ExtenderUtil.listFilesGlob(dir, absPathPattern);
        // Cocoapods uses Ruby where glob patterns are treated slightly differently:
        // Ruby: foo/**/*.h will find .h files in any subdirectory of foo AND in foo/
        // Java: foo/**/*.h will find .h files in any subdirectory of foo but NOT in foo/
        if (absPathPattern.contains("/**/")) {
            absPathPattern = absPathPattern.replaceFirst("\\/\\*\\*\\/", "/");
            files.addAll(ExtenderUtil.listFilesGlob(dir, absPathPattern));
        }
        return files;
    }

    static String sanitizePodName(String podName) {
        String sanitizedName = podName;
        sanitizedName = sanitizedName.replace("+", "\\+");
        return sanitizedName;
    }

    static String[] toPlistPlatforms(String[] platforms) {
        String[] result = new String[platforms.length];
        for (int idx = 0; idx < platforms.length; ++idx) {
            String platform = platforms[idx];
            if (platform.equals("arm64-ios")) {
                result[idx] = "iPhoneOS";
            } else if (platform.equals("x86_64-ios")) {
                result[idx] = "iPhoneSimulator";
            } else if (platform.contains("macos")) {
                result[idx] = "MacOSX";
            }
        }
        return result;
    }

    static boolean isHeaderFile(String filename) {
        return filename.endsWith(".h")
            || filename.endsWith(".hh")
            || filename.endsWith(".hpp")
            || filename.endsWith(".hxx")
            || filename.endsWith(".def");
    }

    // 'GoogleUtilities/Environment (7.10.0)'  -> 'GoogleUtilities/Environment' -> ['GoogleUtilities', 'Environment']
    static String[] splitPodname(String pod) {
        return pod.replaceFirst(" \\(.*\\)", "").split("/");
    }

}
