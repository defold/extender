package com.defold.extender.services.spm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.defold.extender.services.spm.SpmBuildOutputParser.LinkInfo;

public class SpmBuildOutputParserTest {

    private static final String DERIVED_DATA = "/tmp/job123/SwiftPackageManagerService/DerivedData";

    private String dynamicLog;

    @BeforeEach
    public void setUp() throws IOException {
        this.dynamicLog = Files.readString(new File("test-data/spm-buildlogs/dynamic-ios.log").toPath());
    }

    @Test
    public void testFindWrapperLinkLine() {
        String line = SpmBuildOutputParser.findWrapperLinkLine(dynamicLog, "SpmWrapper");
        assertNotNull(line);
        assertTrue(line.contains("-dynamiclib"));
        // the relocatable package prelink (clang -r -o .../Firebase.o) must not match
        assertTrue(line.contains("SpmWrapper.framework/SpmWrapper"));
        assertNull(SpmBuildOutputParser.findWrapperLinkLine(dynamicLog, "OtherWrapper"));
        // the dynamic log has no libtool step
        assertNull(SpmBuildOutputParser.findWrapperLibtoolLine(dynamicLog, "SpmWrapper"));
    }

    @Test
    public void testParseLinkLine() {
        String line = SpmBuildOutputParser.findWrapperLinkLine(dynamicLog, "SpmWrapper");
        LinkInfo info = SpmBuildOutputParser.parseLinkLine(line, DERIVED_DATA);

        // duplicated -l flags collapse, order of first appearance preserved
        assertEquals(List.of("c++", "z", "sqlite3"), info.systemLibs());

        // duplicated -framework flags collapse; system + binary dependency frameworks
        assertEquals(List.of(
            "Accelerate", "UIKit", "Security", "StoreKit",
            "FirebaseAnalytics", "GoogleAppMeasurementIdentitySupport", "GoogleAppMeasurement",
            "GoogleAdsOnDeviceConversion", "FBSDKCoreKit", "FBAEMKit", "FBSDKCoreKit_Basics", "Sentry"),
            info.frameworkNames());

        // job-local DerivedData search paths dropped, Swift runtime paths kept
        assertEquals(List.of(
            "/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/iphoneos",
            "/usr/lib/swift"),
            info.librarySearchPaths());

        // -Xlinker -rpath -Xlinker <value> pairs collapse to their values
        assertEquals(List.of("/usr/lib/swift", "@executable_path/Frameworks"), info.rpaths());

        assertTrue(info.forceLoad().isEmpty());
    }

    @Test
    public void testParseLinkLineGuards() {
        // -lto_library is an ld flag, not a library, whether or not it is wrapped in -Xlinker;
        // a missing response file is reported and skipped, never parsed as a library
        String line = "clang -dynamiclib -Xlinker -lto_library -Xlinker /x/libLTO.dylib "
            + "-lto_library /x/libLTO.dylib @/x/does-not-exist-linker-args.resp "
            + "-lc++ -Wl,-rpath,/usr/lib/swift "
            + "-force_load /x/libFoo.a -o /x/SpmWrapper.framework/SpmWrapper";
        LinkInfo info = SpmBuildOutputParser.parseLinkLine(line, DERIVED_DATA);
        assertEquals(List.of("c++"), info.systemLibs());
        assertEquals(List.of("/usr/lib/swift"), info.rpaths());
        assertEquals(List.of("/x/libFoo.a"), info.forceLoad());
    }

    @Test
    public void testParseLinkLineExpandsResponseFile(@TempDir File tmpDir) throws IOException {
        // Xcode spills long link commands into a response file; the flags inside are part of
        // the link line and are dropped from the engine link unless the @<path> is expanded
        File nested = new File(tmpDir, "nested.resp");
        Files.writeString(nested.toPath(), "-framework Security\n-lz\n");
        File responseFile = new File(tmpDir, "SpmWrapper-linker-args.resp");
        Files.writeString(responseFile.toPath(),
            "-framework FirebaseAnalytics\n"
            + "'-framework' 'FBSDKCoreKit'\n"
            + "-lsqlite3 -L/usr/lib/swift -L" + DERIVED_DATA + "/Build/Products\n"
            + "@nested.resp\n");

        String line = "clang -dynamiclib -lc++ @" + responseFile.getAbsolutePath()
            + " -o /x/SpmWrapper.framework/SpmWrapper";
        LinkInfo info = SpmBuildOutputParser.parseLinkLine(line, DERIVED_DATA);

        assertEquals(List.of("c++", "sqlite3", "z"), info.systemLibs());
        assertEquals(List.of("FirebaseAnalytics", "FBSDKCoreKit", "Security"), info.frameworkNames());
        // DerivedData search paths are dropped inside a response file too
        assertEquals(List.of("/usr/lib/swift"), info.librarySearchPaths());
    }

    @Test
    public void testParseLinkLineResolvesRelativeResponseFile(@TempDir File tmpDir) throws IOException {
        File responseFile = new File(tmpDir, "args.resp");
        Files.writeString(responseFile.toPath(), "-framework StoreKit\n");
        LinkInfo info = SpmBuildOutputParser.parseLinkLine(
            "clang -dynamiclib @args.resp -o /x/SpmWrapper.framework/SpmWrapper", DERIVED_DATA, tmpDir);
        assertEquals(List.of("StoreKit"), info.frameworkNames());
    }

    @Test
    public void testParseLinkLineKeepsDyldPlaceholders() {
        // @rpath/@executable_path/@loader_path are path values, not response files
        LinkInfo info = SpmBuildOutputParser.parseLinkLine(
            "clang -dynamiclib -install_name @rpath/SpmWrapper.framework/SpmWrapper "
            + "-Xlinker -rpath -Xlinker @executable_path/Frameworks -o /x/SpmWrapper.framework/SpmWrapper",
            DERIVED_DATA);
        assertEquals(List.of("@executable_path/Frameworks"), info.rpaths());
    }

    @Test
    public void testFindWrapperLinkLineStreamed() throws IOException {
        // production reads the log from disk one line at a time; a dynamic wrapper's
        // clang -dynamiclib line wins, a static wrapper falls back to its libtool line
        File dynamicLogFile = new File("test-data/spm-buildlogs/dynamic-ios.log");
        String line = SpmBuildOutputParser.findWrapperLinkLine(dynamicLogFile, "SpmWrapper");
        assertNotNull(line);
        assertTrue(line.contains("-dynamiclib"));
        assertNull(SpmBuildOutputParser.findWrapperLinkLine(dynamicLogFile, "OtherWrapper"));

        File staticLogFile = new File("test-data/spm-buildlogs/static-ios.log");
        String libtoolLine = SpmBuildOutputParser.findWrapperLinkLine(staticLogFile, "SpmWrapper");
        assertNotNull(libtoolLine);
        assertTrue(libtoolLine.contains("libtool"));
        assertTrue(libtoolLine.contains("SpmWrapper.framework/SpmWrapper"));
    }

    @Test
    public void testParseModuleMaps() {
        List<String> moduleMaps = SpmBuildOutputParser.parseModuleMaps(dynamicLog);
        // values appear single-quoted in the log; quotes must not leak into the paths
        assertEquals(List.of(
            DERIVED_DATA + "/Build/Intermediates.noindex/GeneratedModuleMaps-iphoneos/GoogleUtilities-Logger.modulemap",
            DERIVED_DATA + "/Build/Intermediates.noindex/GeneratedModuleMaps-iphoneos/GoogleUtilities-Environment.modulemap",
            DERIVED_DATA + "/Build/Intermediates.noindex/GeneratedModuleMaps-iphoneos/third-party-IsAppEncrypted.modulemap"),
            moduleMaps);
    }

    @Test
    public void testFindWrapperLibtoolLine() throws IOException {
        // real log of a MACH_O_TYPE=staticlib build: package targets are archived with
        // libtool too (-o <Target>.o), only the wrapper's line may match
        String staticLog = Files.readString(new File("test-data/spm-buildlogs/static-ios.log").toPath());
        assertNull(SpmBuildOutputParser.findWrapperLinkLine(staticLog, "SpmWrapper"));

        String line = SpmBuildOutputParser.findWrapperLibtoolLine(staticLog, "SpmWrapper");
        assertNotNull(line);
        assertTrue(line.contains("SpmWrapper.framework/SpmWrapper"));

        // a libtool archive line carries no system link dependencies, and its DerivedData
        // search paths are dropped
        LinkInfo info = SpmBuildOutputParser.parseLinkLine(line, DERIVED_DATA);
        assertTrue(info.systemLibs().isEmpty());
        assertTrue(info.frameworkNames().isEmpty());
        assertTrue(info.librarySearchPaths().isEmpty());
        assertTrue(info.rpaths().isEmpty());
    }

    @Test
    public void testFindWrapperLibtoolLineVersionedFramework() {
        // macOS framework bundles are versioned: the binary is <W>.framework/Versions/A/<W>
        String log = "    /x/usr/bin/libtool -static -arch_only arm64 -D -syslibroot /sdk"
            + " -filelist /x/SpmWrapper.LinkFileList"
            + " -o /x/Build/Products/Release/SpmWrapper.framework/Versions/A/SpmWrapper\n";
        assertNotNull(SpmBuildOutputParser.findWrapperLibtoolLine(log, "SpmWrapper"));
        assertNull(SpmBuildOutputParser.findWrapperLibtoolLine(log, "OtherWrapper"));
    }

    @Test
    public void testReadLinkFileList(@TempDir File tmpDir) throws IOException {
        File listFile = new File(tmpDir, "SpmWrapper.LinkFileList");
        Files.writeString(listFile.toPath(), "/a/Dummy.o\n\n/b/Firebase.o\n");
        assertEquals(List.of("/a/Dummy.o", "/b/Firebase.o"), SpmBuildOutputParser.readLinkFileList(listFile));
    }
}
