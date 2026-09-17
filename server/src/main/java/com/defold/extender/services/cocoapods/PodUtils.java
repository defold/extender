package com.defold.extender.services.cocoapods;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;

import com.defold.extender.ExtenderException;
import com.defold.extender.ExtenderUtil;

public class PodUtils {
    public enum Platform {
        IPHONEOS,
        IPHONESIMULATOR,
        MACOSX,
        UNKNOWN;

        public static Platform fromExtenderPlatform(String extenderTargetPlatform) {
            if (ExtenderUtil.isMacOSTarget(extenderTargetPlatform)) {
                return MACOSX;
            }
            if (ExtenderUtil.isIOSTarget(extenderTargetPlatform)) {
                return extenderTargetPlatform.equals("arm64-ios") ? IPHONEOS : IPHONESIMULATOR;
            }
            return UNKNOWN;
        }
    }

    static boolean isIOS(Platform platform) {
        return platform == Platform.IPHONEOS || platform == Platform.IPHONESIMULATOR;
    }

    static boolean isMacOS(Platform platform) {
        return platform == Platform.MACOSX;
    }

    static List<File> parametrizedListFileGlob(File dir, String pattern, BiFunction<File, String, List<File>> listFunction) {
        String absPathPattern = Path.of(dir.getAbsolutePath(), pattern).toString();
        List<File> files = listFunction.apply(dir, absPathPattern);
        // Cocoapods uses Ruby where glob patterns are treated slightly differently:
        // Ruby: foo/**/*.h will find .h files in any subdirectory of foo AND in foo/
        // Java: foo/**/*.h will find .h files in any subdirectory of foo but NOT in foo/
        if (absPathPattern.contains("/**/")) {
            absPathPattern = absPathPattern.replaceFirst("\\/\\*\\*\\/", "/");
            files.addAll(listFunction.apply(dir, absPathPattern));
        }
        return files;
    }
    /*
     * @dir File Directory where files will be searched
     * @patter String Path pattern
    */
    static List<File> listFilesGlob(File dir, String pattern) {
        return parametrizedListFileGlob(dir, pattern, ExtenderUtil::listFilesGlob);
    }

    static List<File> listFilesAndDirsGlob(File dir, String pattern) {
        return parametrizedListFileGlob(dir, pattern, ExtenderUtil::listFilesAndDirsGlob);
    }

    static String sanitizePodName(String podName) {
        String sanitizedName = podName;
        sanitizedName = sanitizedName.replace("+", "\\+");
        return sanitizedName;
    }

    static String[] toPlistPlatforms(String[] platforms) {
        String[] result = new String[platforms.length];
        for (int idx = 0; idx < platforms.length; ++idx) {
            switch (Platform.fromExtenderPlatform(platforms[idx])) {
                case IPHONEOS: result[idx] = "iPhoneOS"; break;
                case IPHONESIMULATOR: result[idx] = "iPhoneSimulator"; break;
                case MACOSX: result[idx] = "MacOSX"; break;
                default: break;
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

    static String getSpecName(String podlockRecord) {
        return podlockRecord.replaceFirst(" \\(.*\\)", "");
    }

    static String getSpecVersion(String podlockRecord) {
        return podlockRecord.replaceFirst(".*\\(", "").replace(")", "");
    }

    static String getPodName(String podlockRecord) {
        String podnameparts[] = PodUtils.splitPodname(PodUtils.getSpecName(podlockRecord));
        return PodUtils.sanitizePodName(podnameparts[0]);
    }

    public static boolean hasSourceFiles(PodBuildSpec spec) {
        return !spec.sourceFiles.isEmpty() || !spec.swiftSourceFiles.isEmpty();
    }

    // Apple's architecture name for the target. The simulator encodes itself in the Defold
    // architecture token (arm64_sim-ios) but is a plain arm64 build to clang, ld and xcodebuild.
    public static String archFromPlatform(String extenderTargetPlatform) {
        if (extenderTargetPlatform.equals("arm64_sim-ios")) {
            return "arm64";
        }
        return extenderTargetPlatform.split("-")[0];
    }

    public static String swiftModuleNameFromPlatform(String extenderTargetPlatform) throws ExtenderException {
        switch(extenderTargetPlatform) {
            case "arm64-ios": return "arm64-apple-ios";
            case "arm64_sim-ios": return "arm64-apple-ios-simulator";
            case "x86_64-ios": return "x86_64-apple-ios-simulator";
            case "arm64-osx":
            case "arm64-macos": return "arm64-apple-macos";
            case "x86_64-osx":
            case "x86_64-macos": return "x86_64-apple-macos";
            default:
                throw new ExtenderException(String.format("Invalid platform input for swift module name %s", extenderTargetPlatform));
        }
    }
}
