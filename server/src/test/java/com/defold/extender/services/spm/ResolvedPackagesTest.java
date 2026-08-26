package com.defold.extender.services.spm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import com.defold.extender.ExtenderException;
import com.defold.extender.services.cocoapods.PodUtils;
import com.defold.extender.services.spm.SpmBuildOutputParser.LinkInfo;

// classification of framework binaries shells out to file(1) with Mach-O magic bytes,
// which is only reliable on a Mac
@EnabledOnOs({OS.MAC})
public class ResolvedPackagesTest {

    private SpmServiceBuildState createBuildState(File rootDir) {
        SpmServiceBuildState buildState = new SpmServiceBuildState();
        buildState.workingDir = rootDir;
        buildState.packageDir = new File(rootDir, "Package");
        buildState.wrapperDir = new File(rootDir, "Wrapper");
        buildState.derivedDataDir = new File(rootDir, "DerivedData");
        buildState.moduleCacheDir = new File(rootDir, "ModuleCache");
        buildState.buildLogFile = new File(rootDir, "build.log");
        buildState.selectedPlatform = PodUtils.Platform.IPHONEOS;
        buildState.buildArch = "arm64";
        return buildState;
    }

    // minimal Mach-O 64-bit dylib header; file(1) reports it as a dynamically linked shared library
    private static void writeDylibBinary(File file) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0xFEEDFACF);  // MH_MAGIC_64
        buffer.putInt(0x0100000C);  // CPU_TYPE_ARM64
        buffer.putInt(0);           // cpusubtype
        buffer.putInt(6);           // MH_DYLIB
        buffer.putInt(0).putInt(0).putInt(0).putInt(0);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(buffer.array());
        }
    }

    private static void writeStaticArchiveBinary(File file) throws IOException {
        Files.writeString(file.toPath(), "!<arch>\n");
    }

    private static File createFramework(File productsDir, String name, boolean dynamic) throws IOException {
        File framework = new File(productsDir, name + ".framework");
        framework.mkdirs();
        File binary = new File(framework, name);
        if (dynamic) {
            writeDylibBinary(binary);
        } else {
            writeStaticArchiveBinary(binary);
        }
        return framework;
    }

    private void createCommonProducts(SpmServiceBuildState buildState) throws IOException {
        File productsDir = buildState.getProductsDir();
        productsDir.mkdirs();

        createFramework(productsDir, "FirebaseAnalytics", false);
        File sentry = createFramework(productsDir, "Sentry", true);
        Files.writeString(new File(sentry, "PrivacyInfo.xcprivacy").toPath(), "<plist/>");

        File bundle = new File(productsDir, "Firebase_FirebaseCore.bundle");
        bundle.mkdirs();
        Files.writeString(new File(bundle, "PrivacyInfo.xcprivacy").toPath(), "<plist/>");

        File moduleMapsDir = new File(buildState.getDerivedDataDir(), "Build/Intermediates.noindex/GeneratedModuleMaps-iphoneos");
        moduleMapsDir.mkdirs();

        File lockFile = buildState.getLockFile();
        lockFile.getParentFile().mkdirs();
        Files.writeString(lockFile.toPath(), "{}");
    }

    private static LinkInfo linkInfo() {
        return new LinkInfo(
            List.of("c++", "z", "sqlite3"),
            List.of("UIKit", "Security", "Sentry"),
            List.of("/usr/lib/swift"),
            List.of("/usr/lib/swift", "@executable_path/Frameworks"),
            List.of());
    }

    @Test
    public void testHarvestDynamicWrapper(@TempDir File rootDir) throws IOException, ExtenderException {
        SpmServiceBuildState buildState = createBuildState(rootDir);
        createCommonProducts(buildState);
        createFramework(buildState.getProductsDir(), "SpmWrapper", true);
        File swiftLibDir = new File(rootDir, "swift-runtime/iphoneos");
        swiftLibDir.mkdirs();

        ResolvedPackages resolved = ResolvedPackages.harvest(buildState, "15.0", linkInfo(), swiftLibDir);

        // products-dir frameworks first (sorted), then link-line names deduplicated
        assertEquals(List.of("FirebaseAnalytics", "Sentry", "SpmWrapper", "UIKit", "Security"), resolved.getFrameworks());
        assertEquals(List.of(buildState.getProductsDir().getAbsolutePath()), resolved.getFrameworksSearchPaths());
        assertEquals(List.of("c++", "z", "sqlite3"), resolved.getStaticLibraries());
        // link-line paths first, then the derived toolchain swift runtime dir
        assertEquals(List.of("/usr/lib/swift", swiftLibDir.getAbsolutePath()), resolved.getLibrarySearchPaths());

        // the dylib wrapper and the dylib dependency get embedded; the static framework does not
        assertEquals(2, resolved.getDynamicFrameworks().size());
        assertTrue(resolved.getDynamicFrameworks().stream().anyMatch(f -> f.getName().equals("SpmWrapper.framework")));
        assertTrue(resolved.getDynamicFrameworks().stream().anyMatch(f -> f.getName().equals("Sentry.framework")));
        assertEquals(List.of("-Wl,-rpath,/usr/lib/swift", "-Wl,-rpath,@executable_path/Frameworks"), resolved.getLinkFlags());

        assertEquals(1, resolved.getResources().size());
        assertEquals("Firebase_FirebaseCore.bundle", resolved.getResources().get(0).getName());
        assertEquals(2, resolved.getPrivacyManifests().size());

        assertEquals(List.of(
            new File(buildState.getDerivedDataDir(), "Build/Intermediates.noindex/GeneratedModuleMaps-iphoneos").getAbsolutePath(),
            buildState.getProductsDir().getAbsolutePath()),
            resolved.getAdditionalIncludePaths());

        assertNotNull(resolved.getLockFile());
        assertEquals("15.0", resolved.getPlatformMinVersion());
        assertTrue(resolved.getBuiltFrameworks().isEmpty());
        assertTrue(resolved.getWeakFrameworks().isEmpty());
    }

    @Test
    public void testHarvestStaticWrapper(@TempDir File rootDir) throws IOException, ExtenderException {
        SpmServiceBuildState buildState = createBuildState(rootDir);
        createCommonProducts(buildState);
        createFramework(buildState.getProductsDir(), "SpmWrapper", false);

        ResolvedPackages resolved = ResolvedPackages.harvest(buildState, "15.0", linkInfo(), null);

        // static wrapper is linked, not embedded; only the dylib dependency gets embedded
        assertTrue(resolved.getFrameworks().contains("SpmWrapper"));
        assertEquals(1, resolved.getDynamicFrameworks().size());
        assertEquals("Sentry.framework", resolved.getDynamicFrameworks().get(0).getName());
    }

    @Test
    public void testCollectModuleMapIncludePaths(@TempDir File rootDir) throws IOException {
        File maps = new File(rootDir, "GeneratedModuleMaps-iphoneos");
        maps.mkdirs();

        // Firebase-style layout: umbrella at .../Public/FirebaseCore/FirebaseCore.h ->
        // the Public dir serves #import <FirebaseCore/FirebaseCore.h> directly
        File nestedHeaders = new File(rootDir, "checkouts/firebase/Sources/Public/FirebaseCore");
        nestedHeaders.mkdirs();
        Files.writeString(new File(nestedHeaders, "FirebaseCore.h").toPath(), "//");
        Files.writeString(new File(maps, "FirebaseCore.modulemap").toPath(),
            "module FirebaseCore {\numbrella header \"" + new File(nestedHeaders, "FirebaseCore.h").getAbsolutePath() + "\"\nexport *\n}");

        // flat layout: headers directly in Public/ -> synthetic <Module> symlink dir
        File flatHeaders = new File(rootDir, "checkouts/sentry/Public");
        flatHeaders.mkdirs();
        Files.writeString(new File(flatHeaders, "Sentry.h").toPath(), "//");
        Files.writeString(new File(maps, "Sentry.modulemap").toPath(),
            "module Sentry {\numbrella header \"" + new File(flatHeaders, "Sentry.h").getAbsolutePath() + "\"\n}");

        // no umbrella -> ignored
        Files.writeString(new File(maps, "Odd.modulemap").toPath(), "module Odd { header \"X.h\" }");

        Set<String> includePaths = new LinkedHashSet<>();
        File syntheticDir = new File(rootDir, "include");
        ResolvedPackages.collectModuleMapIncludePaths(maps, syntheticDir, includePaths);

        assertTrue(includePaths.contains(nestedHeaders.getParentFile().getAbsolutePath()));
        // the mismatch case exposes both the headers dir itself and the module-name symlink
        assertTrue(includePaths.contains(flatHeaders.getAbsolutePath()));
        assertTrue(includePaths.contains(syntheticDir.getAbsolutePath()));
        assertTrue(Files.isSymbolicLink(new File(syntheticDir, "Sentry").toPath()));
        assertTrue(new File(syntheticDir, "Sentry/Sentry.h").isFile());
        assertEquals(3, includePaths.size());
    }

    @Test
    public void testHarvestWithoutLinkInfoAndLockFile(@TempDir File rootDir) throws IOException, ExtenderException {
        SpmServiceBuildState buildState = createBuildState(rootDir);
        File productsDir = buildState.getProductsDir();
        productsDir.mkdirs();
        createFramework(productsDir, "SpmWrapper", false);

        ResolvedPackages resolved = ResolvedPackages.harvest(buildState, "15.0", null, null);

        assertEquals(List.of("SpmWrapper"), resolved.getFrameworks());
        assertTrue(resolved.getStaticLibraries().isEmpty());
        assertTrue(resolved.getLibrarySearchPaths().isEmpty());
        assertTrue(resolved.getDynamicFrameworks().isEmpty());
        assertTrue(resolved.getLinkFlags().isEmpty());
        assertNull(resolved.getLockFile());
        // no GeneratedModuleMaps dir was created; only the products dir remains
        assertEquals(List.of(productsDir.getAbsolutePath()), resolved.getAdditionalIncludePaths());
    }
}
