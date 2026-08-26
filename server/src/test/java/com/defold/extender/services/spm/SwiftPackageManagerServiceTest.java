package com.defold.extender.services.spm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

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

    /**
     * Real xcodegen + xcodebuild run against a small public package. Needs a Mac with
     * Xcode (license accepted), xcodegen, and network access:
     *   ./gradlew :server:test -PexcludeTags=integration -PspmE2e=true --tests SwiftPackageManagerServiceTest
     */
    @Test
    @EnabledIfSystemProperty(named = "extender.test.spmE2e", matches = "true")
    public void testResolveDependenciesEndToEnd(@TempDir File rootDir) throws IOException, ExtenderException {
        SwiftPackageManagerService service = createService();
        service.homeDirPrefix = new File(rootDir, "spm-cache").getAbsolutePath();
        service.runAfterStartup();

        File manifestDir = new File(rootDir, "job/upload/spmext/ios");
        manifestDir.mkdirs();
        File manifest = new File(manifestDir, "SwiftPackages.json");
        Files.writeString(manifest.toPath(),
            "{ \"platform\": \"ios\", \"minVersion\": \"15.0\", \"packages\": [" +
            "  { \"url\": \"https://github.com/apple/swift-argument-parser.git\", \"from\": \"1.3.0\", \"products\": [\"ArgumentParser\"] } ] }");

        SpmServiceBuildState buildState = new SpmServiceBuildState();
        buildState.workingDir = new File(rootDir, "job/SwiftPackageManagerService");
        buildState.packageDir = new File(buildState.workingDir, "Package");
        buildState.wrapperDir = new File(buildState.workingDir, "Wrapper");
        buildState.derivedDataDir = new File(buildState.workingDir, "DerivedData");
        buildState.moduleCacheDir = new File(buildState.workingDir, "ModuleCache");
        buildState.clonedSourcePackagesDir = new File(buildState.workingDir, "clonedSourcePackages");
        buildState.buildLogFile = new File(buildState.workingDir, "build.log");
        buildState.selectedPlatform = PodUtils.Platform.IPHONEOS;
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
}
