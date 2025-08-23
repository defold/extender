package com.defold.extender.services.cocoapods;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import javax.naming.InvalidNameException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.defold.extender.ExtenderException;


public class PodSpecParserTest {
    private static final String INHERITED_VALUE = "$(inherited)";

    private static Stream<Arguments> specData() {
        return Stream.of(
            Arguments.of("PNChartboostSDKAdapter", Path.of("test-data/pod_specs/PNChartboostSDKAdapter.json")),
            Arguments.of("UnityAds", Path.of("test-data/pod_specs/UnityAds.json")),
            Arguments.of("Cuckoo", Path.of("test-data/pod_specs/Cuckoo.json")),
            Arguments.of("Rtc555Sdk", Path.of("test-data/pod_specs/Rtc555Sdk.json")),
            Arguments.of("streethawk", Path.of("test-data/pod_specs/streethawk.json")),
            Arguments.of("AXPracticalHUD", "test-data/pod_specs/AXPracticalHUD.json"),
            Arguments.of("PubNub", "test-data/pod_specs/PubNub.json"),
            Arguments.of("TPNiOS", "test-data/pod_specs/TPNiOS.json"),
            Arguments.of("Wilddog", "test-data/pod_specs/Wilddog.json")
        );
    }

    @BeforeEach
    public void setUp(TestInfo testInfo) throws IOException, InvalidNameException {
        if (testInfo.getDisplayName().contains(" ")) {
            // display name is used as folder prefix so if it contains spaces - ask to remove spaces ))
            throw new InvalidNameException("Test's display name shouldn't contain spaces");
        }
    }

    @ParameterizedTest(name = "{index}_testParsePodSpecs_{0}")
    @MethodSource("specData")
    public void testParsePodSpecs(String alias, Path spec) throws IOException, ExtenderException {
        String jsonSpec = Files.readString(spec);
        assertDoesNotThrow(() -> PodSpecParser.createPodSpec(PodSpecParser.parseJson(jsonSpec), PodUtils.Platform.IPHONEOS, null));
    }

    @ParameterizedTest(name = "{index}_testPodSpecsNoInherited_{0}")
    @MethodSource("specData")
    public void testPodSpecsNoInherited(String alias, Path spec) throws IOException, ExtenderException {
        String jsonSpec = Files.readString(spec);
        PodSpec podSpec = PodSpecParser.createPodSpec(PodSpecParser.parseJson(jsonSpec), PodUtils.Platform.IPHONEOS, null);
        assertFalse(podSpec.linkflags.contains(INHERITED_VALUE));
        assertFalse(podSpec.defines.contains(INHERITED_VALUE));
        assertFalse(podSpec.flags.c.contains(INHERITED_VALUE));
        assertFalse(podSpec.flags.cpp.contains(INHERITED_VALUE));
        assertFalse(podSpec.flags.objc.contains(INHERITED_VALUE));
        assertFalse(podSpec.flags.objcpp.contains(INHERITED_VALUE));

        for (PodSpec subspec : podSpec.subspecs) {
            assertFalse(subspec.linkflags.contains(INHERITED_VALUE));
            assertFalse(subspec.defines.contains(INHERITED_VALUE));
            assertFalse(subspec.flags.c.contains(INHERITED_VALUE));
            assertFalse(subspec.flags.cpp.contains(INHERITED_VALUE));
            assertFalse(subspec.flags.objc.contains(INHERITED_VALUE));
            assertFalse(subspec.flags.objcpp.contains(INHERITED_VALUE));
        }
    }

    @Test
    public void testSpecBraceExpanderVendoredFrameworks() throws ExtenderException, IOException {
        String jsonSpec = Files.readString(Path.of("test-data/pod_specs/TPNiOS.json"));
        PodSpec podSpec = PodSpecParser.createPodSpec(PodSpecParser.parseJson(jsonSpec), PodUtils.Platform.IPHONEOS, null);
        String[] expectedValues = new String[]{
            "core/AnyThinkBanner.xcframework",
            "core/AnyThinkSplash.xcframework",
            "core/AnyThinkRewardedVideo.xcframework",
            "core/AnyThinkInterstitial.xcframework",
            "core/AnyThinkNative.xcframework",
            "core/AnyThinkMediaVideo.xcframework",
            "core/AnyThinkSDK.xcframework"
        };
        assertArrayEquals(expectedValues, podSpec.subspecs.get(0).vendoredFrameworks.toArray());
    }

    @Test
    public void testDefaultSubspecs() throws ExtenderException, IOException {
        String jsonSpec = Files.readString(Path.of("test-data/pod_specs/Wilddog.json"));
        PodSpec podSpec = PodSpecParser.createPodSpec(PodSpecParser.parseJson(jsonSpec), PodUtils.Platform.IPHONEOS, null);
        String[] expectedValues = new String[]{
            "Public",
            "Sync",
            "Auth",
            "Core"
        };
        assertArrayEquals(expectedValues, podSpec.defaultSubspecs.toArray());
    }
}
