package com.defold.extender.services.spm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.defold.extender.services.spm.SpmManifestParser.PackageRef;
import com.defold.extender.services.spm.SpmManifestParser.ParseResult;
import com.defold.extender.services.spm.SpmManifestParser.RequirementKind;

public class SpmManifestParserTest {

    private static File fixture(String name) {
        return new File("test-data/swiftpackages/" + name);
    }

    @Test
    public void testRegularIosManifest() throws IOException, SpmManifestParsingException {
        ParseResult result = SpmManifestParser.parseManifests(List.of(fixture("regular_ios.json")), "ios", "11.0");
        assertEquals("ios", result.platform);
        assertEquals("13.0", result.minVersion);
        assertEquals(2, result.packages.size());

        PackageRef firebase = result.packages.get("github.com/firebase/firebase-ios-sdk");
        assertNotNull(firebase);
        assertEquals("https://github.com/firebase/firebase-ios-sdk.git", firebase.url);
        assertEquals("firebase-ios-sdk", firebase.label());
        assertEquals(RequirementKind.EXACT, firebase.requirement.kind);
        assertEquals("exact: \"12.0.0\"", firebase.requirement.toSwiftArgument());
        assertEquals(Set.of("FirebaseAnalytics", "FirebaseRemoteConfig"), firebase.products);

        PackageRef sentry = result.packages.get("github.com/getsentry/sentry-cocoa");
        assertNotNull(sentry);
        assertEquals(RequirementKind.FROM, sentry.requirement.kind);
        assertEquals("from: \"9.0.0\"", sentry.requirement.toSwiftArgument());
    }

    @Test
    public void testRegularOsxManifest() throws IOException, SpmManifestParsingException {
        ParseResult result = SpmManifestParser.parseManifests(List.of(fixture("regular_osx.json")), "osx", "10.15");
        assertEquals("osx", result.platform);
        assertEquals("11.0", result.minVersion);
        PackageRef ref = result.packages.values().iterator().next();
        assertEquals("swift-argument-parser", ref.label());
    }

    @Test
    public void testBranchAndRevisionRequirements() throws IOException, SpmManifestParsingException {
        ParseResult branch = SpmManifestParser.parseManifest(fixture("branch.json"));
        assertEquals("branch: \"release/1.1\"", branch.packages.values().iterator().next().requirement.toSwiftArgument());

        ParseResult revision = SpmManifestParser.parseManifest(fixture("revision.json"));
        assertEquals("revision: \"94cf62b3ba8d4bed62680341ce53483da1d0c1a7\"",
            revision.packages.values().iterator().next().requirement.toSwiftArgument());
    }

    @Test
    public void testDefaultMinVersionKeptWhenManifestHasNone() throws IOException, SpmManifestParsingException {
        ParseResult result = SpmManifestParser.parseManifests(List.of(fixture("branch.json")), "ios", "11.0");
        assertEquals("11.0", result.minVersion);
    }

    @Test
    public void testMergeManifests() throws IOException, SpmManifestParsingException {
        // merge_extra declares firebase without ".git" and with different host casing plus one extra
        // product; the canonical key must fold it into the same package. minVersion is the max.
        ParseResult result = SpmManifestParser.parseManifests(
            List.of(fixture("regular_ios.json"), fixture("merge_extra.json")), "ios", "11.0");
        assertEquals("15.0", result.minVersion);
        assertEquals(3, result.packages.size());
        PackageRef firebase = result.packages.get("github.com/firebase/firebase-ios-sdk");
        assertEquals(Set.of("FirebaseAnalytics", "FirebaseRemoteConfig", "FirebaseCrashlytics"), firebase.products);
    }

    @Test
    public void testMergeConflictingRequirements() {
        assertThrows(SpmManifestParsingException.class, () -> SpmManifestParser.parseManifests(
            List.of(fixture("regular_ios.json"), fixture("merge_conflict.json")), "ios", "11.0"));
    }

    @Test
    public void testPlatformMismatchWithBuildPlatform() {
        assertThrows(SpmManifestParsingException.class, () -> SpmManifestParser.parseManifests(
            List.of(fixture("regular_ios.json")), "osx", "11.0"));
    }

    private static Stream<Arguments> rejectedManifests() {
        return Stream.of(
            Arguments.of("url_file_scheme.json"),
            Arguments.of("url_ssh_scp.json"),
            Arguments.of("url_userinfo.json"),
            Arguments.of("url_backtick.json"),
            Arguments.of("url_dollar_paren.json"),
            Arguments.of("url_quote_breakout.json"),
            Arguments.of("url_newline.json"),
            Arguments.of("url_traversal.json"),
            Arguments.of("url_custom_port.json"),
            Arguments.of("url_query.json"),
            Arguments.of("product_swift_injection.json"),
            Arguments.of("version_injection.json"),
            Arguments.of("branch_injection.json"),
            Arguments.of("revision_short.json"),
            Arguments.of("both_version_and_branch.json"),
            Arguments.of("no_requirement.json"),
            Arguments.of("empty_products.json"),
            Arguments.of("oversized_packages.json"),
            Arguments.of("bad_json.json"),
            Arguments.of("wrong_platform_value.json"),
            Arguments.of("wrapper_type_bad.json")
        );
    }

    @Test
    public void testWrapperTypeParsed() throws IOException, SpmManifestParsingException {
        ParseResult result = SpmManifestParser.parseManifest(fixture("wrapper_type_dynamic.json"));
        assertEquals("dynamic", result.wrapperType);
        // absent by default, and survives a merge with a manifest that has none
        ParseResult merged = SpmManifestParser.parseManifests(
            List.of(fixture("regular_ios.json"), fixture("wrapper_type_dynamic.json")), "ios", "11.0");
        assertEquals("dynamic", merged.wrapperType);
    }

    @Tag("security")
    @ParameterizedTest
    @MethodSource("rejectedManifests")
    public void testManifestRejected(String fixtureName) {
        assertThrows(SpmManifestParsingException.class, () -> SpmManifestParser.parseManifest(fixture(fixtureName)));
    }

    @Tag("security")
    @Test
    public void testSanitizeUrlReconstructs() throws SpmManifestParsingException {
        assertEquals("https://github.com/foo/bar.git", SpmManifestParser.sanitizeUrl("https://github.com/foo/bar.git"));
        assertEquals("https://github.com/foo/bar", SpmManifestParser.sanitizeUrl("https://github.com/foo/bar"));
    }

    private static File writeManifest(File dir, String name, String minVersion, int firstPackage, int count)
            throws IOException {
        StringBuilder json = new StringBuilder("{ \"platform\": \"ios\", \"minVersion\": \"" + minVersion + "\", \"packages\": [");
        for (int i = 0; i < count; i++) {
            json.append(i > 0 ? ", " : "");
            json.append(String.format(
                "{ \"url\": \"https://github.com/foo/repo%d.git\", \"version\": \"1.0.0\", \"products\": [\"P%d\"] }",
                firstPackage + i, firstPackage + i));
        }
        json.append("] }");
        File manifest = new File(dir, name);
        Files.writeString(manifest.toPath(), json.toString());
        return manifest;
    }

    @Test
    public void testShortVersionsPaddedToSwiftPmGrammar(@TempDir File tmpDir) throws IOException, SpmManifestParsingException {
        // SwiftPM's Version literal fatalErrors on "1.0" and a platform rejects .iOS("13");
        // both are unambiguous, so they are padded here rather than failing inside xcodebuild
        File manifest = new File(tmpDir, "SwiftPackages.json");
        Files.writeString(manifest.toPath(),
            "{ \"platform\": \"ios\", \"minVersion\": \"13\", \"packages\": ["
            + " { \"url\": \"https://github.com/foo/bar.git\", \"version\": \"1.0\", \"products\": [\"X\"] },"
            + " { \"url\": \"https://github.com/foo/baz.git\", \"from\": \"9\", \"products\": [\"Y\"] } ] }");

        ParseResult result = SpmManifestParser.parseManifest(manifest);
        assertEquals("13.0", result.minVersion);
        assertEquals("exact: \"1.0.0\"", result.packages.get("github.com/foo/bar").requirement.toSwiftArgument());
        assertEquals("from: \"9.0.0\"", result.packages.get("github.com/foo/baz").requirement.toSwiftArgument());
    }

    @Test
    public void testPrereleaseVersionKeepsSuffixWhenPadded(@TempDir File tmpDir) throws IOException, SpmManifestParsingException {
        File manifest = new File(tmpDir, "SwiftPackages.json");
        Files.writeString(manifest.toPath(),
            "{ \"platform\": \"ios\", \"packages\": ["
            + " { \"url\": \"https://github.com/foo/bar.git\", \"version\": \"2.0-beta.1\", \"products\": [\"X\"] } ] }");
        assertEquals("exact: \"2.0.0-beta.1\"",
            SpmManifestParser.parseManifest(manifest).packages.get("github.com/foo/bar").requirement.toSwiftArgument());
    }

    @Test
    public void testMergedPackageCountCapped(@TempDir File tmpDir) throws IOException {
        // the per-file cap says nothing about the total: two legal manifests must not
        // add up to more packages than a single one is allowed to declare
        File first = writeManifest(tmpDir, "first.json", "13.0", 0, SpmManifestParser.MAX_PACKAGES);
        File second = writeManifest(tmpDir, "second.json", "13.0", SpmManifestParser.MAX_PACKAGES, 1);
        assertThrows(SpmManifestParsingException.class,
            () -> SpmManifestParser.parseManifests(List.of(first, second), "ios", "11.0"));
    }

    @Test
    public void testManifestCountCapped(@TempDir File tmpDir) throws IOException {
        List<File> manifests = new ArrayList<>();
        for (int i = 0; i <= SpmManifestParser.MAX_MANIFESTS; i++) {
            manifests.add(writeManifest(tmpDir, "m" + i + ".json", "13.0", 0, 1));
        }
        assertThrows(SpmManifestParsingException.class,
            () -> SpmManifestParser.parseManifests(manifests, "ios", "11.0"));
    }

    @Test
    public void testInvalidDefaultMinVersionRejected() {
        assertThrows(SpmManifestParsingException.class, () -> SpmManifestParser.parseManifests(
            List.of(fixture("regular_ios.json")), "ios", "11.0\"), .iOS(\"9"));
    }

    @Test
    public void testCanonicalKey() {
        assertEquals("github.com/foo/bar", SpmManifestParser.canonicalKey("https://github.com/foo/bar.git"));
        assertEquals("github.com/foo/bar", SpmManifestParser.canonicalKey("https://GitHub.com/foo/bar"));
        assertEquals("github.com/foo/bar", SpmManifestParser.canonicalKey("https://github.com/foo/bar/"));
    }
}
