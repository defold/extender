package com.defold.extender.services.cocoapods;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.naming.InvalidNameException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import com.defold.extender.ExtenderException;

public class CocoaPodsServiceTest {

    private File workingDir;
    private File frameworksDir;

    @BeforeEach
    public void setUp(TestInfo testInfo) throws IOException, InvalidNameException {
        if (testInfo.getDisplayName().contains(" ")) {
            // display name is used as folder prefix so if it contains spaces - ask to remove spaces ))
            throw new InvalidNameException("Test's display name shouldn't contain spaces");
        }
        this.workingDir = Files.createTempDirectory(testInfo.getDisplayName()).toFile();
        this.workingDir.deleteOnExit();
        this.frameworksDir = Path.of(this.workingDir.toString(), "build", "Debugiphoneos", "XCFrameworkIntermediates").toFile();
        this.frameworksDir.mkdirs();
    }

    private static PodBuildSpec specWithSearchPath(String name, File includePath, File frameworkPath) {
        PodBuildSpec spec = new PodBuildSpec();
        spec.name = name;
        if (includePath != null) {
            spec.includePaths.add(includePath);
        }
        spec.frameworkSearchPaths = new java.util.LinkedHashSet<>();
        if (frameworkPath != null) {
            spec.frameworkSearchPaths.add(frameworkPath);
        }
        return spec;
    }

    // ------------------------------------------------------------------
    // findUnpackFailure
    // ------------------------------------------------------------------

    @Test
    public void unpackFailureDetectedInScriptOutput() {
        // this is what a Cocoapods generated <pod>-xcframeworks.sh prints while still exiting with code 0
        String log = String.join("\n",
            "Copying IronSourceAdQualitySDK",
            "warning: [CP] Unable to find matching .xcframework slice in 'ios-arm64 ios-arm64_x86_64-simulator' for the current build architectures (x86_64).",
            "done");
        String failure = CocoaPodsService.findUnpackFailure(log);
        assertEquals("warning: [CP] Unable to find matching .xcframework slice in 'ios-arm64 ios-arm64_x86_64-simulator' for the current build architectures (x86_64).", failure);
    }

    @Test
    public void unpackFailureNotReportedForCleanOutput() {
        assertNull(CocoaPodsService.findUnpackFailure("Copying IronSourceAdQualitySDK\ndone"));
        assertNull(CocoaPodsService.findUnpackFailure(""));
        assertNull(CocoaPodsService.findUnpackFailure(null));
        // an unrelated line that happens to contain the words must not trip the check
        assertNull(CocoaPodsService.findUnpackFailure("Unable to find matching taste in music"));
    }

    // ------------------------------------------------------------------
    // validateUnpackedFrameworks
    // ------------------------------------------------------------------

    @Test
    public void validationPassesWhenFrameworkWasUnpacked() throws IOException, ExtenderException {
        File podDir = new File(this.frameworksDir, "IronSourceAdQualitySDK");
        File header = new File(podDir, "IronSourceAdQuality.framework/Headers/IronSourceAdQuality.h");
        header.getParentFile().mkdirs();
        header.createNewFile();

        PodBuildSpec spec = specWithSearchPath("IronSourceAdQualitySDK", null, podDir);
        CocoaPodsService.validateUnpackedFrameworks(this.frameworksDir, List.of(spec));
    }

    @Test
    public void validationFailsWhenUnpackDirectoryIsEmpty() {
        // this is the issue #989 signature: the xcconfig points at the intermediates dir,
        // the dir was mkdirs()'d, but the unpack script never populated it
        File podDir = new File(this.frameworksDir, "IronSourceAdQualitySDK");
        podDir.mkdirs();

        PodBuildSpec spec = specWithSearchPath("IronSourceAdQualitySDK", null, podDir);
        ExtenderException exc = assertThrows(ExtenderException.class,
            () -> CocoaPodsService.validateUnpackedFrameworks(this.frameworksDir, List.of(spec)));
        assertTrue(exc.getMessage().contains("IronSourceAdQualitySDK"), exc.getMessage());
        assertTrue(exc.getMessage().contains("empty"), exc.getMessage());
    }

    @Test
    public void validationFailsWhenUnpackDirectoryIsMissing() {
        File podDir = new File(this.frameworksDir, "IronSourceAdQualitySDK");

        PodBuildSpec spec = specWithSearchPath("IronSourceAdQualitySDK", podDir, null);
        ExtenderException exc = assertThrows(ExtenderException.class,
            () -> CocoaPodsService.validateUnpackedFrameworks(this.frameworksDir, List.of(spec)));
        assertTrue(exc.getMessage().contains("missing"), exc.getMessage());
    }

    @Test
    public void validationIgnoresPathsOutsideTheIntermediatesDirectory() throws ExtenderException {
        // search paths into the pod source tree are not produced by the unpack step,
        // so a missing one is not this check's business
        File outside = new File(this.workingDir, "pods/SomePod/Headers");

        PodBuildSpec spec = specWithSearchPath("SomePod", outside, null);
        CocoaPodsService.validateUnpackedFrameworks(this.frameworksDir, List.of(spec));
    }

    @Test
    public void validationIgnoresTheIntermediatesRootItself() throws ExtenderException {
        PodBuildSpec spec = specWithSearchPath("SomePod", null, this.frameworksDir);
        CocoaPodsService.validateUnpackedFrameworks(this.frameworksDir, List.of(spec));
    }

    @Test
    public void validationHandlesEscapedWhitespaceInSearchPaths() throws IOException, ExtenderException {
        // XCConfigParser escapes whitespace in the values it produces
        File podDir = new File(this.frameworksDir, "Some Pod");
        podDir.mkdirs();
        new File(podDir, "marker").createNewFile();

        PodBuildSpec spec = specWithSearchPath("SomePod", null, new File(podDir.toString().replace(" ", "\\ ")));
        CocoaPodsService.validateUnpackedFrameworks(this.frameworksDir, List.of(spec));
    }

    @Test
    public void unescapeWhitespaceOnlyRemovesEscapesBeforeWhitespace() {
        assertEquals("/tmp/Some Pod", CocoaPodsService.unescapeWhitespace("/tmp/Some\\ Pod"));
        assertEquals("/tmp/plain", CocoaPodsService.unescapeWhitespace("/tmp/plain"));
        assertEquals("C:\\tmp", CocoaPodsService.unescapeWhitespace("C:\\tmp"));
    }

    // ------------------------------------------------------------------
    // hasVendoredXCFramework
    // ------------------------------------------------------------------

    @Test
    public void vendoredXCFrameworkDetection() {
        PodBuildSpec withXCFramework = new PodBuildSpec();
        withXCFramework.vendoredFrameworks.add("IronSourceAdQualitySDK.xcframework");
        assertTrue(CocoaPodsService.hasVendoredXCFramework(withXCFramework));

        PodBuildSpec plainFramework = new PodBuildSpec();
        plainFramework.vendoredFrameworks.add("SomePod.framework");
        assertFalse(CocoaPodsService.hasVendoredXCFramework(plainFramework));

        assertFalse(CocoaPodsService.hasVendoredXCFramework(new PodBuildSpec()));
    }
}
