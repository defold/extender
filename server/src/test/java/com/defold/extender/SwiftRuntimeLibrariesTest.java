package com.defold.extender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SwiftRuntimeLibrariesTest {
    private static final String TOOLCHAIN = "/opt/xcode/Toolchains/XcodeDefault.xctoolchain/usr/lib";
    private static final File CORE = new File(TOOLCHAIN + "/swift-5.0/iphoneos/libswiftCore.dylib");
    private static final File CONCURRENCY = new File(TOOLCHAIN + "/swift-5.5/iphoneos/libswift_Concurrency.dylib");
    private static final File SPAN = new File(TOOLCHAIN + "/swift-6.2/iphoneos/libswiftCompatibilitySpan.dylib");

    private static SwiftRuntimeLibraries iphoneos() {
        return new SwiftRuntimeLibraries("12.2", Map.of(
                "libswift_Concurrency.dylib", "15.0",
                "libswiftCompatibilitySpan.dylib", "26.0"));
    }

    // The log line of the command is part of the output and must not be taken for a library
    @Test
    public void testParseToolOutput() {
        String output = "/opt/xcode/usr/bin/swift-stdlib-tool --print --scan-executable build/dmengine --platform iphoneos\n"
                + CORE + "\n"
                + CONCURRENCY + "\r\n"
                + "ld: warning: something\n"
                + "build/libswift_Relative.dylib\n"
                + "/opt/other/libFoo.dylib\n"
                + "\n";
        assertEquals(List.of(CORE, CONCURRENCY), SwiftRuntimeLibraries.parseToolOutput(output));
        assertTrue(SwiftRuntimeLibraries.parseToolOutput("").isEmpty());
    }

    // The core runtime is never bundled, it comes from the OS through the /usr/lib/swift rpath
    @Test
    public void testSelectKeepsOnlyCompatibilityLibrariesNeededForTheMinimumOs() throws ExtenderException {
        List<File> referenced = List.of(CORE, CONCURRENCY, SPAN);

        assertEquals(List.of(CONCURRENCY, SPAN), iphoneos().select(referenced, "14.0"));
        assertEquals(List.of(CONCURRENCY, SPAN), iphoneos().select(referenced, "14.9.1"));
        assertEquals(List.of(SPAN), iphoneos().select(referenced, "15.0"));
        assertEquals(List.of(SPAN), iphoneos().select(referenced, "25.9"));
        assertTrue(iphoneos().select(referenced, "26.0").isEmpty());
        assertTrue(iphoneos().select(referenced, "27.1").isEmpty());
        assertTrue(iphoneos().select(List.of(CORE), "13.0").isEmpty());
        assertTrue(iphoneos().select(List.of(), "13.0").isEmpty());
    }

    @Test
    public void testSelectIgnoresADuplicateReference() throws ExtenderException {
        assertEquals(List.of(CONCURRENCY), iphoneos().select(List.of(CONCURRENCY, CONCURRENCY), "13.0"));
    }

    // Without the Swift runtime in the OS everything would have to be bundled, which is not supported
    @Test
    public void testMinimumOsVersionNeedsTheSwiftRuntimeOfTheOs() throws ExtenderException {
        iphoneos().checkMinOsVersion("12.2");
        iphoneos().checkMinOsVersion("15.0");
        ExtenderException e = assertThrows(ExtenderException.class, () -> iphoneos().checkMinOsVersion("12.1"));
        assertTrue(e.getMessage().contains("12.2"), e.getMessage());
        assertTrue(e.getMessage().contains("12.1"), e.getMessage());
        assertThrows(ExtenderException.class, () -> iphoneos().checkMinOsVersion("11.0"));
        assertThrows(ExtenderException.class, () -> new SwiftRuntimeLibraries("10.14.4", Map.of()).checkMinOsVersion("10.14"));
    }

    @Test
    public void testFromSdkSettings(@TempDir Path dir) throws IOException, ExtenderException {
        File settings = dir.resolve("SDKSettings.json").toFile();
        Files.writeString(settings.toPath(), """
                {"SupportedTargets": {
                    "iphoneos": {
                        "MinimumDeploymentTarget": "15.0",
                        "SwiftOSRuntimeMinimumDeploymentTarget": "12.2",
                        "SwiftCompatibilityLibraryNotNeededStartingIn": {
                            "libswiftCompatibilitySpan.dylib": "26.0",
                            "libswift_Concurrency.dylib": "15.0"}},
                    "macosx": {"MinimumDeploymentTarget": "12.0"}}}
                """);

        SwiftRuntimeLibraries iphoneos = SwiftRuntimeLibraries.fromSdkSettings(settings, "iphoneos");
        assertEquals(List.of(CONCURRENCY, SPAN), iphoneos.select(List.of(CORE, CONCURRENCY, SPAN), "14.0"));
        assertThrows(ExtenderException.class, () -> iphoneos.checkMinOsVersion("12.0"));

        // a target the SDK does not describe, or describes without Swift support
        assertNull(SwiftRuntimeLibraries.fromSdkSettings(settings, "iphonesimulator"));
        assertNull(SwiftRuntimeLibraries.fromSdkSettings(settings, "macosx"));
    }

    @Test
    public void testFromSdkSettingsRejectsUnreadableSettings(@TempDir Path dir) throws IOException {
        File settings = dir.resolve("SDKSettings.json").toFile();
        Files.writeString(settings.toPath(), "not json");
        assertThrows(ExtenderException.class, () -> SwiftRuntimeLibraries.fromSdkSettings(settings, "iphoneos"));
        assertThrows(ExtenderException.class, () -> SwiftRuntimeLibraries.fromSdkSettings(dir.resolve("missing.json").toFile(), "iphoneos"));
    }
}
