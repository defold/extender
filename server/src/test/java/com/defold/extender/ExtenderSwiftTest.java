package com.defold.extender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.defold.extender.services.ResolvedNativeDeps;

/**
 * The Swift steps of an extension build, against the fixture SDK and a platform SDK folder with
 * fake tools, which record their arguments, so that these run on every host.
 */
public class ExtenderSwiftTest {

    private static final File FIXTURE_SDK = new File("test-data/sdk/a/defoldsdk");
    private static final Pattern SWIFT_SUPPORT_KEYS = Pattern.compile("^\\s*(swiftSourceRe|allowedSwiftFlags|compileSwiftModuleCmd|listSwiftRuntimeLibsCmd):.*");

    @TempDir
    File root;

    private File jobDir;
    private File extDir;
    private File platformSdk;
    private File toolchain;
    private File toolArguments;
    private Map<String, String> env;

    @BeforeEach
    void createJob() throws IOException {
        jobDir = new File(root, "job");
        Files.createDirectories(new File(jobDir, "build").toPath());
        extDir = new File(jobDir, "upload/myext");
        write(new File(extDir, "ext.manifest"), "name: My-Ext\n");
        write(new File(extDir, "src/A.swift"), "");
        write(new File(extDir, "src/shim.mm"), "");

        env = ExtenderTest.createEnv();
        platformSdk = new File(root, "platformsdk");
        env.put("PLATFORMSDK_DIR", platformSdk.getPath());
        toolchain = new File(platformSdk, "XcodeDefault" + env.get("XCODE_VERSION") + ".xctoolchain");
        toolArguments = new File(root, "tool-arguments.txt");
    }

    private static void write(File file, String content) throws IOException {
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), content);
    }

    private void fakeTool(String name, String... outputLines) throws IOException {
        File tool = new File(toolchain, "usr/bin/" + name);
        StringBuilder script = new StringBuilder("#!/bin/sh\nprintf '%s\\n' \"$*\" >> '" + toolArguments.getPath() + "'\n");
        for (String line : outputLines) {
            script.append("echo '").append(line).append("'\n");
        }
        write(tool, script.toString());
        assertTrue(tool.setExecutable(true));
    }

    private String recordedToolArguments() throws IOException {
        return Files.readString(toolArguments.toPath(), StandardCharsets.UTF_8);
    }

    private Extender extender(String platform, File sdk) throws IOException, ExtenderException {
        return new Extender.Builder()
                .setPlatform(platform)
                .setSdk(sdk)
                .setJobDirectory(jobDir)
                .setUploadDirectory(new File(jobDir, "upload"))
                .setBuildDirectory(new File(jobDir, "build"))
                .setEnv(env)
                .build();
    }

    private Extender extender(String platform) throws IOException, ExtenderException {
        return extender(platform, FIXTURE_SDK);
    }

    private List<File> engineSourceDirs() {
        return List.of(new File(extDir, "commonsrc"), new File(extDir, "src"));
    }

    private List<File> collectSwiftSources(Extender extender) throws ExtenderException {
        return extender.collectSwiftSourceFiles(new File(extDir, "ext.manifest"), extDir, engineSourceDirs());
    }

    /** A copy of the fixture SDK from before Defold had the Swift extension templates. */
    private File sdkWithoutSwiftSupport() throws IOException {
        File sdk = new File(root, "old-sdk");
        FileUtils.copyDirectory(FIXTURE_SDK, sdk);
        File buildYml = new File(sdk, "extender/build.yml");
        List<String> lines = Files.readAllLines(buildYml.toPath()).stream()
                .filter(line -> !SWIFT_SUPPORT_KEYS.matcher(line).matches())
                .collect(Collectors.toList());
        Files.write(buildYml.toPath(), lines);
        return sdk;
    }

    private static ResolvedNativeDeps deps(String minVersion, List<String> swiftModuleMaps, List<File> dynamicFrameworks) {
        return new ResolvedNativeDeps() {
            @Override
            public List<String> getFrameworks() {
                return List.of();
            }

            @Override
            public List<String> getFrameworksSearchPaths() {
                return List.of();
            }

            @Override
            public String getPlatformMinVersion() {
                return minVersion;
            }

            @Override
            public List<String> getSwiftModuleMaps() {
                return swiftModuleMaps;
            }

            @Override
            public List<File> getDynamicFrameworks() {
                return dynamicFrameworks;
            }
        };
    }

    private static ResolvedNativeDeps depsWithMinVersion(String minVersion) {
        return deps(minVersion, List.of(), List.of());
    }

    private String compileSwiftModule(Extender extender) throws Exception {
        Map<String, Object> manifestContext = new HashMap<>(extender.getMergedAppContext());
        manifestContext.put("extension_name", "My-Ext");
        extender.compileExtensionSwiftFiles(extDir, manifestContext, List.of(new File(extDir, "src/A.swift")));
        return recordedToolArguments();
    }

    // Verifies that the Swift sources of src/ are collected for every Apple target.
    @Test
    public void swiftSourcesInSrcAreCollectedForAppleTargets() throws Exception {
        for (String platform : List.of("arm64-osx", "x86_64-osx", "arm64-ios", "arm64_sim-ios", "x86_64-ios")) {
            assertEquals(List.of(new File(extDir, "src/A.swift")), collectSwiftSources(extender(platform)), platform);
        }
    }

    // Verifies that Swift sources are skipped on the platforms without a Swift compiler, wherever they are.
    @Test
    public void swiftSourcesAreIgnoredOnOtherPlatforms() throws Exception {
        write(new File(extDir, "commonsrc/B.swift"), "");
        env = ExtenderTest.createAndroidEnv();
        assertTrue(collectSwiftSources(extender("armv7-android")).isEmpty());
    }

    // Verifies that Swift sources are refused outside of src/, as the plugin build also reads those folders.
    @Test
    public void swiftSourcesOutsideSrcAreRefused() throws Exception {
        for (String folder : List.of("commonsrc", "pluginsrc")) {
            File misplaced = new File(extDir, folder + "/B.swift");
            write(misplaced, "");
            ExtenderException e = assertThrows(ExtenderException.class, () -> collectSwiftSources(extender("arm64-osx")), folder);
            assertTrue(e.getMessage().contains("Swift sources are only supported in the 'src' folder"), e.getMessage());
            assertTrue(e.getMessage().contains(folder + "/B.swift"), e.getMessage());
            assertTrue(misplaced.delete());
        }
    }

    // Verifies that an SDK without the Swift templates says so, also for a misplaced source,
    // where moving the file to src/ would not help.
    @Test
    public void swiftSourcesAreRefusedOnAnSdkWithoutSwiftSupport() throws Exception {
        File oldSdk = sdkWithoutSwiftSupport();
        ExtenderException e = assertThrows(ExtenderException.class, () -> collectSwiftSources(extender("arm64-osx", oldSdk)));
        assertTrue(e.getMessage().contains("Swift sources are not supported by this Defold SDK version"), e.getMessage());

        assertTrue(new File(extDir, "src/A.swift").delete());
        write(new File(extDir, "commonsrc/B.swift"), "");
        e = assertThrows(ExtenderException.class, () -> collectSwiftSources(extender("arm64-osx", oldSdk)));
        assertTrue(e.getMessage().contains("Swift sources are not supported by this Defold SDK version"), e.getMessage());
    }

    // Verifies that an SDK without the Swift templates still builds an extension without Swift sources.
    @Test
    public void extensionsWithoutSwiftAreUnaffectedOnAnSdkWithoutSwiftSupport() throws Exception {
        assertTrue(new File(extDir, "src/A.swift").delete());
        assertTrue(collectSwiftSources(extender("arm64-osx", sdkWithoutSwiftSupport())).isEmpty());
    }

    // Verifies the swiftc call of one extension: one module, named after the sanitized extension name,
    // with the bridging header and the generated header next to the other build outputs.
    @Test
    public void swiftModuleCompileCommand() throws Exception {
        write(new File(extDir, "src/My_Ext-Bridging-Header.h"), "");
        fakeTool("swiftc");

        String arguments = compileSwiftModule(extender("arm64-osx"));

        assertTrue(arguments.contains("-module-name My_Ext "), arguments);
        assertTrue(arguments.contains("upload/myext/src/A.swift "), arguments);
        assertTrue(arguments.contains("-import-objc-header upload/myext/src/My_Ext-Bridging-Header.h "), arguments);
        assertTrue(arguments.contains("-emit-module-path build/myext/My_Ext.swiftmodule "), arguments);
        assertTrue(arguments.contains("-emit-objc-header-path build/myext/My_Ext-Swift.h "), arguments);
    }

    // Verifies that without native dependencies the Swift code targets the minimum version of the engine.
    @Test
    public void swiftModuleTargetsTheMinimumVersionOfTheEngine() throws Exception {
        fakeTool("swiftc");
        assertTrue(compileSwiftModule(extender("arm64-osx")).contains("-target arm64-apple-macosx" + env.get("MACOS_VERSION_MIN") + " "));
    }

    // Verifies that the Swift code targets the minimum version the native dependencies raise it to, as
    // the link does, since the module of a package can't be imported below the minimum version of the package.
    @Test
    public void swiftModuleTargetsTheMinimumVersionOfItsDependencies() throws Exception {
        Map<String, String> targets = Map.of(
                "arm64-osx", "-target arm64-apple-macosx42.0 ",
                "x86_64-osx", "-target x86_64-apple-macosx42.0 ",
                "arm64-ios", "-target arm64-apple-ios42.0 ",
                "arm64_sim-ios", "-target arm64-apple-ios42.0-simulator ",
                "x86_64-ios", "-target x86_64-apple-ios42.0-simulator ");
        fakeTool("swiftc");
        for (Map.Entry<String, String> target : targets.entrySet()) {
            Extender extender = extender(target.getKey());
            extender.resolvedNativeDeps.add(depsWithMinVersion("42.0"));
            assertTrue(compileSwiftModule(extender).contains(target.getValue()), target.getKey());
        }
    }

    // Verifies that the Swift compile gets every module map of the native dependencies next to the
    // flags of the platform, as Swift only finds the C and Objective-C modules of a package through them.
    @Test
    public void swiftModuleCompileGetsTheModuleMapsOfTheDependencies() throws Exception {
        fakeTool("swiftc");
        Extender extender = extender("arm64-osx");
        extender.resolvedNativeDeps.add(deps(null, List.of("/maps/A.modulemap", "/maps/B.modulemap"), List.of()));

        String arguments = compileSwiftModule(extender);

        assertTrue(arguments.contains("-Xcc -fmodule-map-file=/maps/A.modulemap -Xcc -fmodule-map-file=/maps/B.modulemap "), arguments);
        assertTrue(arguments.contains("-target arm64-apple-macosx"), arguments);
    }

    private File spanLibrary() {
        return new File(toolchain, "usr/lib/swift-6.2/macosx/libswiftCompatibilitySpan.dylib");
    }

    /** The two libraries the fake stdlib tool reports, and the SDK table that only knows the Span one. */
    private File fakeRuntimeLibraries(File exe) throws IOException {
        File core = new File(toolchain, "usr/lib/swift/macosx/libswiftCore.dylib");
        write(core, "");
        write(spanLibrary(), "");
        fakeTool("swift-stdlib-tool", core.getPath(), spanLibrary().getPath());
        write(new File(platformSdk, "MacOSX" + env.get("MACOS_VERSION") + ".sdk/SDKSettings.json"),
                "{\"SupportedTargets\": {\"macosx\": {"
                + "\"SwiftCompatibilityLibraryNotNeededStartingIn\": {\"libswiftCompatibilitySpan.dylib\": \"26.0\"}}}}");
        write(exe, "");
        return exe;
    }

    /** A stdlib tool that reports the Span library only for one scanned binary. */
    private void fakeStdlibToolReportingOnlyFor(String binary) throws IOException {
        File tool = new File(toolchain, "usr/bin/swift-stdlib-tool");
        write(tool, "#!/bin/sh\ncase \"$*\" in *'--scan-executable " + binary + " '*) echo '" + spanLibrary().getPath() + "';; esac\n");
        assertTrue(tool.setExecutable(true));
    }

    private File[] copiedSwiftLibraries() {
        return new File(jobDir, "build").listFiles((dir, name) -> name.startsWith("libswift"));
    }

    private void assertOnlySpanLibraryIsCopied() {
        File[] libraries = copiedSwiftLibraries();
        assertEquals(1, libraries.length);
        assertEquals("libswiftCompatibilitySpan.dylib", libraries[0].getName());
    }

    // Verifies that the engine gets the libraries that the OS of the minimum version lacks, and not the core runtime.
    @Test
    public void runtimeLibrariesTheMinimumOsLacksAreCopiedNextToTheEngine() throws Exception {
        env.put("MACOS_VERSION_MIN", "12.0");
        File exe = fakeRuntimeLibraries(new File(jobDir, "build/dmengine"));

        Extender extender = extender("arm64-osx");
        extender.copySwiftRuntimeLibraries(extender.getMergedAppContext(), exe, List.of());

        assertTrue(recordedToolArguments().contains("--print --scan-executable build/dmengine --platform macosx"), recordedToolArguments());
        assertOnlySpanLibraryIsCopied();
    }

    // Verifies that a minimum OS version that has the library needs no copy.
    @Test
    public void runtimeLibrariesTheMinimumOsHasAreNotCopied() throws Exception {
        env.put("MACOS_VERSION_MIN", "26.0");
        File exe = fakeRuntimeLibraries(new File(jobDir, "build/dmengine"));

        Extender extender = extender("arm64-osx");
        extender.copySwiftRuntimeLibraries(extender.getMergedAppContext(), exe, List.of());

        assertEquals(0, copiedSwiftLibraries().length);
    }

    // Verifies that the libraries follow the minimum version the native dependencies raise it to, as the link does.
    @Test
    public void runtimeLibrariesFollowTheLinkedMinimumVersion() throws Exception {
        env.put("MACOS_VERSION_MIN", "12.0");
        File exe = fakeRuntimeLibraries(new File(jobDir, "build/dmengine"));

        Extender extender = extender("arm64-osx");
        Map<String, Object> linkContext = new HashMap<>(extender.getMergedAppContext());
        linkContext.put("osMinVersion", "26.0");
        extender.copySwiftRuntimeLibraries(linkContext, exe, List.of());

        assertEquals(0, copiedSwiftLibraries().length);
    }

    // Verifies that a build without Swift code does not depend on the Swift settings of the SDK.
    @Test
    public void binariesWithoutSwiftLibrariesDoNotNeedTheSdkSettings() throws Exception {
        File exe = new File(jobDir, "build/dmengine");
        write(exe, "");
        fakeTool("swift-stdlib-tool");

        Extender extender = extender("arm64-osx");
        extender.copySwiftRuntimeLibraries(extender.getMergedAppContext(), exe, List.of());

        assertTrue(recordedToolArguments().contains("--scan-executable build/dmengine"), recordedToolArguments());
        assertEquals(0, copiedSwiftLibraries().length);
    }

    // Verifies that a library is found when only an embedded dynamic framework refers to it.
    @Test
    public void runtimeLibrariesOfAnEmbeddedFrameworkAreCopied() throws Exception {
        env.put("MACOS_VERSION_MIN", "12.0");
        File exe = fakeRuntimeLibraries(new File(jobDir, "build/dmengine"));
        fakeStdlibToolReportingOnlyFor("spm/Dep.framework/Dep");

        Extender extender = extender("arm64-osx");
        extender.resolvedNativeDeps.add(deps(null, List.of(), List.of(new File(jobDir, "spm/Dep.framework"))));
        extender.copySwiftRuntimeLibraries(extender.getMergedAppContext(), exe, List.of());

        assertOnlySpanLibraryIsCopied();
    }

    // Verifies that a library is found when only a dynamic library of an extension refers to it.
    @Test
    public void runtimeLibrariesOfAnExtensionDynamicLibraryAreCopied() throws Exception {
        env.put("MACOS_VERSION_MIN", "12.0");
        File exe = fakeRuntimeLibraries(new File(jobDir, "build/dmengine"));
        fakeStdlibToolReportingOnlyFor("upload/myext/lib/osx/libDep.dylib");

        Extender extender = extender("arm64-osx");
        extender.copySwiftRuntimeLibraries(extender.getMergedAppContext(), exe,
                List.of(new File(extDir, "lib/osx/libDep.dylib").getPath()));

        assertOnlySpanLibraryIsCopied();
    }
}
