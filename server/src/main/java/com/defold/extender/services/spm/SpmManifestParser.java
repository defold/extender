package com.defold.extender.services.spm;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.defold.extender.ExtenderException;
import com.defold.extender.utils.VersionUtil;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses and sanitizes SwiftPackages.json manifests found in extension uploads. Every value
 * that ends up in a generated Package.swift or project.yml is reconstructed from validated
 * parts — raw manifest input is never echoed into a template.
 *
 * Manifest schema:
 * {
 *   "platform": "ios",
 *   "minVersion": "13.0",
 *   "packages": [
 *     { "url": "https://github.com/firebase/firebase-ios-sdk.git",
 *       "version": "12.0.0",                      // or "from" / "branch" / "revision"
 *       "products": ["FirebaseAnalytics"] }
 *   ]
 * }
 */
public class SpmManifestParser {

    static final int MAX_MANIFEST_FILE_SIZE = 64 * 1024;
    static final int MAX_MANIFESTS = 32;
    static final int MAX_PACKAGES = 32;
    static final int MAX_PRODUCTS = 64;
    static final int MAX_URL_LENGTH = 2048;

    private static final Pattern HOST_PATTERN = Pattern.compile("[A-Za-z0-9.-]+");
    private static final Pattern PATH_PATTERN = Pattern.compile("[A-Za-z0-9._/-]+");
    private static final Pattern VERSION_PATTERN = Pattern.compile("\\d+(\\.\\d+){0,2}([-+][A-Za-z0-9.-]{1,40})?");
    private static final Pattern MIN_VERSION_PATTERN = Pattern.compile("\\d+(\\.\\d+){0,2}");
    // SwiftPM's Version literal fatalErrors on fewer than three components and a platform
    // version needs at least two; unambiguous short forms are padded rather than rejected
    private static final int VERSION_COMPONENTS = 3;
    private static final int MIN_VERSION_COMPONENTS = 2;
    private static final Pattern BRANCH_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,99}");
    private static final Pattern REVISION_PATTERN = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern PRODUCT_PATTERN = Pattern.compile("[A-Za-z0-9_.-]{1,100}");

    public enum RequirementKind { EXACT, FROM, BRANCH, REVISION }

    public static final class Requirement {
        public final RequirementKind kind;
        public final String value;

        Requirement(RequirementKind kind, String value) {
            this.kind = kind;
            this.value = value;
        }

        /** The dependency requirement as a Package.swift argument, built from validated parts only. */
        public String toSwiftArgument() {
            switch (kind) {
                case EXACT:    return String.format("exact: \"%s\"", value);
                case FROM:     return String.format("from: \"%s\"", value);
                case BRANCH:   return String.format("branch: \"%s\"", value);
                case REVISION: return String.format("revision: \"%s\"", value);
                default: throw new IllegalStateException("Unknown requirement kind " + kind);
            }
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Requirement)) {
                return false;
            }
            Requirement req = (Requirement) other;
            return kind == req.kind && value.equals(req.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(kind, value);
        }

        @Override
        public String toString() {
            return toSwiftArgument();
        }
    }

    public static final class PackageRef {
        public final String url;               // sanitized, reconstructed
        public final Requirement requirement;
        public final Set<String> products = new LinkedHashSet<>();

        PackageRef(String url, Requirement requirement) {
            this.url = url;
            this.requirement = requirement;
        }

        /** SPM package identity: the repository basename without the ".git" suffix. */
        public String label() {
            String basename = url.substring(url.lastIndexOf('/') + 1);
            if (basename.endsWith(".git")) {
                basename = basename.substring(0, basename.length() - 4);
            }
            return basename;
        }
    }

    public static final class ParseResult {
        public String platform;
        public String minVersion;
        // optional per-graph override of the wrapper linkage: "static" | "dynamic" | null
        public String wrapperType;
        // keyed by canonical URL (lowercased host, ".git" suffix stripped)
        public final Map<String, PackageRef> packages = new LinkedHashMap<>();

        public ParseResult mergeWith(ParseResult other) throws SpmManifestParsingException {
            if (platform != null && other.platform != null && !platform.equals(other.platform)) {
                throw new SpmManifestParsingException(
                    String.format("Mismatch 'platform' between Swift package manifests: %s != %s", platform, other.platform));
            }
            if (wrapperType != null && other.wrapperType != null && !wrapperType.equals(other.wrapperType)) {
                throw new SpmManifestParsingException(
                    String.format("Mismatch 'wrapperType' between Swift package manifests: %s != %s", wrapperType, other.wrapperType));
            }
            if (wrapperType == null) {
                wrapperType = other.wrapperType;
            }
            if (platform == null) {
                platform = other.platform;
            }

            if (minVersion == null) {
                minVersion = other.minVersion;
            }
            else if (other.minVersion != null) {
                try {
                    if (VersionUtil.compareVersions(other.minVersion, minVersion) > 0) {
                        minVersion = other.minVersion;
                    }
                } catch (ExtenderException e) {
                    throw new SpmManifestParsingException(
                        String.format("Failed to compare platform min versions '%s' and '%s'", minVersion, other.minVersion), e);
                }
            }

            for (Map.Entry<String, PackageRef> entry : other.packages.entrySet()) {
                PackageRef existing = packages.get(entry.getKey());
                if (existing == null) {
                    packages.put(entry.getKey(), entry.getValue());
                }
                else {
                    if (!existing.requirement.equals(entry.getValue().requirement)) {
                        throw new SpmManifestParsingException(
                            String.format("Conflicting requirements for Swift package '%s': %s != %s",
                                existing.url, existing.requirement, entry.getValue().requirement));
                    }
                    existing.products.addAll(entry.getValue().products);
                }
            }
            checkPackageCount(packages);
            checkProductCount(packages.values());
            return this;
        }
    }

    /**
     * The result is seeded with the build's platform family and default min version so
     * every manifest is validated against the platform actually being built.
     */
    public static ParseResult parseManifests(List<File> manifestFiles, String platformFamily, String defaultMinVersion)
            throws IOException, SpmManifestParsingException {
        if (manifestFiles.size() > MAX_MANIFESTS) {
            throw new SpmManifestParsingException(
                String.format("Upload declares more than %d Swift package manifests", MAX_MANIFESTS));
        }
        ParseResult result = new ParseResult();
        result.platform = platformFamily;
        if (defaultMinVersion != null) {
            if (!MIN_VERSION_PATTERN.matcher(defaultMinVersion).matches()) {
                throw new SpmManifestParsingException(
                    String.format("Invalid platform min version in the job environment: '%s'", defaultMinVersion));
            }
            result.minVersion = padVersion(defaultMinVersion, MIN_VERSION_COMPONENTS);
        }
        for (File manifestFile : manifestFiles) {
            result.mergeWith(parseManifest(manifestFile));
        }
        return result;
    }

    public static ParseResult parseManifest(File manifestFile) throws IOException, SpmManifestParsingException {
        if (manifestFile.length() > MAX_MANIFEST_FILE_SIZE) {
            throw new SpmManifestParsingException(
                String.format("Swift package manifest %s is larger than %d bytes", manifestFile.getName(), MAX_MANIFEST_FILE_SIZE));
        }

        JsonNode root;
        try {
            root = new ObjectMapper().readTree(manifestFile);
        } catch (RuntimeException e) {
            throw new SpmManifestParsingException(
                String.format("Swift package manifest %s is not valid JSON", manifestFile.getName()), e);
        }
        if (root == null || !root.isObject()) {
            throw new SpmManifestParsingException(
                String.format("Swift package manifest %s must contain a JSON object", manifestFile.getName()));
        }

        ParseResult result = new ParseResult();
        result.platform = parsePlatform(root);
        result.minVersion = parseMinVersion(root);
        result.wrapperType = parseWrapperType(root);

        JsonNode packagesNode = root.get("packages");
        if (packagesNode == null || !packagesNode.isArray() || packagesNode.isEmpty()) {
            throw new SpmManifestParsingException("Swift package manifest must declare a non-empty 'packages' list");
        }
        if (packagesNode.size() > MAX_PACKAGES) {
            throw new SpmManifestParsingException(
                String.format("Swift package manifest declares more than %d packages", MAX_PACKAGES));
        }

        for (JsonNode packageNode : packagesNode) {
            if (!packageNode.isObject()) {
                throw new SpmManifestParsingException("Each entry in 'packages' must be a JSON object");
            }
            String url = sanitizeUrl(textValue(packageNode, "url"));
            Requirement requirement = parseRequirement(packageNode, url);
            PackageRef ref = new PackageRef(url, requirement);
            ref.products.addAll(parseProducts(packageNode, url));

            String key = canonicalKey(url);
            PackageRef existing = result.packages.get(key);
            if (existing == null) {
                result.packages.put(key, ref);
            }
            else {
                if (!existing.requirement.equals(ref.requirement)) {
                    throw new SpmManifestParsingException(
                        String.format("Conflicting requirements for Swift package '%s': %s != %s",
                            url, existing.requirement, ref.requirement));
                }
                existing.products.addAll(ref.products);
            }
        }
        checkProductCount(result.packages.values());

        return result;
    }

    // the static wrapper breaks when a graph feeds the same binary library to libtool
    // twice; a dynamic wrapper links such graphs cleanly
    private static String parseWrapperType(JsonNode root) throws SpmManifestParsingException {
        JsonNode node = root.get("wrapperType");
        if (node == null || node.isNull()) {
            return null;
        }
        String wrapperType = node.isString() ? node.asString() : null;
        if (!"static".equals(wrapperType) && !"dynamic".equals(wrapperType)) {
            throw new SpmManifestParsingException(
                String.format("Invalid 'wrapperType' in Swift package manifest: '%s' (expected 'static' or 'dynamic')", node.asString()));
        }
        return wrapperType;
    }

    private static String parsePlatform(JsonNode root) throws SpmManifestParsingException {
        String platform = textValue(root, "platform");
        if (!"ios".equals(platform) && !"osx".equals(platform)) {
            throw new SpmManifestParsingException(
                String.format("Unsupported 'platform' in Swift package manifest: '%s' (expected 'ios' or 'osx')", platform));
        }
        return platform;
    }

    private static String parseMinVersion(JsonNode root) throws SpmManifestParsingException {
        JsonNode node = root.get("minVersion");
        if (node == null || node.isNull()) {
            return null;
        }
        String minVersion = node.isString() ? node.asString() : null;
        if (minVersion == null || !MIN_VERSION_PATTERN.matcher(minVersion).matches()) {
            throw new SpmManifestParsingException(
                String.format("Invalid 'minVersion' in Swift package manifest: '%s'", node.asString()));
        }
        return padVersion(minVersion, MIN_VERSION_COMPONENTS);
    }

    /** Pads a validated version to the number of components its SwiftPM grammar requires. */
    private static String padVersion(String version, int components) {
        int cut = version.length();
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (c == '-' || c == '+') {
                cut = i;
                break;
            }
        }
        StringBuilder core = new StringBuilder(version.substring(0, cut));
        for (int i = core.toString().split("\\.", -1).length; i < components; i++) {
            core.append(".0");
        }
        return core + version.substring(cut);
    }

    private static Requirement parseRequirement(JsonNode packageNode, String url) throws SpmManifestParsingException {
        Requirement requirement = null;
        for (RequirementKind kind : RequirementKind.values()) {
            String field = fieldNameFor(kind);
            JsonNode node = packageNode.get(field);
            if (node == null) {
                continue;
            }
            if (requirement != null) {
                throw new SpmManifestParsingException(
                    String.format("Swift package '%s' declares more than one of version/from/branch/revision", url));
            }
            String value = node.isString() ? node.asString() : null;
            if (value == null || !patternFor(kind).matcher(value).matches()) {
                throw new SpmManifestParsingException(
                    String.format("Invalid '%s' for Swift package '%s': '%s'", field, url, node.asString()));
            }
            if (kind == RequirementKind.EXACT || kind == RequirementKind.FROM) {
                value = padVersion(value, VERSION_COMPONENTS);
            }
            requirement = new Requirement(kind, value);
        }
        if (requirement == null) {
            throw new SpmManifestParsingException(
                String.format("Swift package '%s' must declare exactly one of version/from/branch/revision", url));
        }
        return requirement;
    }

    private static List<String> parseProducts(JsonNode packageNode, String url) throws SpmManifestParsingException {
        JsonNode productsNode = packageNode.get("products");
        if (productsNode == null || !productsNode.isArray() || productsNode.isEmpty()) {
            throw new SpmManifestParsingException(
                String.format("Swift package '%s' must declare a non-empty 'products' list", url));
        }
        List<String> products = new java.util.ArrayList<>();
        for (JsonNode productNode : productsNode) {
            String product = productNode.isString() ? productNode.asString() : null;
            if (product == null || !PRODUCT_PATTERN.matcher(product).matches()) {
                throw new SpmManifestParsingException(
                    String.format("Invalid product name for Swift package '%s': '%s'", url, productNode.asString()));
            }
            products.add(product);
        }
        return products;
    }

    private static void checkPackageCount(Map<String, PackageRef> packages) throws SpmManifestParsingException {
        if (packages.size() > MAX_PACKAGES) {
            throw new SpmManifestParsingException(
                String.format("Swift package manifests declare more than %d packages in total", MAX_PACKAGES));
        }
    }

    private static void checkProductCount(Iterable<PackageRef> packages) throws SpmManifestParsingException {
        int count = 0;
        for (PackageRef ref : packages) {
            count += ref.products.size();
        }
        if (count > MAX_PRODUCTS) {
            throw new SpmManifestParsingException(
                String.format("Swift package manifests declare more than %d products in total", MAX_PRODUCTS));
        }
    }

    private static String fieldNameFor(RequirementKind kind) {
        switch (kind) {
            case EXACT: return "version";
            case FROM: return "from";
            case BRANCH: return "branch";
            case REVISION: return "revision";
            default: throw new IllegalStateException("Unknown requirement kind " + kind);
        }
    }

    private static Pattern patternFor(RequirementKind kind) {
        switch (kind) {
            case EXACT:
            case FROM: return VERSION_PATTERN;
            case BRANCH: return BRANCH_PATTERN;
            case REVISION: return REVISION_PATTERN;
            default: throw new IllegalStateException("Unknown requirement kind " + kind);
        }
    }

    private static String textValue(JsonNode node, String field) throws SpmManifestParsingException {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.asString().isEmpty()) {
            throw new SpmManifestParsingException(
                String.format("Missing or invalid '%s' in Swift package manifest", field));
        }
        return value.asString();
    }

    /**
     * Validates a package URL and reconstructs it from its parsed parts.
     * Only plain https URLs pointing at a repository path are accepted.
     */
    static String sanitizeUrl(String rawUrl) throws SpmManifestParsingException {
        if (rawUrl.length() > MAX_URL_LENGTH) {
            throw new SpmManifestParsingException("Swift package URL is too long");
        }
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException e) {
            throw new SpmManifestParsingException(String.format("Invalid Swift package URL: '%s'", rawUrl), e);
        }
        if (!"https".equals(uri.getScheme())) {
            throw new SpmManifestParsingException(
                String.format("Swift package URL must use the https scheme: '%s'", rawUrl));
        }
        if (uri.getUserInfo() != null) {
            throw new SpmManifestParsingException(
                String.format("Swift package URL must not contain credentials: '%s'", rawUrl));
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new SpmManifestParsingException(
                String.format("Swift package URL must not use a custom port: '%s'", rawUrl));
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new SpmManifestParsingException(
                String.format("Swift package URL must not contain a query or fragment: '%s'", rawUrl));
        }
        String host = uri.getHost();
        if (host == null || !HOST_PATTERN.matcher(host).matches()) {
            throw new SpmManifestParsingException(
                String.format("Invalid host in Swift package URL: '%s'", rawUrl));
        }
        String path = uri.getPath();
        if (path == null || path.length() < 2 || !PATH_PATTERN.matcher(path).matches()) {
            throw new SpmManifestParsingException(
                String.format("Invalid repository path in Swift package URL: '%s'", rawUrl));
        }
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new SpmManifestParsingException(
                    String.format("Invalid repository path in Swift package URL: '%s'", rawUrl));
            }
        }
        return "https://" + host + path;
    }

    /** Canonical map key for a sanitized URL: lowercased host, ".git" suffix and trailing "/" stripped. */
    static String canonicalKey(String sanitizedUrl) {
        String withoutScheme = sanitizedUrl.substring("https://".length());
        int slash = withoutScheme.indexOf('/');
        String host = withoutScheme.substring(0, slash).toLowerCase();
        String path = withoutScheme.substring(slash);
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - 4);
        }
        return host + path;
    }
}
