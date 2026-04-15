package com.defold.extender.services.cocoapods;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PodfileParser {
    public static class ParseResult {
        public String minVersion;
        public String platform;
        public boolean useFrameworks = true;
        public Set<String> podDefinitions = new HashSet<>();
        public Set<String> podNames = new HashSet<>();

        public ParseResult() { }

        public ParseResult(String platform, String minVersion) {
            this.platform = platform;
            this.minVersion = minVersion;
        }

        public ParseResult mergeWith(ParseResult other) throws PodfileParsingException {
            if (minVersion == null) {
                minVersion = other.minVersion;
            } else if (other.minVersion != null && compareVersions(minVersion, other.minVersion) < 0) {
                minVersion = other.minVersion;
            }


            if (platform == null) {
                platform = other.platform;
            } else if (other.platform != null && !platform.equals(other.platform)) {
                throw new PodfileParsingException(String.format("Mismatch 'platform': %s!=%s", platform, other.platform));
            }

            useFrameworks = useFrameworks || other.useFrameworks;

            podDefinitions.addAll(other.podDefinitions);
            podNames.addAll(other.podNames);
            return this;
        }
    }

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

    // https://www.baeldung.com/java-comparing-versions#customSolution
    static int compareVersions(String version1, String version2) throws PodfileParsingException {
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
                throw new PodfileParsingException(
                    String.format("Failed to compare pod versions '%s' and '%s'", version1, version2), exc);
            }
        }
        return result;
    }

    // Matches pod name (alphanumeric, hyphens, underscores, dots, plus signs) with optional subspec paths
    // e.g. "pod 'Firebase/Analytics'" or "pod 'OneTrust-CMP-XCFramework'"
    private static final Pattern POD_NAME_PATTERN = Pattern.compile("[\\w.+][\\w.+/-]*");

    // Matches version operator + version string, including pre-release/build metadata segments
    // e.g. "~>1.0", ">=2.3.1", "<=4.3.28.3", "1.0.0-beta", "2.0.0-rc.1"
    private static final Pattern POD_VERSION_PATTERN = Pattern.compile("[~><=.-]*\\s*[\\d][\\w.+-]*");

    // Full pod line pattern: pod 'Name' with optional version, optional trailing comment
    // Group 1: pod name (with optional subspec)
    // Group 2: optional version constraint (operator + version)
    private static final Pattern POD_LINE_PATTERN = Pattern.compile(
        "pod\\s+'(" + POD_NAME_PATTERN.pattern() + ")'(?:\\s*,\\s*'(" + POD_VERSION_PATTERN.pattern() + ")')?\\s*(?:#.*)?");

    /**
     * Sanitize a pod definition line by parsing and reconstructing it.
     * This prevents injection of arbitrary Ruby code through crafted Podfile lines.
     * Only the pod name and optional version constraint are preserved.
     */
    static String sanitizePodDefinition(String podName, String podVersion) {
        if (podVersion != null) {
            return String.format("pod '%s', '%s'", podName, podVersion);
        }
        return String.format("pod '%s'", podName);
    }

    public static ParseResult parsePodfile(File podFile) throws IOException, PodfileParsingException {
        ParseResult res = new ParseResult();
        // Split each file into lines and go through them one by one
        // Search for a Podfile platform and version configuration, examples:
        //   platform :ios, '9.0'
        //   platform :osx, '10.2'
        // Get the version and figure out which is the highest version defined. This
        // version will be used in the combined Podfile created by this function.
        // Treat everything else as pods
        List<String> lines = Files.readAllLines(podFile.toPath());
        for (String line : lines) {
            // Strip inline comments and trim whitespace before processing
            String stripped = line.contains("#") ? line.substring(0, line.indexOf('#')).trim() : line.trim();
            if (stripped.isEmpty()) {
                continue;
            }
            if (stripped.startsWith("platform :")) {
                if (res.platform != null) {
                    throw new PodfileParsingException("'platform' is already defined.");
                }
                if (!stripped.contains(":ios") && !stripped.contains(":osx")) {
                    throw new PodfileParsingException("Unsupported 'platform'");
                }
                res.platform = stripped.contains(":ios") ? "ios" : "osx";
                String version = stripped.replaceFirst("platform :ios|platform :osx", "").replace(",", "").replace("'", "").trim();
                if (!version.isEmpty()) {
                    res.minVersion = version;
                }
            } else if (stripped.startsWith("use_frameworks!")) {
                res.useFrameworks = true;
            } else {
                Matcher matcher = POD_LINE_PATTERN.matcher(stripped);
                if (matcher.matches()) {
                    String podName = matcher.group(1);
                    String podVersion = matcher.group(2);
                    // Extract the top-level pod name (before any subspec slash)
                    String topLevelName = podName.contains("/") ? podName.substring(0, podName.indexOf('/')) : podName;
                    // Reconstruct a safe pod definition line from parsed components only
                    res.podDefinitions.add(sanitizePodDefinition(podName, podVersion));
                    res.podNames.add(topLevelName);
                }
            }
        }
        return res;
    }
}
