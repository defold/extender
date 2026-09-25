package com.defold.extender.services.cocoapods;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import com.defold.extender.ExtenderException;
import com.defold.extender.utils.PodBuildUtil;

public class PodBuildUtilTest {

    private File workingDir;

    @BeforeEach
    public void setUp(TestInfo testInfo) throws IOException {
        this.workingDir = Files.createTempDirectory(testInfo.getDisplayName()).toFile();
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (this.workingDir != null && this.workingDir.exists()) {
            Files.walk(this.workingDir.toPath())
                .sorted((a, b) -> b.compareTo(a))
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        // ignore cleanup errors
                    }
                });
        }
    }

    // A pod whose public headers live inside a vendored .xcframework (e.g. IronSourceAdQualitySDK)
    // has no directory literally named after the module relative to the compiler's working
    // directory. generateHeaderMap() must map such a header to its own real directory, not to
    // "<moduleName>/<filename>", or clang's header map lookup resolves to a path that doesn't
    // exist and the compile fails with "file not found" instead of falling back to the correct
    // -I search directory.
    @Test
    public void testPublicHeaderInXCFrameworkResolvesToItsRealDirectory() throws IOException, ExtenderException {
        File xcframeworkHeadersDir = new File(this.workingDir, "SomeSDK.xcframework/ios-arm64/Headers");
        xcframeworkHeadersDir.mkdirs();
        File header = new File(xcframeworkHeadersDir, "SomeHeader.h");
        Files.writeString(header.toPath(), "// header contents");

        File intermediateDir = new File(this.workingDir, "intermediate");
        intermediateDir.mkdirs();

        PodBuildSpec spec = new PodBuildSpec();
        spec.name = "SomeSDK";
        spec.moduleName = "SomeSDK";
        spec.publicHeaders.add(header);
        spec.headerMapFile = new File(intermediateDir, "SomeSDK.hmap");

        PodBuildUtil.generateHeaderMap(spec);

        File jsonHeaderMap = new File(intermediateDir, "SomeSDK.json");
        assertTrue(jsonHeaderMap.exists(), "header map JSON should have been written");
        String contents = Files.readString(jsonHeaderMap.toPath());
        // org.json.simple escapes '/' as '\/' when serializing, so compare against the
        // escaped form rather than the raw absolute path
        String escapedRealDirectory = xcframeworkHeadersDir.getAbsolutePath().replace("/", "\\/");

        // the entry must point at the header's real, absolute directory, not at "SomeSDK/",
        // which does not exist anywhere relative to the compiler's working directory
        assertTrue(contents.contains(escapedRealDirectory),
            "header map should reference the header's real absolute directory:\n" + contents);
        assertTrue(new File(xcframeworkHeadersDir.getAbsolutePath(), "SomeHeader.h").isFile(),
            "sanity check: the real header file should exist at the resolved path");
    }
}
