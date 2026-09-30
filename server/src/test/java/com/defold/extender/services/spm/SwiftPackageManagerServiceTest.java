package com.defold.extender.services.spm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.defold.extender.process.SandboxPolicy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import org.springframework.core.io.FileSystemResource;

import com.defold.extender.ExtenderException;
import com.defold.extender.services.cocoapods.PodUtils;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@EnabledOnOs({OS.MAC})
public class SwiftPackageManagerServiceTest {

    private SwiftPackageManagerService createService() throws IOException {
        SwiftPackageManagerService service = new SwiftPackageManagerService(
            new FileSystemResource("src/main/resources/template.package-swift"),
            new FileSystemResource("src/main/resources/template.project-yml"),
            new SpmServiceConfiguration(),
            new SimpleMeterRegistry());
        service.swiftVersion = "6.0";
        service.wrapperMachOType = "staticlib";
        service.defaultDeveloperDir = "/Applications/Xcode.app/Contents/Developer";
        service.xcodegenPath = new File("/opt/homebrew/bin/xcodegen").exists() ? "/opt/homebrew/bin/xcodegen" : "xcodegen";
        service.mirrorRefreshIntervalMillis = 600_000;
        return service;
    }

    @Test
    public void testGeneratePackageSwift() throws IOException, SpmManifestParsingException {
        SwiftPackageManagerService service = createService();
        SpmManifestParser.ParseResult manifest = SpmManifestParser.parseManifests(
            List.of(new File("test-data/swiftpackages/regular_ios.json")), "ios", "11.0");

        String packageSwift = service.generatePackageSwift(manifest, true);

        assertTrue(packageSwift.contains("name: \"SpmDeps\""));
        assertTrue(packageSwift.contains(".iOS(\"13.0\")"));
        assertTrue(packageSwift.contains(".library("));
        assertTrue(packageSwift.contains("type: .static"));
        assertTrue(packageSwift.contains(".package(url: \"https://github.com/firebase/firebase-ios-sdk.git\", exact: \"12.0.0\"),"));
        assertTrue(packageSwift.contains(".package(url: \"https://github.com/getsentry/sentry-cocoa.git\", from: \"9.0.0\"),"));
        assertTrue(packageSwift.contains(".product(name: \"FirebaseAnalytics\", package: \"firebase-ios-sdk\"),"));
        assertTrue(packageSwift.contains(".product(name: \"FirebaseRemoteConfig\", package: \"firebase-ios-sdk\"),"));
        assertTrue(packageSwift.contains(".product(name: \"Sentry\", package: \"sentry-cocoa\"),"));
    }

    @Test
    public void testGenerateProjectYml() throws IOException, SpmManifestParsingException {
        SwiftPackageManagerService service = createService();
        SpmManifestParser.ParseResult manifest = SpmManifestParser.parseManifests(
            List.of(new File("test-data/swiftpackages/regular_ios.json")), "ios", "11.0");

        String projectYml = service.generateProjectYml(manifest, true);

        assertTrue(projectYml.contains("name: SpmWrapper"));
        assertTrue(projectYml.contains("iOS: \"13.0\""));
        assertTrue(projectYml.contains("platform: iOS"));
        assertTrue(projectYml.contains("SWIFT_VERSION: \"6.0\""));
        assertTrue(projectYml.contains("MACH_O_TYPE: staticlib"));
        assertTrue(projectYml.contains("GENERATE_INFOPLIST_FILE: \"YES\""));
        assertTrue(projectYml.contains("path: ../Package"));
        assertTrue(projectYml.contains("product: SpmDeps"));
    }

    @Test
    public void testGenerateProjectYmlMacOs() throws IOException, SpmManifestParsingException {
        SwiftPackageManagerService service = createService();
        service.wrapperMachOType = "mh_dylib";
        SpmManifestParser.ParseResult manifest = SpmManifestParser.parseManifests(
            List.of(new File("test-data/swiftpackages/regular_osx.json")), "osx", "10.15");

        String projectYml = service.generateProjectYml(manifest, false);

        assertTrue(projectYml.contains("macOS: \"11.0\""));
        assertTrue(projectYml.contains("platform: macOS"));
        assertTrue(projectYml.contains("MACH_O_TYPE: mh_dylib"));
    }

    @Test
    public void testWrapperTypeOverride() throws IOException, SpmManifestParsingException {
        SwiftPackageManagerService service = createService();
        SpmManifestParser.ParseResult manifest = SpmManifestParser.parseManifests(
            List.of(new File("test-data/swiftpackages/regular_ios.json")), "ios", "11.0");

        assertEquals("staticlib", service.machOTypeFor(manifest));
        manifest.wrapperType = "dynamic";
        assertEquals("mh_dylib", service.machOTypeFor(manifest));
        assertTrue(service.generateProjectYml(manifest, true).contains("MACH_O_TYPE: mh_dylib"));
        manifest.wrapperType = "static";
        assertEquals("staticlib", service.machOTypeFor(manifest));
    }

    @Test
    public void testCacheSubDirForSanitizesJobValue() {
        // XCODE_VERSION comes from the job's build.yml and must never steer the shared
        // cache out of home-dir-prefix, where the cleanup task would never reclaim it
        assertEquals("16.2", SwiftPackageManagerService.cacheSubDirFor("16.2"));
        assertEquals("16.0__16A242d_", SwiftPackageManagerService.cacheSubDirFor("16.0 (16A242d)"));
        assertEquals("default", SwiftPackageManagerService.cacheSubDirFor(null));
        assertEquals("default", SwiftPackageManagerService.cacheSubDirFor(".."));
        assertEquals("_", SwiftPackageManagerService.cacheSubDirFor("/"));
        assertEquals(".._.._etc", SwiftPackageManagerService.cacheSubDirFor("../../etc"));
        assertEquals(64, SwiftPackageManagerService.cacheSubDirFor("9".repeat(200)).length());
    }

    @Test
    public void testLockFileIsOptional(@TempDir File dir) throws IOException {
        File manifestDir = new File(dir, "spmext/ios");
        manifestDir.mkdirs();
        File manifest = new File(manifestDir, "SwiftPackages.json");
        Files.writeString(manifest.toPath(), "{}");

        // the graph is discovered round by round, so an upload without a lock file builds
        assertNull(SwiftPackageManagerService.findUserLockFile(List.of(manifest)));

        File lock = new File(manifestDir, "Package.resolved");
        Files.writeString(lock.toPath(), "{}");
        assertEquals(lock, SwiftPackageManagerService.findUserLockFile(List.of(manifest)));
    }

    @Test
    public void testHarvestsOnlyRepositoryUrlsFromABuildLog() throws IOException, ExtenderException {
        String missingLog = Files.readString(new File("test-data/swiftpackages/xcodebuild_missing_dependency.log").toPath());
        String cachedLog = Files.readString(new File("test-data/swiftpackages/xcodebuild_cached_repository.log").toPath());
        // the line that tells a resolution failure from a compile error
        assertTrue(missingLog.contains(SwiftPackageManagerService.RESOLUTION_FAILED_MARKER), missingLog);
        assertTrue(cachedLog.contains(SwiftPackageManagerService.RESOLUTION_FAILED_MARKER), cachedLog);

        List<String> missing = SwiftPackageManagerService.harvestPackageUrls(missingLog);
        // the fetched repository and the one that could not be cloned, each named once
        assertEquals(List.of("https://github.com/acme/PkgA.git", "https://github.com/acme/PkgB.git"), missing);

        // a repository already in the shared package cache is updated rather than cloned, so
        // the only line naming it is an "Updating from"
        List<String> cached = SwiftPackageManagerService.harvestPackageUrls(cachedLog);
        assertEquals(List.of("https://github.com/acme/PkgA.git"), cached);

        // a binary target is downloaded over HTTP, not cloned: mirroring its URL as a
        // repository would replace the message that explains the real problem
        assertEquals(List.of(), SwiftPackageManagerService.harvestPackageUrls(
            "failed downloading 'https://github.com/getsentry/sentry-cocoa/releases/download/9.0.0/"
            + "Sentry.xcframework.zip' which is required by binary target 'Sentry'"));
        assertEquals(List.of(), SwiftPackageManagerService.harvestPackageUrls(null));
    }

    @Test
    public void testHarvestsBinaryTargetArchivesFromABuildLog() throws IOException, ExtenderException {
        String log = Files.readString(new File("test-data/swiftpackages/xcodebuild_missing_artifact.log").toPath());
        assertTrue(log.contains(SwiftPackageManagerService.RESOLUTION_FAILED_MARKER), log);
        // the repository still resolves as usual; the archives are separate
        assertEquals(List.of("https://github.com/acme/PkgA.git"), SwiftPackageManagerService.harvestPackageUrls(log));

        List<SwiftPackageManagerService.BinaryArtifact> artifacts = SwiftPackageManagerService.harvestBinaryArtifacts(log);
        // every archive of the round, each once, although SwiftPM reports Foo three times
        assertEquals(List.of(
            new SwiftPackageManagerService.BinaryArtifact("https://dl.example.com/sdk/1.2.0/Foo.zip", "Foo"),
            new SwiftPackageManagerService.BinaryArtifact(
                "https://github.com/acme/PkgA/releases/download/1.0.0/Bar.xcframework.zip", "Bar")), artifacts);
        assertEquals(List.of(), SwiftPackageManagerService.harvestBinaryArtifacts("Downloading binary artifact https://x/y.zip"));
        assertEquals(List.of(), SwiftPackageManagerService.harvestBinaryArtifacts(null));

        Map<String, SwiftPackageManagerService.BinaryArtifact> known = new LinkedHashMap<>();
        assertTrue(SwiftPackageManagerService.addArtifact(known, artifacts.get(0)));
        assertFalse(SwiftPackageManagerService.addArtifact(known, artifacts.get(0)));
        // archive URLs are chosen by manifests, so they are validated like package URLs
        assertThrows(ExtenderException.class, () -> SwiftPackageManagerService.addArtifact(known,
            new SwiftPackageManagerService.BinaryArtifact("https://user:pw@dl.example.com/Foo.zip", "Foo")));
        assertThrows(ExtenderException.class, () -> SwiftPackageManagerService.addArtifact(known,
            new SwiftPackageManagerService.BinaryArtifact("https://dl.example.com:8443/Foo.zip", "Foo")));
        for (int i = known.size(); i < SwiftPackageManagerService.MAX_BINARY_ARTIFACTS; i++) {
            assertTrue(SwiftPackageManagerService.addArtifact(known,
                new SwiftPackageManagerService.BinaryArtifact("https://dl.example.com/sdk/" + i + ".zip", "T" + i)));
        }
        assertThrows(ExtenderException.class, () -> SwiftPackageManagerService.addArtifact(known,
            new SwiftPackageManagerService.BinaryArtifact("https://dl.example.com/sdk/more.zip", "More")));
    }

    @Test
    public void testRejectedArchivesAreFoundThroughTheDeclaringManifest(@TempDir File dir) throws IOException {
        String log = "error: checksum of downloaded artifact of binary target 'Sentry' (ab) "
            + SwiftPackageManagerService.CHECKSUM_MISMATCH_MARKER + " (cd)\n"
            + "  checksum of downloaded artifact of binary target 'Sentry' (ab) does not match\n"
            + "error: checksum of downloaded artifact of binary target 'Other' (ef) does not match";
        assertEquals(List.of("Sentry", "Other"), SwiftPackageManagerService.harvestMismatchedTargets(log));
        assertEquals(List.of(), SwiftPackageManagerService.harvestMismatchedTargets(null));

        File pkg = new File(dir, "checkouts/sentry-cocoa");
        pkg.mkdirs();
        Files.writeString(new File(pkg, "Package.swift").toPath(),
            "let package = Package(\n    targets: [\n"
            + "    .binaryTarget(\n        name: \"Sentry\",\n"
            + "        url: \"https://github.com/getsentry/sentry-cocoa/releases/download/9.18.0/Sentry.xcframework.zip\",\n"
            + "        checksum: \"ab\"\n    ),\n"
            + "    .binaryTarget(name: \"Sentry-Dynamic\", url: \"https://example.com/Dynamic.zip\", checksum: \"cd\"),\n"
            + "    .binaryTarget(name: \"Local\", path: \"Local.xcframework\")])\n");
        assertEquals("https://github.com/getsentry/sentry-cocoa/releases/download/9.18.0/Sentry.xcframework.zip",
            SwiftPackageManagerService.findArtifactUrlInCheckouts(dir, "Sentry"));
        assertEquals("https://example.com/Dynamic.zip", SwiftPackageManagerService.findArtifactUrlInCheckouts(dir, "Sentry-Dynamic"));
        assertNull(SwiftPackageManagerService.findArtifactUrlInCheckouts(dir, "Local"));
        assertNull(SwiftPackageManagerService.findArtifactUrlInCheckouts(dir, "Other"));
        assertNull(SwiftPackageManagerService.findArtifactUrlInCheckouts(new File(dir, "nowhere"), "Sentry"));
    }

    @Test
    public void testManifestSymlinkedOutOfCheckoutsIsRefused(@TempDir File dir) throws IOException {
        // a checked-out package ran as untrusted code with RWX on this tree; it may have
        // replaced its own Package.swift with a link to an arbitrary host path
        File outside = new File(dir.getParentFile(), "outside-" + UUID.randomUUID());
        outside.mkdirs();
        Files.writeString(new File(outside, "secret").toPath(),
            "  .binaryTarget(name: \"Sentry\", url: \"https://evil.example.com/exfil.zip\", checksum: \"ab\")\n");

        File pkg = new File(dir, "checkouts/evil-pkg");
        pkg.mkdirs();
        Files.createSymbolicLink(new File(pkg, "Package.swift").toPath(), new File(outside, "secret").toPath());

        assertNull(SwiftPackageManagerService.findArtifactUrlInCheckouts(dir, "Sentry"));
    }

    @Test
    public void testArtifactCacheNamesMatchSwiftPM() {
        // names SwiftPM 6.x wrote into <packageCache>/artifacts for these URLs
        assertEquals("https___dl_google_com_firebase_ios_swiftpm_12_15_0_FirebaseAnalytics_zip",
            SwiftPackageManagerService.artifactCacheName("https://dl.google.com/firebase/ios/swiftpm/12.15.0/FirebaseAnalytics.zip"));
        assertEquals("https___github_com_getsentry_sentry_cocoa_releases_download_9_18_0_Sentry_Dynamic_WithARM64e_xcframework_zip",
            SwiftPackageManagerService.artifactCacheName("https://github.com/getsentry/sentry-cocoa/releases/download/9.18.0/Sentry-Dynamic-WithARM64e.xcframework.zip"));
        assertEquals("https___dl_google_com_firebase_ios_bin_firestore_12_15_0_rc0_FirebaseFirestoreInternal_zip",
            SwiftPackageManagerService.artifactCacheName("https://dl.google.com/firebase/ios/bin/firestore/12.15.0/rc0/FirebaseFirestoreInternal.zip"));
        assertEquals("https___github_com_facebook_facebook_ios_sdk_releases_download_v18_1_0_FBSDKCoreKit_Dynamic_XCFramework_zip",
            SwiftPackageManagerService.artifactCacheName("https://github.com/facebook/facebook-ios-sdk/releases/download/v18.1.0/FBSDKCoreKit-Dynamic_XCFramework.zip"));
        assertEquals("_1_x_zip", SwiftPackageManagerService.artifactCacheName("1%x.zip"));
    }

    @Test
    public void testHarvestedUrlsAreValidatedAndCapped() throws ExtenderException {
        Map<String, SwiftPackageManagerService.PackageRepo> repos = new LinkedHashMap<>();
        assertTrue(SwiftPackageManagerService.addRepo(repos, "https://github.com/acme/Pkg.git"));
        // the same repository under another spelling is one mirror with two rewrites
        assertFalse(SwiftPackageManagerService.addRepo(repos, "https://GitHub.com/acme/Pkg"));
        assertEquals(1, repos.size());
        // insteadOf matches by prefix and is case sensitive, so every spelling seen and its
        // .git twin rewrite to the one mirror
        assertEquals(Set.of("https://github.com/acme/Pkg.git", "https://github.com/acme/Pkg",
            "https://GitHub.com/acme/Pkg.git", "https://GitHub.com/acme/Pkg"),
            repos.values().iterator().next().spellings);

        // a transitive manifest picks these URLs, so they get the manifest validation
        assertThrows(ExtenderException.class, () -> SwiftPackageManagerService.addRepo(repos, "http://github.com/a/b"));
        assertThrows(ExtenderException.class,
            () -> SwiftPackageManagerService.addRepo(repos, "https://user:pw@github.com/a/b"));

        for (int i = 0; repos.size() < SwiftPackageManagerService.MAX_MIRRORED_PACKAGES; i++) {
            SwiftPackageManagerService.addRepo(repos, "https://github.com/acme/p" + i + ".git");
        }
        ExtenderException e = assertThrows(ExtenderException.class,
            () -> SwiftPackageManagerService.addRepo(repos, "https://github.com/acme/one-too-many.git"));
        assertTrue(e.getMessage().contains("not supported"), e.getMessage());
    }

    @Test
    public void testMirrorDirNameIsStableSafeAndUnique() {
        String sentry = SwiftPackageManagerService.mirrorDirName("github.com/getsentry/sentry-cocoa");
        assertTrue(sentry.matches("sentry-cocoa-[0-9a-f]{12}\\.git"), sentry);
        assertEquals(sentry, SwiftPackageManagerService.mirrorDirName("github.com/getsentry/sentry-cocoa"));
        // same repository name on another host or under another owner gets its own mirror
        assertFalse(sentry.equals(SwiftPackageManagerService.mirrorDirName("gitlab.com/getsentry/sentry-cocoa")));
        assertFalse(sentry.equals(SwiftPackageManagerService.mirrorDirName("github.com/fork/sentry-cocoa")));
        // the readable part never carries path separators or exotic characters
        String odd = SwiftPackageManagerService.mirrorDirName("h.example/a/b" + "x".repeat(100) + "%$");
        assertTrue(odd.matches("[A-Za-z0-9._-]+\\.git"), odd);
        assertTrue(odd.length() < 64);
    }

    @Test
    public void testGitConfigRewritesEveryKnownUrlToItsMirror(@TempDir File dir) throws IOException, ExtenderException {
        SpmManifestParser.ParseResult manifest = SpmManifestParser.parseManifests(
            List.of(new File("test-data/swiftpackages/regular_ios.json")), "ios", "11.0");
        Map<String, SwiftPackageManagerService.PackageRepo> repos = new LinkedHashMap<>();
        for (SpmManifestParser.PackageRef declared : manifest.packages.values()) {
            SwiftPackageManagerService.addRepo(repos, declared.url);
        }
        for (SpmLockFile.Pin pin : SpmLockFile.parse(new File("test-data/swiftpackages/lock_v2.json")).values()) {
            SwiftPackageManagerService.addRepo(repos, pin.location);
        }
        Map<String, File> mirrors = Map.of(
            "github.com/firebase/firebase-ios-sdk", new File(dir, "mirrors/firebase.git"),
            "github.com/getsentry/sentry-cocoa", new File(dir, "mirrors/sentry.git"),
            "github.com/apple/swift-protobuf", new File(dir, "mirrors/protobuf.git"));
        SpmServiceBuildState buildState = new SpmServiceBuildState();
        buildState.workingDir = new File(dir, "SwiftPackageManagerService");
        buildState.workingDir.mkdirs();

        File gitConfig = SwiftPackageManagerService.writeGitConfig(buildState, repos.values(), mirrors);
        String config = Files.readString(gitConfig.toPath());

        assertEquals(new File(buildState.workingDir, "gitconfig"), gitConfig);
        // the lock file spells sentry without .git and the manifest with it: both rewrite
        String sentrySection = section(config, new File(dir, "mirrors/sentry.git"));
        assertTrue(sentrySection.contains("insteadOf = https://github.com/getsentry/sentry-cocoa\n"), sentrySection);
        assertTrue(sentrySection.contains("insteadOf = https://github.com/getsentry/sentry-cocoa.git\n"), sentrySection);
        // a transitive pin is rewritten too, in both spellings
        String protobufSection = section(config, new File(dir, "mirrors/protobuf.git"));
        assertTrue(protobufSection.contains("insteadOf = https://github.com/apple/swift-protobuf.git\n"), protobufSection);
        assertTrue(protobufSection.contains("insteadOf = https://github.com/apple/swift-protobuf\n"), protobufSection);
        assertFalse(config.contains("https://"+ "example"), config);
    }

    private static String section(String config, File mirror) {
        String header = "[url \"file://" + mirror.getAbsolutePath() + "\"]\n";
        int start = config.indexOf(header);
        assertTrue(start >= 0, "no section for " + mirror + " in:\n" + config);
        int end = config.indexOf("[url ", start + header.length());
        return config.substring(start, end < 0 ? config.length() : end);
    }

    @Test
    public void testXcodebuildRunsWithoutNetworkAndReadsMirrors(@TempDir File dir) throws IOException {
        SpmServiceBuildState buildState = new SpmServiceBuildState();
        buildState.workingDir = new File(dir, "SwiftPackageManagerService");
        File packageCache = new File(dir, "packageCache");
        File mirrors = new File(dir, "mirrors");

        SandboxPolicy policy = SwiftPackageManagerService.xcodebuildPolicy(buildState, packageCache, mirrors);

        assertEquals(SandboxPolicy.Network.NONE, policy.network());
        assertTrue(policy.readOnlyPaths().contains(mirrors.getAbsolutePath()), policy.readOnlyPaths().toString());
        assertFalse(policy.readWritePaths().contains(mirrors.getAbsolutePath()));
        assertTrue(policy.readWritePaths().contains(packageCache.getAbsolutePath()));
        assertTrue(policy.readWriteExecPaths().contains(buildState.workingDir.getAbsolutePath()));

        SandboxPolicy prefetch = SwiftPackageManagerService.gitPrefetchPolicy(mirrors);
        assertEquals(SandboxPolicy.Network.ALL, prefetch.network());
        assertTrue(prefetch.readWritePaths().contains(mirrors.getAbsolutePath()));
    }

    @Test
    public void testOfflineBuildHints() {
        assertEquals("", SwiftPackageManagerService.offlineBuildHint(null));
        assertEquals("", SwiftPackageManagerService.offlineBuildHint("error: something else"));
        assertTrue(SwiftPackageManagerService.offlineBuildHint("fatal: transport 'https' not allowed")
            .contains("could not be mirrored"));
        assertTrue(SwiftPackageManagerService.offlineBuildHint("failed downloading 'https://github.com/getsentry/sentry-cocoa/"
            + "releases/download/9.0.0/Sentry.xcframework.zip' which is required by binary target 'Sentry': downloadError(...)")
            .contains("could not use it"));
        assertTrue(SwiftPackageManagerService.offlineBuildHint("error: checksum of downloaded artifact of binary target 'Sentry' (ab) "
            + SwiftPackageManagerService.CHECKSUM_MISMATCH_MARKER + " (cd)").contains("publisher replaced"));
    }

    /**
     * The discovery loop with no network at all: a three level package graph is seeded into
     * the mirror store by hand and only its root is declared, so the service can only get to
     * PkgB and PkgC by reading them out of the output of a failed round. Needs a Mac with
     * Xcode and xcodegen; unlike the test below it never touches a network:
     *   ./gradlew :server:test -PexcludeTags=integration -PspmE2e=true --tests SwiftPackageManagerServiceTest
     */
    @Test
    @EnabledIfSystemProperty(named = "extender.test.spmE2e", matches = "true")
    public void testTransitiveDependenciesAreDiscoveredRoundByRound(@TempDir File rootDir)
            throws IOException, ExtenderException, InterruptedException {
        SwiftPackageManagerService service = createService();
        service.homeDirPrefix = new File(rootDir, "spm-cache").getAbsolutePath();
        service.runAfterStartup();

        // PkgA -> PkgB -> PkgC, none of them reachable over the network. The names are unique
        // per run because SwiftPM records <identity, version> -> revision in a fingerprint
        // store in the real home, which fresh repositories with the same names would clash with
        String suffix = UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        String pkgA = "PkgA" + suffix;
        String pkgB = "PkgB" + suffix;
        String pkgC = "PkgC" + suffix;
        File repos = new File(rootDir, "repos");
        makeLocalPackage(repos, pkgC, null);
        makeLocalPackage(repos, pkgB, pkgC);
        makeLocalPackage(repos, pkgA, pkgB);
        File mirrorsDir = new File(service.ensureCacheDirInitialized().toFile(), SwiftPackageManagerService.MIRRORS_SUBDIR);
        for (String name : List.of(pkgA, pkgB, pkgC)) {
            String key = SpmManifestParser.canonicalKey("https://github.com/acme/" + name + ".git");
            File mirror = new File(mirrorsDir, SwiftPackageManagerService.mirrorDirName(key));
            mirror.getParentFile().mkdirs();
            git(rootDir, "git", "clone", "--mirror", "--quiet", new File(repos, name).getAbsolutePath(),
                mirror.getAbsolutePath());
            // a fresh fetch marker keeps the service from trying to update the mirror
            Files.write(new File(mirrorsDir, mirror.getName() + SwiftPackageManagerService.FETCH_MARKER_SUFFIX)
                .toPath(), new byte[0]);
        }

        File manifestDir = new File(rootDir, "job/upload/spmext/osx");
        manifestDir.mkdirs();
        File manifest = new File(manifestDir, "SwiftPackages.json");
        Files.writeString(manifest.toPath(),
            "{ \"platform\": \"osx\", \"minVersion\": \"11.0\", \"packages\": [" +
            "  { \"url\": \"https://github.com/acme/" + pkgA + ".git\", \"from\": \"1.0.0\"," +
            "    \"products\": [\"" + pkgA + "\"] } ] }");

        SpmServiceBuildState buildState = macOsBuildState(new File(rootDir, "job"));
        ResolvedPackages resolved = service.resolveDependencies(
            List.of(manifest), buildState, Map.of("env.MACOS_VERSION_MIN", "11.0"), false);

        assertNotNull(resolved);
        String lock = Files.readString(buildState.getLockFile().toPath());
        // the transitive packages were never declared and never pinned by the upload
        assertTrue(lock.contains("https://github.com/acme/" + pkgB + ".git"), lock);
        assertTrue(lock.contains("https://github.com/acme/" + pkgC + ".git"), lock);
    }

    /** A minimal library package, committed and tagged 1.0.0, optionally depending on another one. */
    private static void makeLocalPackage(File repos, String name, String dependency)
            throws IOException, InterruptedException {
        File dir = new File(repos, name);
        new File(dir, "Sources/" + name).mkdirs();
        String dependencies = dependency == null ? ""
            : ".package(url: \"https://github.com/acme/" + dependency + ".git\", from: \"1.0.0\")";
        String targetDeps = dependency == null ? ""
            : ".product(name: \"" + dependency + "\", package: \"" + dependency + "\")";
        Files.writeString(new File(dir, "Package.swift").toPath(),
            "// swift-tools-version:5.9\n"
            + "import PackageDescription\n"
            + "let package = Package(\n"
            + "    name: \"" + name + "\",\n"
            + "    products: [.library(name: \"" + name + "\", targets: [\"" + name + "\"])],\n"
            + "    dependencies: [" + dependencies + "],\n"
            + "    targets: [.target(name: \"" + name + "\", dependencies: [" + targetDeps + "])]\n"
            + ")\n");
        Files.writeString(new File(dir, "Sources/" + name + "/" + name + ".swift").toPath(),
            "public let " + name.toLowerCase() + " = 1\n");
        git(dir, "git", "init", "--quiet");
        git(dir, "git", "add", "-A");
        git(dir, "git", "-c", "user.email=extender@example.com", "-c", "user.name=Extender", "commit", "--quiet", "-m", "init");
        git(dir, "git", "tag", "1.0.0");
    }

    private static void git(File cwd, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(cwd).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), String.join(" ", command) + ":\n" + output);
    }

    private static SpmServiceBuildState macOsBuildState(File jobDir) {
        SpmServiceBuildState buildState = new SpmServiceBuildState();
        buildState.workingDir = new File(jobDir, "SwiftPackageManagerService");
        buildState.packageDir = new File(buildState.workingDir, "Package");
        buildState.wrapperDir = new File(buildState.workingDir, "Wrapper");
        buildState.derivedDataDir = new File(buildState.workingDir, "DerivedData");
        buildState.moduleCacheDir = new File(buildState.workingDir, "ModuleCache");
        buildState.clonedSourcePackagesDir = new File(buildState.workingDir, "clonedSourcePackages");
        buildState.buildLogFile = new File(buildState.workingDir, "build.log");
        buildState.selectedPlatform = PodUtils.Platform.MACOSX;
        buildState.buildArch = System.getProperty("os.arch").equals("aarch64") ? "arm64" : "x86_64";
        new File(buildState.packageDir, "Sources/" + SpmServiceBuildState.AGGREGATOR_NAME).mkdirs();
        new File(buildState.wrapperDir, "Sources").mkdirs();
        buildState.derivedDataDir.mkdirs();
        buildState.moduleCacheDir.mkdirs();
        buildState.clonedSourcePackagesDir.mkdirs();
        return buildState;
    }

    /**
     * The discovery loop against the real forge and without any uploaded lock file: only
     * swift-async-algorithms is declared, and swift-collections can be reached only by
     * mirroring what a failed round reported. Needs a Mac with Xcode, xcodegen and network.
     */
    @Test
    @EnabledIfSystemProperty(named = "extender.test.spmE2e", matches = "true")
    public void testPublicGraphResolvesWithoutALockFile(@TempDir File rootDir) throws IOException, ExtenderException {
        SwiftPackageManagerService service = createService();
        service.homeDirPrefix = new File(rootDir, "spm-cache").getAbsolutePath();
        service.runAfterStartup();

        File manifestDir = new File(rootDir, "job/upload/spmext/osx");
        manifestDir.mkdirs();
        File manifest = new File(manifestDir, "SwiftPackages.json");
        Files.writeString(manifest.toPath(),
            "{ \"platform\": \"osx\", \"minVersion\": \"13.0\", \"packages\": [" +
            "  { \"url\": \"https://github.com/apple/swift-async-algorithms.git\", \"from\": \"1.0.0\"," +
            "    \"products\": [\"AsyncAlgorithms\"] } ] }");

        SpmServiceBuildState buildState = macOsBuildState(new File(rootDir, "job"));
        ResolvedPackages resolved = service.resolveDependencies(
            List.of(manifest), buildState, Map.of("env.MACOS_VERSION_MIN", "13.0"), false);

        assertNotNull(resolved);
        String lock = Files.readString(buildState.getLockFile().toPath());
        assertTrue(lock.contains("swift-async-algorithms"), lock);
        // never declared and never uploaded: found by mirroring what a failed round named
        assertTrue(lock.contains("swift-collections"), lock);
    }

    /**
     * Real xcodegen + xcodebuild run against a small public package, with the project's own
     * Package.resolved uploaded as a seed so the graph closes in the first round. Needs a Mac with
     * Xcode (license accepted), xcodegen, and network access for the git mirror step:
     *   ./gradlew :server:test -PexcludeTags=integration -PspmE2e=true --tests SwiftPackageManagerServiceTest
     */
    @ParameterizedTest
    @EnumSource(value = PodUtils.Platform.class, names = { "IPHONEOS", "IPHONESIMULATOR" })
    @EnabledIfSystemProperty(named = "extender.test.spmE2e", matches = "true")
    public void testResolveDependenciesEndToEnd(PodUtils.Platform platform, @TempDir File rootDir) throws IOException, ExtenderException {
        SwiftPackageManagerService service = createService();
        service.homeDirPrefix = new File(rootDir, "spm-cache").getAbsolutePath();
        service.runAfterStartup();

        File manifestDir = new File(rootDir, "job/upload/spmext/ios");
        manifestDir.mkdirs();
        File manifest = new File(manifestDir, "SwiftPackages.json");
        Files.writeString(manifest.toPath(),
            "{ \"platform\": \"ios\", \"minVersion\": \"15.0\", \"packages\": [" +
            "  { \"url\": \"https://github.com/apple/swift-argument-parser.git\", \"from\": \"1.3.0\", \"products\": [\"ArgumentParser\"] } ] }");
        // the build runs offline against git mirrors of the pinned graph. The lock file is
        // shaped like the one Xcode writes for the author's own app: format 3 with an
        // originHash computed from a manifest that is not the wrapper's, and a pin
        // (swift-log) the wrapper never references
        Files.writeString(new File(manifestDir, "Package.resolved").toPath(),
            "{ \"originHash\": \"1f0d8b3c5e7a9b2d4f6e8a0c2b4d6f8e0a1c3e5f7a9b1d3f5e7a9c1b3d5f7e9a\"," +
            "  \"pins\": [ { \"identity\": \"swift-argument-parser\", \"kind\": \"remoteSourceControl\"," +
            "  \"location\": \"https://github.com/apple/swift-argument-parser.git\"," +
            "  \"state\": { \"revision\": \"6a52f3251125d74daf04fcbd5e6f08a75d074382\", \"version\": \"1.8.2\" } }," +
            "  { \"identity\": \"swift-log\", \"kind\": \"remoteSourceControl\"," +
            "  \"location\": \"https://github.com/apple/swift-log.git\"," +
            "  \"state\": { \"revision\": \"3ffafb9722d5d918c614feb496c8789a3b59d222\", \"version\": \"1.15.0\" } } ]," +
            "  \"version\": 3 }");

        SpmServiceBuildState buildState = new SpmServiceBuildState();
        buildState.workingDir = new File(rootDir, "job/SwiftPackageManagerService");
        buildState.packageDir = new File(buildState.workingDir, "Package");
        buildState.wrapperDir = new File(buildState.workingDir, "Wrapper");
        buildState.derivedDataDir = new File(buildState.workingDir, "DerivedData");
        buildState.moduleCacheDir = new File(buildState.workingDir, "ModuleCache");
        buildState.clonedSourcePackagesDir = new File(buildState.workingDir, "clonedSourcePackages");
        buildState.buildLogFile = new File(buildState.workingDir, "build.log");
        buildState.selectedPlatform = platform;
        buildState.buildArch = "arm64";
        new File(buildState.packageDir, "Sources/" + SpmServiceBuildState.AGGREGATOR_NAME).mkdirs();
        new File(buildState.wrapperDir, "Sources").mkdirs();
        buildState.derivedDataDir.mkdirs();
        buildState.moduleCacheDir.mkdirs();
        buildState.clonedSourcePackagesDir.mkdirs();

        ResolvedPackages resolved = service.resolveDependencies(
            List.of(manifest), buildState, Map.of("env.IOS_VERSION_MIN", "15.0"), true);

        assertNotNull(resolved);
        assertTrue(new File(buildState.getProductsDir(), "SpmWrapper.framework/SpmWrapper").isFile());
        assertTrue(resolved.getFrameworks().contains("SpmWrapper"));
        assertNotNull(resolved.getLockFile());
        assertTrue(resolved.getLockFile().isFile());
        assertEquals("15.0", resolved.getPlatformMinVersion());
        assertTrue(buildState.getBuildLogFile().isFile());
    }

    @Test
    public void testCopyLockFileRefusesALinkOnTheWayToTheTarget(@TempDir File rootDir) throws IOException {
        SwiftPackageManagerService service = createService();
        SpmServiceBuildState buildState = macOsBuildState(new File(rootDir, "job"));
        File outside = new File(rootDir, "outside");
        outside.mkdirs();
        // simulates the xcodeproj path being redirected before copyLockFile runs, e.g. by a
        // symlink planted somewhere earlier in the same job
        Files.createSymbolicLink(buildState.getXcodeProjDir().toPath(), outside.toPath());

        File userLockFile = new File(rootDir, "Package.resolved");
        Files.writeString(userLockFile.toPath(), "{}");

        assertThrows(IOException.class, () -> service.copyLockFile(userLockFile, buildState));
        assertFalse(new File(outside, "project.xcworkspace").exists());
    }
}
