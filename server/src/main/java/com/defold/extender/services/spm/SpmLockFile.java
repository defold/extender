package com.defold.extender.services.spm;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the pins of an uploaded {@code Package.resolved} (SwiftPM lock file, formats 1 to 3).
 *
 * The lock file is optional: the build discovers the graph round by round from the output of
 * xcodebuild. When one is uploaded its pins name the whole graph up front, so every repository
 * can be mirrored before the first round and the graph closes in it. Only remote source-control
 * pins can be mirrored; registry and local pins (which the wrapper cannot reference anyway)
 * are ignored.
 * Locations are client input and go through {@link SpmManifestParser#sanitizeUrl(String)}
 * like the manifest URLs.
 */
final class SpmLockFile {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpmLockFile.class);
    static final long MAX_FILE_SIZE = 1024 * 1024;
    static final int MAX_PINS = 256;
    private static final Pattern REVISION_PATTERN = Pattern.compile("[0-9a-f]{40}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final class Pin {
        /** Sanitized https URL exactly as SwiftPM will ask git for it. */
        final String location;
        final String revision;
        /** Version or branch the pin was resolved from, for messages only; may be null. */
        final String label;

        Pin(String location, String revision, String label) {
            this.location = location;
            this.revision = revision;
            this.label = label;
        }

        @Override
        public String toString() {
            return location + "@" + revision.substring(0, 12) + (label != null ? " (" + label + ")" : "");
        }
    }

    private SpmLockFile() {
    }

    /** Pins keyed by {@link SpmManifestParser#canonicalKey(String)}; insertion order is the file order. */
    static Map<String, Pin> parse(File lockFile) throws IOException, SpmManifestParsingException {
        if (!lockFile.isFile()) {
            throw new SpmManifestParsingException("Package.resolved not found: " + lockFile);
        }
        if (lockFile.length() > MAX_FILE_SIZE) {
            throw new SpmManifestParsingException(
                String.format("Package.resolved is too large (%d bytes, max %d)", lockFile.length(), MAX_FILE_SIZE));
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(lockFile);
        } catch (RuntimeException e) {
            throw new SpmManifestParsingException("Package.resolved is not valid JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new SpmManifestParsingException("Package.resolved must be a JSON object");
        }
        JsonNode pins;
        boolean legacy;
        if (root.has("pins")) {
            pins = root.get("pins");
            legacy = false;
        } else if (root.has("object") && root.get("object").has("pins")) {
            pins = root.get("object").get("pins");
            legacy = true;
        } else {
            throw new SpmManifestParsingException("Package.resolved has no 'pins' array");
        }
        if (!pins.isArray()) {
            throw new SpmManifestParsingException("Package.resolved 'pins' must be an array");
        }
        if (pins.size() > MAX_PINS) {
            throw new SpmManifestParsingException(
                String.format("Package.resolved pins %d packages, max %d", pins.size(), MAX_PINS));
        }
        Map<String, Pin> result = new LinkedHashMap<>();
        for (JsonNode pinNode : pins) {
            Pin pin = parsePin(pinNode, legacy);
            if (pin == null) {
                continue;
            }
            String key = SpmManifestParser.canonicalKey(pin.location);
            Pin previous = result.putIfAbsent(key, pin);
            if (previous != null && !previous.revision.equals(pin.revision)) {
                throw new SpmManifestParsingException(
                    String.format("Package.resolved pins '%s' twice with different revisions", key));
            }
        }
        return result;
    }

    /** Null for a pin that cannot be mirrored (registry or local package); the wrapper never needs those. */
    private static Pin parsePin(JsonNode pinNode, boolean legacy) throws SpmManifestParsingException {
        if (!pinNode.isObject()) {
            throw new SpmManifestParsingException("Package.resolved pin must be an object");
        }
        String rawLocation;
        if (legacy) {
            rawLocation = text(pinNode, "repositoryURL");
        } else {
            String kind = text(pinNode, "kind");
            if (!"remoteSourceControl".equals(kind)) {
                LOGGER.info("Ignoring Package.resolved pin '{}' of kind '{}': only remote source control packages are mirrored",
                    pinNode.path("identity").asString(), kind);
                return null;
            }
            rawLocation = text(pinNode, "location");
        }
        String location = SpmManifestParser.sanitizeUrl(rawLocation);

        JsonNode state = pinNode.get("state");
        if (state == null || !state.isObject()) {
            throw new SpmManifestParsingException("Package.resolved pin for " + location + " has no state");
        }
        String revision = text(state, "revision");
        if (!REVISION_PATTERN.matcher(revision).matches()) {
            throw new SpmManifestParsingException(
                String.format("Package.resolved pin for %s has an invalid revision '%s'", location, revision));
        }
        String label = null;
        for (String field : List.of("version", "branch")) {
            JsonNode node = state.get(field);
            if (node != null && node.isString() && !node.asString().isBlank()) {
                label = node.asString();
                break;
            }
        }
        return new Pin(location, revision, label);
    }

    private static String text(JsonNode node, String field) throws SpmManifestParsingException {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new SpmManifestParsingException("Package.resolved pin is missing '" + field + "'");
        }
        return value.asString();
    }
}
