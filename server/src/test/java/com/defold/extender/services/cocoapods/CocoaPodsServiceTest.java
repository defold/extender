package com.defold.extender.services.cocoapods;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.defold.extender.ExtenderException;
import com.defold.extender.TestUtils;


public class CocoaPodsServiceTest {
    private static final String INHERITED_VALUE = "$(inherited)";
    private File emptyPodfile;
    private File withCommentPodfile;
    private File regularPodfile;
    private File wrongPodfile;

    private Map<String, Object> jobContext;

    public static List<Path> specData() {
        return List.of(
            Path.of("test-data/pod_specs/PNChartboostSDKAdapter.json"),
            Path.of("test-data/pod_specs/UnityAds.json"),
            Path.of("test-data/pod_specs/Cuckoo.json"),
            Path.of("test-data/pod_specs/Rtc555Sdk.json"),
            Path.of("test-data/pod_specs/streethawk.json")
        );
    }

    @BeforeEach
    public void setUp() {
        this.emptyPodfile = new File("test-data/podfiles/empty.Podfile");
        this.regularPodfile = new File("test-data/podfiles/regular.Podfile");
        this.wrongPodfile = new File("test-data/podfiles/wrong.Podfile");
        this.withCommentPodfile = new File("test-data/podfiles/with_comments.Podfile");

        this.jobContext = new HashMap<>();
        this.jobContext.putAll(TestUtils.envFileToMap(new File("envs/.env")));
        this.jobContext.putAll(TestUtils.envFileToMap(new File("envs/macos.env")));
    }

    @Test
    public void testPodfileParserRegularSyntax() throws IOException {
        MainPodfile mainPodfile = CocoaPodsService.createMainPodfile();
        CocoaPodsService.parsePodfiles(mainPodfile, List.of(this.regularPodfile));
        String[] expected = {"IronSourcePangleAdapter", "IronSourceSmaatoAdapter", "IronSourceSuperAwesomeAdapter", "IronSourceTencentAdapter", "IronSourceUnityAdsAdapter", "IronSourceYandexAdapter"};
        assertArrayEquals(expected, mainPodfile.podnames.toArray());
    }

    @Test
    public void testPodfileParserComments() throws IOException {
        MainPodfile mainPodfile = CocoaPodsService.createMainPodfile();
        mainPodfile.platformMinVersion = "10.3";
        CocoaPodsService.parsePodfiles(mainPodfile, List.of(this.withCommentPodfile));
        assertEquals(mainPodfile.platformMinVersion, "12.0");
        String[] expected = {"OneTrust-CMP-XCFramework"};
        assertArrayEquals(expected, mainPodfile.podnames.toArray());
    }

    @Test
    public void testPodfileParserWrongSyntax() throws IOException {
        MainPodfile mainPodfile = CocoaPodsService.createMainPodfile();
        CocoaPodsService.parsePodfiles(mainPodfile, List.of(this.wrongPodfile));
        String[] expected = {"IronSourceSuperAwesomeAdapter"};
        assertArrayEquals(expected, mainPodfile.podnames.toArray());
    }

    @Test
    public void testPodfileParserEmptyFile() throws IOException {
        MainPodfile mainPodfile = CocoaPodsService.createMainPodfile();
        CocoaPodsService.parsePodfiles(mainPodfile, List.of(this.emptyPodfile));
        assertTrue(mainPodfile.podnames.isEmpty());
    }

    @Test
    public void testPodfileParserAll() throws IOException {
        MainPodfile mainPodfile = CocoaPodsService.createMainPodfile();
        mainPodfile.platformMinVersion = "9.1";
        CocoaPodsService.parsePodfiles(mainPodfile, List.of(this.emptyPodfile, this.wrongPodfile, this.regularPodfile, this.withCommentPodfile));
        String[] expected = {"IronSourceSuperAwesomeAdapter", "IronSourcePangleAdapter", "IronSourceSmaatoAdapter", "IronSourceSuperAwesomeAdapter", "IronSourceTencentAdapter", "IronSourceUnityAdsAdapter", "IronSourceYandexAdapter", "OneTrust-CMP-XCFramework"};
        assertArrayEquals(expected, mainPodfile.podnames.toArray());
    }

    @ParameterizedTest
    @MethodSource("specData")
    public void testParsePodSpecs(Path spec) throws IOException, ExtenderException {
        String jsonSpec = Files.readString(spec);
        File workingDir = Files.createTempDirectory("parse-spec-test").toFile();
        File podsDir = new File(workingDir, "pods");
        podsDir.mkdir();
        File generatedDir = new File(workingDir, "generated");
        generatedDir.mkdir();
        workingDir.deleteOnExit();
        PodSpecParser.CreatePodSpecArgs args = new PodSpecParser.CreatePodSpecArgs.Builder()
            .setGeneratedDir(generatedDir)
            .setPodsDir(podsDir)
            .setSpecJson(jsonSpec)
            .setWorkingDir(workingDir)
            .setJobContext(this.jobContext)
            .build();
        assertDoesNotThrow(() -> PodSpecParser.createPodSpec(args));
    }

    @ParameterizedTest
    @MethodSource("specData")
    public void testPodSpecsNoInherited(Path spec) throws IOException, ExtenderException {
        String jsonSpec = Files.readString(spec);
        File workingDir = Files.createTempDirectory("flags-spec-test").toFile();
        File podsDir = new File(workingDir, "pods");
        podsDir.mkdir();
        File generatedDir = new File(workingDir, "generated");
        generatedDir.mkdir();
        workingDir.deleteOnExit();
        PodSpecParser.CreatePodSpecArgs args = new PodSpecParser.CreatePodSpecArgs.Builder()
            .setGeneratedDir(generatedDir)
            .setPodsDir(podsDir)
            .setSpecJson(jsonSpec)
            .setWorkingDir(workingDir)
            .setJobContext(this.jobContext)
            .build();
        PodSpec podSpec = PodSpecParser.createPodSpec(args);
        // ios
        assertFalse(podSpec.linkflags.ios.contains(INHERITED_VALUE));
        assertFalse(podSpec.defines.ios.contains(INHERITED_VALUE));
        assertFalse(podSpec.flags.ios.c.contains(INHERITED_VALUE));
        assertFalse(podSpec.flags.ios.cpp.contains(INHERITED_VALUE));
        assertFalse(podSpec.flags.ios.objc.contains(INHERITED_VALUE));
        assertFalse(podSpec.flags.ios.objcpp.contains(INHERITED_VALUE));
        // osx
        assertFalse(podSpec.linkflags.osx.contains(INHERITED_VALUE));
        assertFalse(podSpec.defines.osx.contains(INHERITED_VALUE));
        assertFalse(podSpec.flags.osx.c.contains(INHERITED_VALUE));
        assertFalse(podSpec.flags.osx.cpp.contains(INHERITED_VALUE));
        assertFalse(podSpec.flags.osx.objc.contains(INHERITED_VALUE));
        assertFalse(podSpec.flags.osx.objcpp.contains(INHERITED_VALUE));

        for (PodSpec subspec : podSpec.subspecs) {
            // ios
            assertFalse(subspec.linkflags.ios.contains(INHERITED_VALUE));
            assertFalse(subspec.defines.ios.contains(INHERITED_VALUE));
            assertFalse(subspec.flags.ios.c.contains(INHERITED_VALUE));
            assertFalse(subspec.flags.ios.cpp.contains(INHERITED_VALUE));
            assertFalse(subspec.flags.ios.objc.contains(INHERITED_VALUE));
            assertFalse(subspec.flags.ios.objcpp.contains(INHERITED_VALUE));
            // osx
            assertFalse(subspec.linkflags.osx.contains(INHERITED_VALUE));
            assertFalse(subspec.defines.osx.contains(INHERITED_VALUE));
            assertFalse(subspec.flags.osx.c.contains(INHERITED_VALUE));
            assertFalse(subspec.flags.osx.cpp.contains(INHERITED_VALUE));
            assertFalse(subspec.flags.osx.objc.contains(INHERITED_VALUE));
            assertFalse(subspec.flags.osx.objcpp.contains(INHERITED_VALUE));
        }
        
    }
}
