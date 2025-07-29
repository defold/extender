// package com.defold.extender.services.cocoapods;

// import static org.junit.jupiter.api.Assertions.assertArrayEquals;
// import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
// import static org.junit.jupiter.api.Assertions.assertEquals;
// import static org.junit.jupiter.api.Assertions.assertFalse;
// import static org.junit.jupiter.api.Assertions.assertTrue;

// import java.io.File;
// import java.io.IOException;
// import java.nio.file.Files;
// import java.nio.file.Path;
// import java.nio.file.StandardCopyOption;
// import java.util.HashMap;
// import java.util.List;
// import java.util.Map;
// import java.util.stream.Stream;

// import javax.naming.InvalidNameException;

// import org.junit.jupiter.api.BeforeEach;
// import org.junit.jupiter.api.Test;
// import org.junit.jupiter.api.TestInfo;
// import org.junit.jupiter.api.condition.EnabledOnOs;
// import org.junit.jupiter.api.condition.OS;
// import org.junit.jupiter.params.ParameterizedTest;
// import org.junit.jupiter.params.provider.Arguments;
// import org.junit.jupiter.params.provider.MethodSource;

// import com.defold.extender.ExtenderException;
// import com.defold.extender.TestUtils;


// public class CocoaPodsServiceTest {
//     private static final String INHERITED_VALUE = "$(inherited)";
//     private File emptyPodfile;
//     private File withCommentPodfile;
//     private File regularPodfile;
//     private File wrongPodfile;

//     private Map<String, Object> jobContext;

//     private File workingDir;
//     private File buildDir;
//     private File podsDir;
//     private File frameworksDir;

//     class EmptyConfigParser implements IConfigParser {
//         @Override
//         public Map<String, String> parse(String moduleName, String podName, File xcconfig) throws IOException {
//             return Map.of();
//         }

//     }

//     private static Stream<Arguments> specData() {
//         return Stream.of(
//             Arguments.of("PNChartboostSDKAdapter", Path.of("test-data/pod_specs/PNChartboostSDKAdapter.json")),
//             Arguments.of("UnityAds", Path.of("test-data/pod_specs/UnityAds.json")),
//             Arguments.of("Cuckoo", Path.of("test-data/pod_specs/Cuckoo.json")),
//             Arguments.of("Rtc555Sdk", Path.of("test-data/pod_specs/Rtc555Sdk.json")),
//             Arguments.of("streethawk", Path.of("test-data/pod_specs/streethawk.json")),
//             Arguments.of("AXPracticalHUD", "test-data/pod_specs/AXPracticalHUD.json"),
//             Arguments.of("PubNub", "test-data/pod_specs/PubNub.json"),
//             Arguments.of("TPNiOS", "test-data/pod_specs/TPNiOS.json"),
//             Arguments.of("Wilddog", "test-data/pod_specs/Wilddog.json")
//         );
//     }

//     private static File createEmptyFiles(File podsDir, String podName, String[] filesToCreate) throws IOException {
//         File resultFolder = new File(podsDir, podName);
//         for (String f : filesToCreate) {
//             File emptyF = new File(resultFolder, f);
//             emptyF.getParentFile().mkdirs();
//             emptyF.createNewFile();
//         }
//         return resultFolder;
//     }

//     @BeforeEach
//     public void setUp(TestInfo testInfo) throws IOException, InvalidNameException {
//         this.emptyPodfile = new File("test-data/podfiles/empty.Podfile");
//         this.regularPodfile = new File("test-data/podfiles/regular.Podfile");
//         this.wrongPodfile = new File("test-data/podfiles/wrong.Podfile");
//         this.withCommentPodfile = new File("test-data/podfiles/with_comments.Podfile");

//         this.jobContext = new HashMap<>();
//         this.jobContext.putAll(TestUtils.envFileToMap(new File("envs/.env")));
//         this.jobContext.putAll(TestUtils.envFileToMap(new File("envs/macos.env")));

//         if (testInfo.getDisplayName().contains(" ")) {
//             // display name is used as folder prefix so if it contains spaces - ask to remove spaces ))
//             throw new InvalidNameException("Test's display name shouldn't contain spaces");
//         }
//         this.workingDir = Files.createTempDirectory(testInfo.getDisplayName()).toFile();
//         this.buildDir = new File(this.workingDir, "build");
//         this.buildDir.mkdir();
//         this.podsDir = new File(this.workingDir, "pods");
//         this.podsDir.mkdir();
//         this.frameworksDir = Path.of(this.buildDir.toString(), "Debugiphoneos", "XCFrameworkIntermediates").toFile();
//         this.frameworksDir.mkdirs();
//         this.workingDir.deleteOnExit();
//     }

//     @Test
//     public void testPodfileParserRegularSyntax() throws IOException, PodfileParsingException {
//         PodfileParser.ParseResult res = CocoaPodsService.parsePodfiles(List.of(this.regularPodfile), "ios", "11.0");
//         String[] expected = {"IronSourcePangleAdapter", "IronSourceSmaatoAdapter", "IronSourceSuperAwesomeAdapter", "IronSourceTencentAdapter", "IronSourceUnityAdsAdapter", "IronSourceYandexAdapter"};
//         assertArrayEquals(expected, res.podDefinitions.toArray());
//     }

//     @Test
//     public void testPodfileParserComments() throws IOException, PodfileParsingException {
//         PodfileParser.ParseResult res = CocoaPodsService.parsePodfiles(List.of(this.withCommentPodfile), "ios", "10.3");
//         assertEquals(res.minVersion, "12.0");
//         String[] expected = {"OneTrust-CMP-XCFramework"};
//         assertArrayEquals(expected, res.podDefinitions.toArray());
//     }

//     @Test
//     public void testPodfileParserWrongSyntax() throws IOException, PodfileParsingException {
//         PodfileParser.ParseResult res = CocoaPodsService.parsePodfiles(List.of(this.wrongPodfile), "ios", "11.0");
//         String[] expected = {"IronSourceSuperAwesomeAdapter"};
//         assertArrayEquals(expected, res.podDefinitions.toArray());
//     }

//     @Test
//     public void testPodfileParserEmptyFile() throws IOException, PodfileParsingException {
//         PodfileParser.ParseResult res = CocoaPodsService.parsePodfiles(List.of(this.emptyPodfile), "ios", "11.0");
//         assertTrue(res.podDefinitions.isEmpty());
//     }

//     @Test
//     public void testPodfileParserAll() throws IOException, PodfileParsingException {
//         PodfileParser.ParseResult result = CocoaPodsService.parsePodfiles(List.of(this.emptyPodfile, this.wrongPodfile, this.regularPodfile, this.withCommentPodfile), "ios", "9.1");
//         String[] expected = {"IronSourceSuperAwesomeAdapter", "IronSourcePangleAdapter", "IronSourceSmaatoAdapter", "IronSourceSuperAwesomeAdapter", "IronSourceTencentAdapter", "IronSourceUnityAdsAdapter", "IronSourceYandexAdapter", "OneTrust-CMP-XCFramework"};
//         assertArrayEquals(expected, result.podDefinitions.toArray());
//     }

//     @ParameterizedTest(name = "{index}_testParsePodSpecs_{0}")
//     @MethodSource("specData")
//     public void testParsePodSpecs(String alias, Path spec) throws IOException, ExtenderException {
//         String jsonSpec = Files.readString(spec);
//         PodSpecParser.CreatePodSpecArgs args = new PodSpecParser.CreatePodSpecArgs.Builder()
//             // .setBuildDir(this.buildDir)
//             .setPodsDir(this.podsDir)
//             .setSpecJson(jsonSpec)
//             .setJobContext(this.jobContext)
//             .setSelectedPlatform(PodSpecParser.Platform.IPHONEOS)
//             // .setConfiguration("Debug")
//             .setConfigParser(new EmptyConfigParser())
//             .build();
//         assertDoesNotThrow(() -> PodSpecParser.createPodSpec(args));
//     }

//     @ParameterizedTest(name = "{index}_testPodSpecsNoInherited_{0}")
//     @MethodSource("specData")
//     public void testPodSpecsNoInherited(String alias, Path spec) throws IOException, ExtenderException {
//         String jsonSpec = Files.readString(spec);
//         PodSpecParser.CreatePodSpecArgs args = new PodSpecParser.CreatePodSpecArgs.Builder()
//             // .setBuildDir(this.buildDir)
//             .setPodsDir(this.podsDir)
//             .setSpecJson(jsonSpec)
//             .setJobContext(this.jobContext)
//             .setSelectedPlatform(PodSpecParser.Platform.IPHONEOS)
//             // .setConfiguration("Debug")
//             .setConfigParser(new EmptyConfigParser())
//             .build();
//         PodSpec podSpec = PodSpecParser.createPodSpec(args);
//         assertFalse(podSpec.linkflags.contains(INHERITED_VALUE));
//         assertFalse(podSpec.defines.contains(INHERITED_VALUE));
//         assertFalse(podSpec.flags.c.contains(INHERITED_VALUE));
//         assertFalse(podSpec.flags.cpp.contains(INHERITED_VALUE));
//         assertFalse(podSpec.flags.objc.contains(INHERITED_VALUE));
//         assertFalse(podSpec.flags.objcpp.contains(INHERITED_VALUE));

//         for (PodSpec subspec : podSpec.subspecs) {
//             assertFalse(subspec.linkflags.contains(INHERITED_VALUE));
//             assertFalse(subspec.defines.contains(INHERITED_VALUE));
//             assertFalse(subspec.flags.c.contains(INHERITED_VALUE));
//             assertFalse(subspec.flags.cpp.contains(INHERITED_VALUE));
//             assertFalse(subspec.flags.objc.contains(INHERITED_VALUE));
//             assertFalse(subspec.flags.objcpp.contains(INHERITED_VALUE));
//         }
//     }

//     @Test
//     public void testSpecBraceExpanderVendoredFrameworks() throws ExtenderException, IOException {
//         String jsonSpec = Files.readString(Path.of("test-data/pod_specs/TPNiOS.json"));
//         PodSpecParser.CreatePodSpecArgs args = new PodSpecParser.CreatePodSpecArgs.Builder()
//             // .setBuildDir(this.buildDir)
//             .setPodsDir(this.podsDir)
//             .setSpecJson(jsonSpec)
//             .setJobContext(this.jobContext)
//             .setSelectedPlatform(PodSpecParser.Platform.IPHONEOS)
//             // .setConfiguration("Debug")
//             .setConfigParser(new EmptyConfigParser())
//             .build();
//         PodSpec podSpec = PodSpecParser.createPodSpec(args);
//         String[] expectedValues = new String[]{
//             "core/AnyThinkBanner.xcframework",
//             "core/AnyThinkSplash.xcframework",
//             "core/AnyThinkRewardedVideo.xcframework",
//             "core/AnyThinkInterstitial.xcframework",
//             "core/AnyThinkNative.xcframework",
//             "core/AnyThinkMediaVideo.xcframework",
//             "core/AnyThinkSDK.xcframework"
//         };
//         assertArrayEquals(expectedValues, podSpec.subspecs.get(0).vendoredFrameworks.toArray());
//     }

//     @Test
//     public void testSpecBraceExpanderSourceFiles() throws ExtenderException, IOException {
//         String jsonSpec = Files.readString(Path.of("test-data/pod_specs/PubNub.json"));
//         PodSpecParser.CreatePodSpecArgs args = new PodSpecParser.CreatePodSpecArgs.Builder()
//             // .setBuildDir(this.buildDir)
//             .setPodsDir(this.podsDir)
//             .setSpecJson(jsonSpec)
//             .setJobContext(this.jobContext)
//             .setSelectedPlatform(PodSpecParser.Platform.IPHONEOS)
//             // .setConfiguration("Debug")
//             .setConfigParser(new EmptyConfigParser())
//             .build();
//         // create empty files to simulate sources
//         String[] simulatedFiles = new String[]{
//             "PubNub/Core/core1/core11.cpp",
//             "PubNub/Core/core1/core12.cpp",
//             "PubNub/Core/core1/core1.h",
//             "PubNub/Core/core2/core21.cpp",
//             "PubNub/Core/core2/core22.cpp",
//             "PubNub/Core/core2/core2.h",

//             "PubNub/Data/data1/data11.cpp",
//             "PubNub/Data/data1/data12.cpp",
//             "PubNub/Data/data1/data1.h",
//             "PubNub/Data/data2/data21.cpp",
//             "PubNub/Data/data2/data22.cpp",
//             "PubNub/Data/data2/data2.h",

//             "PubNub/Misc/misc1/misc11.cpp",
//             "PubNub/Misc/misc1/misc12.cpp",
//             "PubNub/Misc/misc1/misc1.h",
//             "PubNub/Misc/misc2/misc21.cpp",
//             "PubNub/Misc/misc2/misc22.cpp",
//             "PubNub/Misc/misc2/misc2.h",

//             "PubNub/Network/network1/network11.cpp",
//             "PubNub/Network/network1/network12.cpp",
//             "PubNub/Network/network1/network1.h",
//             "PubNub/Network/network2/network21.cpp",
//             "PubNub/Network/network2/network22.cpp",
//             "PubNub/Network/network2/network2.h"
//         };
//         File pubNubFolder = createEmptyFiles(this.podsDir, "PubNub", simulatedFiles);

//         PodSpec podSpec = PodSpecParser.createPodSpec(args);
//         File[] expectedValues = new File[]{
//             new File(pubNubFolder, "PubNub/Core/core1/core11.cpp"),
//             new File(pubNubFolder, "PubNub/Core/core1/core12.cpp"),
//             new File(pubNubFolder, "PubNub/Core/core2/core21.cpp"),
//             new File(pubNubFolder, "PubNub/Core/core2/core22.cpp"),

//             new File(pubNubFolder, "PubNub/Data/data1/data11.cpp"),
//             new File(pubNubFolder, "PubNub/Data/data1/data12.cpp"),
//             new File(pubNubFolder, "PubNub/Data/data2/data21.cpp"),
//             new File(pubNubFolder, "PubNub/Data/data2/data22.cpp"),

//             new File(pubNubFolder, "PubNub/Misc/misc1/misc11.cpp"),
//             new File(pubNubFolder, "PubNub/Misc/misc1/misc12.cpp"),
//             new File(pubNubFolder, "PubNub/Misc/misc2/misc21.cpp"),
//             new File(pubNubFolder, "PubNub/Misc/misc2/misc22.cpp"),

//             new File(pubNubFolder, "PubNub/Network/network1/network11.cpp"),
//             new File(pubNubFolder, "PubNub/Network/network1/network12.cpp"),
//             new File(pubNubFolder, "PubNub/Network/network2/network21.cpp"),
//             new File(pubNubFolder, "PubNub/Network/network2/network22.cpp")
//         };
//         assertArrayEquals(expectedValues, podSpec.subspecs.get(0).sourceFiles.toArray());
//     }

//     @Test
//     public void testDefaultSubspecs() throws ExtenderException, IOException {
//         String jsonSpec = Files.readString(Path.of("test-data/pod_specs/Wilddog.json"));
//         PodSpecParser.CreatePodSpecArgs args = new PodSpecParser.CreatePodSpecArgs.Builder()
//             // .setBuildDir(this.buildDir)
//             .setPodsDir(this.podsDir)
//             .setSpecJson(jsonSpec)
//             .setJobContext(this.jobContext)
//             .setSelectedPlatform(PodSpecParser.Platform.IPHONEOS)
//             // .setConfiguration("Debug")
//             .setConfigParser(new EmptyConfigParser())
//             .build();
//         PodSpec podSpec = PodSpecParser.createPodSpec(args);
//         String[] expectedValues = new String[]{
//             "Public",
//             "Sync",
//             "Auth",
//             "Core"
//         };
//         assertArrayEquals(expectedValues, podSpec.defaultSubspecs.toArray());
//     }

//     @Test
//     public void testPodResourceFromBundle() throws IOException, ExtenderException {
//         String jsonSpec = Files.readString(Path.of("test-data/pod_specs/AXPracticalHUD.json"));
//         PodSpecParser.CreatePodSpecArgs args = new PodSpecParser.CreatePodSpecArgs.Builder()
//             // .setBuildDir(this.buildDir)
//             .setPodsDir(this.podsDir)
//             .setSpecJson(jsonSpec)
//             .setJobContext(this.jobContext)
//             .setSelectedPlatform(PodSpecParser.Platform.IPHONEOS)
//             // .setConfiguration("Debug")
//             .setConfigParser(new EmptyConfigParser())
//             .build();
//         String[] simulatedFiles = new String[]{
//             "AXPracticalHUD/AXPracticalHUD/AXPracticalHUD.bundle/ax_hud_error@2x.png",
//             "AXPracticalHUD/AXPracticalHUD/AXPracticalHUD.bundle/ax_hud_error@3x.png",
//             "AXPracticalHUD/AXPracticalHUD/AXPracticalHUD.bundle/ax_hud_success@2x.png",
//             "AXPracticalHUD/AXPracticalHUD/AXPracticalHUD.bundle/ax_hud_success@3x.png"
//         };
//         File podFolder = createEmptyFiles(this.podsDir, "AXPracticalHUD", simulatedFiles);
//         File[] expectedFiles = new File[]{
//             new File(podFolder, "AXPracticalHUD/AXPracticalHUD/AXPracticalHUD.bundle/ax_hud_error@2x.png"),
//             new File(podFolder, "AXPracticalHUD/AXPracticalHUD/AXPracticalHUD.bundle/ax_hud_error@3x.png"),
//             new File(podFolder, "AXPracticalHUD/AXPracticalHUD/AXPracticalHUD.bundle/ax_hud_success@2x.png"),
//             new File(podFolder, "AXPracticalHUD/AXPracticalHUD/AXPracticalHUD.bundle/ax_hud_success@3x.png")
//         };
//         PodSpec podSpec = PodSpecParser.createPodSpec(args);
//         ResolvedPods resolvedPods = new ResolvedPods(this.podsDir, this.frameworksDir, List.of(podSpec), new File(this.buildDir, "Podfile.lock"), "11.0");

//         List<File> result = resolvedPods.getAllPodResources();
//         assertArrayEquals(expectedFiles, result.toArray());
//     }

//     @Test
//     @EnabledOnOs({ OS.MAC })
//     public void testResourceBundleParsing() throws IOException, ExtenderException {
//         String jsonSpec = Files.readString(Path.of("test-data/pod_specs/UnityAds.json"));
//         PodSpecParser.CreatePodSpecArgs args = new PodSpecParser.CreatePodSpecArgs.Builder()
//             // .setBuildDir(this.buildDir)
//             .setPodsDir(this.podsDir)
//             .setSpecJson(jsonSpec)
//             .setJobContext(this.jobContext)
//             .setSelectedPlatform(PodSpecParser.Platform.IPHONEOS)
//             // .setConfiguration("Debug")
//             .setConfigParser(new EmptyConfigParser())
//             .build();

//         String[] simulatedFiles = new String[]{
//             "UnityAds.xcframework/ios-arm64/UnityAds.framework/PrivacyInfo.xcprivacy",
//             "UnityAds.xcframework/ios-arm64/UnityAds.framework/omid-session-client-v1.js",
//             "UnityAds.xcframework/ios-arm64/UnityAds.framework/omsdk-v1.js",
//             "UnityAds.xcframework/ios-arm64/UnityAds.framework/Info.plist"
//         };
//         File podFolder = createEmptyFiles(this.podsDir, "UnityAds", simulatedFiles);

//         PodSpec unityAdsSpec = PodSpecParser.createPodSpec(args);
//         ResolvedPods resolvedPods = new ResolvedPods(this.podsDir, this.frameworksDir, List.of(unityAdsSpec), new File(this.buildDir, "Podfile.lock"), "11.0");

//         File targetDir = new File(this.workingDir, "result");
//         List<File> result = resolvedPods.createResourceBundles(targetDir, "arm64-ios");
//         assertEquals(result.size(), 1);
//         assertEquals("UnityAdsResources.bundle", result.get(0).getName());
//         List<String> expectedFiles = List.of(
//             "PrivacyInfo.xcprivacy",
//             "omid-session-client-v1.js",
//             "omsdk-v1.js",
//             "Info.plist"
//         );

//         File bundleDir = new File(targetDir, "UnityAdsResources.bundle");
//         assertTrue(bundleDir.exists());
//         List<Path> resultContent = Files.list(bundleDir.toPath()).toList();
//         assertEquals(expectedFiles.size(), resultContent.size());
//         for (Path p : resultContent) {
//             assertTrue(expectedFiles.contains(p.getFileName().toString()));
//         }
//     }

//     @Test
//     public void testValueSubstitution() throws IOException, ExtenderException {
//         String jsonSpec = Files.readString(Path.of("test-data/pod_specs/Sentry.json"));
//         XCConfigParser parser = new XCConfigParser(this.buildDir, this.podsDir, PodSpecParser.Platform.IPHONEOS.toString().toLowerCase(), "Debug", "arm64");
//         File sentryTargetFolder = Path.of(this.podsDir.toString(), "Target Support Files", "Sentry").toFile();
//         sentryTargetFolder.mkdirs();
//         Files.copy(Path.of("test-data/xcconfigs/Sentry.xcconfig"), Path.of(sentryTargetFolder.toString(), "Sentry.debug.xcconfig"), StandardCopyOption.REPLACE_EXISTING);

//         PodSpecParser.CreatePodSpecArgs args = new PodSpecParser.CreatePodSpecArgs.Builder()
//             // .setBuildDir(this.buildDir)
//             .setPodsDir(this.podsDir)
//             .setSpecJson(jsonSpec)
//             .setJobContext(this.jobContext)
//             .setSelectedPlatform(PodSpecParser.Platform.IPHONEOS)
//             // .setConfiguration("Debug")
//             .setConfigParser(parser)
//             .build();
//         PodSpec podSpec = PodSpecParser.createPodSpec(args);
//         assertTrue(podSpec.flags.c.contains("-DAPPLICATION_EXTENSION_API_ONLY_YES"));
//         assertTrue(podSpec.flags.objc.contains("-DAPPLICATION_EXTENSION_API_ONLY_YES"));
//     }

//     @Test
//     public void testCompilationFlags() throws IOException, ExtenderException {
//         String jsonSpec = Files.readString(Path.of("test-data/pod_specs/Sentry.json"));
//         XCConfigParser parser = new XCConfigParser(this.buildDir, this.podsDir, PodSpecParser.Platform.IPHONEOS.toString().toLowerCase(), "Debug", "arm64");
//         File sentryTargetFolder = Path.of(this.podsDir.toString(), "Target Support Files", "Sentry").toFile();
//         sentryTargetFolder.mkdirs();
//         Files.copy(Path.of("test-data/xcconfigs/Sentry.xcconfig"), Path.of(sentryTargetFolder.toString(), "Sentry.debug.xcconfig"), StandardCopyOption.REPLACE_EXISTING);

//         PodSpecParser.CreatePodSpecArgs args = new PodSpecParser.CreatePodSpecArgs.Builder()
//             // .setBuildDir(this.buildDir)
//             .setPodsDir(this.podsDir)
//             .setSpecJson(jsonSpec)
//             .setJobContext(this.jobContext)
//             .setSelectedPlatform(PodSpecParser.Platform.IPHONEOS)
//             // .setConfiguration("Debug")
//             .setConfigParser(parser)
//             .build();
//         PodSpec podSpec = PodSpecParser.createPodSpec(args);
//         // check handle APPLICATION_EXTENSION_API_ONLY flag
//         assertTrue(podSpec.flags.c.contains("-fapplication-extension"));
//         assertTrue(podSpec.flags.objc.contains("-fapplication-extension"));
//         assertTrue(podSpec.flags.swift.contains("-application-extension"));
//         // check SWIFT_INCLUDE_PATHS
//         assertTrue(podSpec.flags.swift.contains(String.format("-I%s/Sentry/Sources/Sentry/include", this.podsDir.toString())));
//     }
// }
