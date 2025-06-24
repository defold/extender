package com.defold.extender.services.cocoapods;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@TestInstance(Lifecycle.PER_CLASS)
public class XCConfigParserTest {

    private static final String PODS_WORKING_DIR = "/var/tmp/tmp-dir/pod-build-dir";
    private static final String PODS_DIR = "/var/tmp/tmp-dir/pods-dir";
    // private static final String POD_CONFIGURATION_DIR = String.format("%s/Target Support Files", PODS_DIR);

    XCConfigParser parser;
    Map<String, String> baseVars;

    @BeforeAll
    public void beforeAll() throws IOException {
        File podsDir = Files.createTempDirectory("pods-dir").toFile();
        podsDir.deleteOnExit();
        File workingDir = Files.createTempDirectory("pods-working-dir").toFile();
        workingDir.deleteOnExit();
        this.parser = new XCConfigParser(workingDir, podsDir, "iphones", "Debug");
        this.baseVars = createMockBaseVars();
    }

    private Map<String, String> createMockBaseVars() {
        return  Map.of(
            "PODS_ROOT", "/Users/test-pod/Pods",
            "PODS_BUILD_DIR", "/var/tmp/tmp-dir/pod-build-dir",
            "CONFIGURATION", "debug",
            "EFFECTIVE_PLATFORM_NAME","iphones"
        );
    } 

    private static Stream<Arguments> lineData() {
        return Stream.of(
            Arguments.of("// only comments", null),
            Arguments.of("\t      ARG=1 // comment end line", Pair.of("ARG", "1")),
            Arguments.of("LINE_WITHSEMICOLON=value1 value_w2;;;;", Pair.of("LINE_WITHSEMICOLON", "value1 value_w2")),
            Arguments.of("\t\t\t   \t ARG\t\t    = \t\t\t\t    \t\t\t\t   \"some_values\"", Pair.of("ARG", "\"some_values\"")),
            Arguments.of("PODS_VARIANT1[sdk=*][version=13.0] = value1 value2", Pair.of("PODS_VARIANT1", "value1 value2")),
            Arguments.of("PODS_VARIANT2[sdk=iphone, version=11.0, configuration=debug]= single value", Pair.of("PODS_VARIANT2", "single value"))
        );
    }

    private static Stream<Arguments> postProcessData() {
        return Stream.of(
            Arguments.of("INHERITED_VALUE = $(inherited) additional_value other_value ", "additional_value other_value"),
            Arguments.of("VALUE_SUBSTITUTION1=$(inherited) \"${PODS_ROOT}/Headers/Private\" \"${PODS_ROOT}/Headers/Private/KSCrash\" \"${PODS_ROOT}/Headers/Public\" \"${PODS_ROOT}/Headers/Public/KSCrash\"", "\"/Users/test-pod/Pods/Headers/Private\" \"/Users/test-pod/Pods/Headers/Private/KSCrash\" \"/Users/test-pod/Pods/Headers/Public\" \"/Users/test-pod/Pods/Headers/Public/KSCrash\""),
            Arguments.of("VALUE_SUBSTITUTION2=${PODS_BUILD_DIR}/$(CONFIGURATION)/$(EFFECTIVE_PLATFORM_NAME)", "/var/tmp/tmp-dir/pod-build-dir/debug/iphones"),
            Arguments.of("VALUE_SUBSTITUTION3=${PODS_BUILD_DIR}/${CONFIGURATION}/$(EFFECTIVE_PLATFORM_NAME)", "/var/tmp/tmp-dir/pod-build-dir/debug/iphones")
        );
    }

    private static Stream<Arguments> parsingData() {
        String podsConfigurationBuildDir = String.format("%s/build/%s%s", PODS_WORKING_DIR, "Debug", "iphones");
        String podsXCFrameworksBuildDir = String.format("%s/XCFrameworkIntermediates", podsConfigurationBuildDir, "Debug", "iphones");
        return Stream.of(
            Arguments.of("AppLovinSDK", new File("test-data/xcconfigs/AppLovinSDK.xcconfig"), Map.of(
                "FRAMEWORK_SEARCH_PATHS", String.format("\"%s/AppLovinSDK/applovin-ios-sdk-13.0.1\" \"%s/AppLovinSDK\"", PODS_DIR, podsXCFrameworksBuildDir),
                "GCC_PREPROCESSOR_DEFINITIONS", "COCOAPODS=1",
                "OTHER_LDFLAGS", "-ObjC",
                "OTHER_SWIFT_FLAGS", String.format("-D COCOAPODS -import-underlying-module -Xcc -fmodule-map-file=\"%s/Headers/Public/AppLovinSDK/AppLovinSDK.modulemap\"", PODS_DIR),
                "PODS_ROOT", PODS_DIR,
                "PODS_TARGET_SRCROOT", String.format("%s/AppLovinSDK", PODS_DIR)
            )),
            Arguments.of("AppMetricaLog", new File("test-data/xcconfigs/AppMetricaLog.xcconfig"), Map.of(
                "DEFINES_MODULE", "YES",
                "GCC_PREPROCESSOR_DEFINITIONS", "COCOAPODS=1",
                "HEADER_SEARCH_PATHS", String.format("\"%s/Headers/Private\" \"%s/Headers/Private/AppMetricaLog\" \"%s/Headers/Public\" \"%s/Headers/Public/AppMetricaLog\"", PODS_DIR, PODS_DIR, PODS_DIR, PODS_DIR),
                "PODS_TARGET_SRCROOT", String.format("%s/AppMetricaLog", PODS_DIR)
            )),
            Arguments.of("DivKit", new File("test-data/xcconfigs/DivKit.xcconfig"), Map.of(
                "GCC_PREPROCESSOR_DEFINITIONS", "COCOAPODS=1",
                "OTHER_CFLAGS", String.format("-fmodule-map-file=\"%s/DivKit_LayoutKit/LayoutKit.modulemap\" -fmodule-map-file=\"%s/DivKit_LayoutKitInterface/LayoutKitInterface.modulemap\" -fmodule-map-file=\"%s/DivKit_Serialization/Serialization.modulemap\" -fmodule-map-file=\"%s/VGSL/VGSL.modulemap\" -fmodule-map-file=\"%s/VGSLFundamentals/VGSLFundamentals.modulemap\" -fmodule-map-file=\"%s/VGSLNetworking/VGSLNetworking.modulemap\" -fmodule-map-file=\"%s/VGSLUI/VGSLUI.modulemap\"", podsConfigurationBuildDir, podsConfigurationBuildDir, podsConfigurationBuildDir, podsConfigurationBuildDir, podsConfigurationBuildDir, podsConfigurationBuildDir, podsConfigurationBuildDir),
                "OTHER_SWIFT_FLAGS", String.format("-D COCOAPODS -Xcc -fmodule-map-file=\"%s/DivKit_LayoutKit/LayoutKit.modulemap\" -Xcc -fmodule-map-file=\"%s/DivKit_LayoutKitInterface/LayoutKitInterface.modulemap\" -Xcc -fmodule-map-file=\"%s/DivKit_Serialization/Serialization.modulemap\" -Xcc -fmodule-map-file=\"%s/VGSL/VGSL.modulemap\" -Xcc -fmodule-map-file=\"%s/VGSLFundamentals/VGSLFundamentals.modulemap\" -Xcc -fmodule-map-file=\"%s/VGSLNetworking/VGSLNetworking.modulemap\" -Xcc -fmodule-map-file=\"%s/VGSLUI/VGSLUI.modulemap\" -import-underlying-module -Xcc -fmodule-map-file=\"%s/Headers/Public/DivKit/DivKit.modulemap\"", podsConfigurationBuildDir, podsConfigurationBuildDir, podsConfigurationBuildDir, podsConfigurationBuildDir, podsConfigurationBuildDir, podsConfigurationBuildDir, podsConfigurationBuildDir, PODS_DIR),
                "PODS_TARGET_SRCROOT", String.format("%s/DivKit", PODS_DIR),
                "SWIFT_INCLUDE_PATHS", String.format("\"%s/DivKit_LayoutKit\" \"%s/DivKit_LayoutKitInterface\" \"%s/DivKit_Serialization\" \"%s/VGSL\" \"%s/VGSLFundamentals\" \"%s/VGSLNetworking\" \"%s/VGSLUI\"", podsConfigurationBuildDir, podsConfigurationBuildDir, podsConfigurationBuildDir, podsConfigurationBuildDir, podsConfigurationBuildDir, podsConfigurationBuildDir, podsConfigurationBuildDir)
            )),
            Arguments.of("KSCrash", new File("test-data/xcconfigs/KSCrash.xcconfig"), Map.of(
                "GCC_PREPROCESSOR_DEFINITIONS", "COCOAPODS=1",
                "HEADER_SEARCH_PATHS", String.format("\"%s/Headers/Private\" \"%s/Headers/Private/KSCrash\" \"%s/Headers/Public\" \"%s/Headers/Public/KSCrash\"", PODS_DIR, PODS_DIR, PODS_DIR, PODS_DIR),
                "PODS_TARGET_SRCROOT", String.format("%s/KSCrash", PODS_DIR)
            )),
            Arguments.of("MintegralAdSDK", new File("test-data/xcconfigs/MintegralAdSDK.xcconfig"), Map.of(
                "FRAMEWORK_SEARCH_PATHS", String.format("\"%s/MintegralAdSDK/Fmk\" \"%s/MintegralAdSDK/BannerAd\" \"%s/MintegralAdSDK/BidNativeAd\" \"%s/MintegralAdSDK/InterstitialVideoAd\" \"%s/MintegralAdSDK/NativeAd\" \"%s/MintegralAdSDK/NewInterstitialAd\" \"%s/MintegralAdSDK/RewardVideoAd\"", PODS_DIR, podsXCFrameworksBuildDir, podsXCFrameworksBuildDir, podsXCFrameworksBuildDir, podsXCFrameworksBuildDir, podsXCFrameworksBuildDir, podsXCFrameworksBuildDir),
                "GCC_PREPROCESSOR_DEFINITIONS", "COCOAPODS=1",
                "OTHER_LDFLAGS", "-ObjC",
                "OTHER_SWIFT_FLAGS", String.format("-D COCOAPODS -import-underlying-module -Xcc -fmodule-map-file=\"%s/Headers/Public/MintegralAdSDK/MintegralAdSDK.modulemap\"", PODS_DIR),
                "PODS_TARGET_SRCROOT", String.format("%s/MintegralAdSDK", PODS_DIR)
            )),
            Arguments.of("Sentry", new File("test-data/xcconfigs/Sentry.xcconfig"), Map.of(
                "APPLICATION_EXTENSION_API_ONLY", "YES",
                "CLANG_CXX_LANGUAGE_STANDARD", "c++14",
                "CLANG_CXX_LIBRARY", "libc++",
                "GCC_ENABLE_CPP_EXCEPTIONS", "YES",
                "GCC_PREPROCESSOR_DEFINITIONS", "COCOAPODS=1",
                "HEADER_SEARCH_PATHS", String.format("\"%s/Headers/Private\" \"%s/Headers/Private/Sentry\" \"%s/Headers/Public\"", PODS_DIR, PODS_DIR, PODS_DIR),
                "OTHER_CFLAGS", "-DAPPLICATION_EXTENSION_API_ONLY_YES",
                "OTHER_SWIFT_FLAGS", String.format("-D COCOAPODS -import-underlying-module -Xcc -fmodule-map-file=\"%s/Headers/Public/Sentry/Sentry.modulemap\"", PODS_DIR),
                "PODS_TARGET_SRCROOT", String.format("%s/Sentry", PODS_DIR)
            )),
            Arguments.of("VGSLFundamentals", new File("test-data/xcconfigs/VGSLFundamentals.xcconfig"), Map.of(
                "GCC_PREPROCESSOR_DEFINITIONS", "COCOAPODS=1",
                "OTHER_SWIFT_FLAGS", String.format("-D COCOAPODS -import-underlying-module -Xcc -fmodule-map-file=\"%s/Headers/Public/VGSLFundamentals/VGSLFundamentals.modulemap\" -enable-experimental-feature AccessLevelOnImport", PODS_DIR)
            ))
        );
    }


    @Test
    public void testBaseVariables() {
        Map<String, String> baseVars = parser.calculateBaseVariables("testPodSDK");
    }

    @ParameterizedTest
    @MethodSource("lineData")
    public void testParseLine(String inputLine, Pair<String, String> expectedResult) {
        Pair<String, String> result = parser.parseLine(inputLine);
        assertEquals(expectedResult, result);
    }

    @ParameterizedTest
    @MethodSource("postProcessData")
    public void testPostProcessLine(String inputLine, String expectedResult) {
        Pair<String, String> tmp = parser.parseLine(inputLine);
        String result = parser.postProcessValue(tmp.getRight(), baseVars);
        assertEquals(expectedResult, result);
    }

    @ParameterizedTest(name = "{index}_testParsing_{0}")
    @MethodSource("parsingData")
    public void testParsing(String podName, File inputSource, Map<String, String> expectedSubset) throws IOException {
        XCConfigParser parser = new XCConfigParser(new File(PODS_WORKING_DIR), new File(PODS_DIR), "iphones", "Debug");
        Map<String, String> result = parser.parse(podName, inputSource);
        for (Map.Entry<String, String> entry : expectedSubset.entrySet()) {
            String key = entry.getKey();
            assertTrue(result.containsKey(key));
            assertEquals(entry.getValue(), result.get(key));
        }
    }
}
