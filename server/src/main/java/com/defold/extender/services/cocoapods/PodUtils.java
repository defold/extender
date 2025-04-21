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
}
