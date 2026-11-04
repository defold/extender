package com.defold.extender;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.defold.extender.utils.VersionUtil;
import tools.jackson.databind.ObjectMapper;

// Picks the Swift runtime libraries an application has to bundle, the way Xcode does with
// swift-stdlib-tool --filter-for-swift-os --back-deploy-swift-concurrency --back-deploy-swift-span:
// the Swift runtime of the OS is used wherever the minimum OS version includes it, so only the
// compatibility libraries that the SDK lists as not yet part of that OS are copied. Their
// dependencies on the core runtime resolve to the OS through the /usr/lib/swift rpath.
final class SwiftRuntimeLibraries {
    private static final Pattern LIBRARY_NAME = Pattern.compile("libswift[A-Za-z0-9_]*\\.dylib");

    private final String osRuntimeMinVersion;
    private final Map<String, String> compatibilityLibraries;

    SwiftRuntimeLibraries(String osRuntimeMinVersion, Map<String, String> compatibilityLibraries) {
        this.osRuntimeMinVersion = osRuntimeMinVersion;
        this.compatibilityLibraries = compatibilityLibraries;
    }

    // Reads the Swift support of one target (e.g. "iphoneos") from the SDKSettings.json of an SDK.
    // Returns null if the SDK does not describe it.
    @SuppressWarnings("unchecked")
    static SwiftRuntimeLibraries fromSdkSettings(File sdkSettings, String target) throws ExtenderException {
        Map<String, Object> settings;
        try {
            settings = new ObjectMapper().readValue(sdkSettings, Map.class);
        } catch (RuntimeException e) {
            throw new ExtenderException(e, String.format("Failed to read %s", sdkSettings));
        }

        Object supportedTargets = settings.get("SupportedTargets");
        if (!(supportedTargets instanceof Map) || !(((Map<String, Object>) supportedTargets).get(target) instanceof Map)) {
            return null;
        }
        Map<String, Object> targetSettings = (Map<String, Object>) ((Map<String, Object>) supportedTargets).get(target);

        Object runtimeMinVersion = targetSettings.get("SwiftOSRuntimeMinimumDeploymentTarget");
        Object libraries = targetSettings.get("SwiftCompatibilityLibraryNotNeededStartingIn");
        if (!(runtimeMinVersion instanceof String) || !(libraries instanceof Map)) {
            return null;
        }

        Map<String, String> compatibilityLibraries = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : ((Map<String, Object>) libraries).entrySet()) {
            if (entry.getValue() instanceof String) {
                compatibilityLibraries.put(entry.getKey(), (String) entry.getValue());
            }
        }
        return new SwiftRuntimeLibraries((String) runtimeMinVersion, compatibilityLibraries);
    }

    // The libraries listed in the output of swift-stdlib-tool --print, one path per line. The
    // output of the command also carries its log line, which does not name a library.
    static List<File> parseToolOutput(String output) {
        List<File> libraries = new ArrayList<>();
        for (String line : output.split("\r\n|\r|\n")) {
            File file = new File(line.trim());
            if (file.isAbsolute() && LIBRARY_NAME.matcher(file.getName()).matches()) {
                libraries.add(file);
            }
        }
        return libraries;
    }

    // Below this version the OS has no Swift runtime at all, which would have to be bundled whole.
    void checkMinOsVersion(String minOsVersion) throws ExtenderException {
        if (VersionUtil.compareVersions(minOsVersion, osRuntimeMinVersion) < 0) {
            throw new ExtenderException(String.format(
                    "Swift code needs the Swift runtime of the OS, which is available from version %s, but this build targets version %s. Swift sources are not supported for this minimum OS version.",
                    osRuntimeMinVersion, minOsVersion));
        }
    }

    // The libraries that have to be bundled for the minimum OS version, out of the ones a binary refers to
    List<File> select(List<File> referenced, String minOsVersion) throws ExtenderException {
        Map<String, File> selected = new LinkedHashMap<>();
        for (File library : referenced) {
            String notNeededStartingIn = compatibilityLibraries.get(library.getName());
            if (notNeededStartingIn != null && VersionUtil.compareVersions(minOsVersion, notNeededStartingIn) < 0) {
                selected.putIfAbsent(library.getName(), library);
            }
        }
        return new ArrayList<>(selected.values());
    }
}
