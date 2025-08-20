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

    // https://www.baeldung.com/java-comparing-versions#customSolution
    static int compareVersions(String version1, String version2) {
        int result = 0;
        String[] parts1 = version1.split("\\.");
        String[] parts2 = version2.split("\\.");
        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            Integer v1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            Integer v2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
            int compare = v1.compareTo(v2);
            if (compare != 0) {
                result = compare;
                break;
            }
        }
        return result;
    }

    public static ParseResult parsePodfile(File podFile) throws IOException, PodfileParsingException {
        ParseResult res = new ParseResult();
        // Load all Podfiles
        Pattern podPattern = Pattern.compile("pod '([\\w|-]+)'.*");
        // Split each file into lines and go through them one by one
        // Search for a Podfile platform and version configuration, examples:
        //   platform :ios, '9.0'
        //   platform :osx, '10.2'
        // Get the version and figure out which is the highest version defined. This
        // version will be used in the combined Podfile created by this function. 
        // Treat everything else as pods
        List<String> lines = Files.readAllLines(podFile.toPath());
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("platform :")) {
                if (res.platform != null) {
                    throw new PodfileParsingException("'platform' is already defined.");
                }
                if (!line.contains(":ios") && !line.contains(":osx")) {
                    throw new PodfileParsingException("Unsupported 'platform'");
                }
                res.platform = line.contains(":ios") ? "ios" : "osx";
                String version = line.replaceFirst("platform :ios|platform :osx", "").replace(",", "").replace("'", "").trim();
                if (!version.isEmpty()) {
                    res.minVersion = version;
                }
            } else if (line.startsWith("use_frameworks!")) {
                res.useFrameworks = true;
            } else {
                Matcher matcher = podPattern.matcher(line);
                if (matcher.matches()) {
                    res.podDefinitions.add(line);
                    res.podNames.add(matcher.group(1));
                }
            }
        }
        return res;
    }
}
