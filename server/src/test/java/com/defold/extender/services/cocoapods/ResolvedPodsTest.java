package com.defold.extender.services.cocoapods;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.naming.InvalidNameException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import com.defold.extender.ExtenderException;
import com.defold.extender.TestUtils;

public class ResolvedPodsTest {
    File emptyPodfile;
    File regularPodfile;
    File wrongPodfile;
    File withCommentPodfile;

    private Map<String, Object> jobContext;
    private File workingDir;
    private File buildDir;
    private File podsDir;
    private File frameworksDir;

    private CocoaPodsServiceBuildState cocoapodsState;
    CreateBuildSpecArgs args;

    class EmptyConfigParser implements IConfigParser {
        @Override
        public Map<String, String> parse(String moduleName, String podName, File xcconfig) throws IOException {
            return Map.of();
        }

    }

    private static File createEmptyFiles(File podsDir, String podName, String[] filesToCreate) throws IOException {
        File resultFolder = new File(podsDir, podName);
        for (String f : filesToCreate) {
            File emptyF = new File(resultFolder, f);
            emptyF.getParentFile().mkdirs();
            emptyF.createNewFile();
        }
        return resultFolder;
    }

    @BeforeEach
    public void setUp(TestInfo testInfo) throws IOException, InvalidNameException {
        this.emptyPodfile = new File("test-data/podfiles/empty.Podfile");
        this.regularPodfile = new File("test-data/podfiles/regular.Podfile");
        this.wrongPodfile = new File("test-data/podfiles/wrong.Podfile");
        this.withCommentPodfile = new File("test-data/podfiles/with_comments.Podfile");

        this.jobContext = new HashMap<>();
        this.jobContext.putAll(TestUtils.envFileToMap(new File("envs/.env")));
        this.jobContext.putAll(TestUtils.envFileToMap(new File("envs/macos.env")));

        if (testInfo.getDisplayName().contains(" ")) {
            // display name is used as folder prefix so if it contains spaces - ask to remove spaces ))
            throw new InvalidNameException("Test's display name shouldn't contain spaces");
        }
        this.workingDir = Files.createTempDirectory(testInfo.getDisplayName()).toFile();
        this.buildDir = new File(this.workingDir, "build");
        this.buildDir.mkdir();
        this.podsDir = new File(this.workingDir, "pods");
        this.podsDir.mkdir();
        this.frameworksDir = Path.of(this.buildDir.toString(), "Debugiphoneos", "XCFrameworkIntermediates").toFile();
        this.frameworksDir.mkdirs();
        this.workingDir.deleteOnExit();

        this.cocoapodsState = new CocoaPodsServiceBuildState();
        this.cocoapodsState.podsDir = this.podsDir;
        this.cocoapodsState.selectedPlatform = PodUtils.Platform.IPHONEOS;
        this.cocoapodsState.workingDir = this.workingDir;
        this.cocoapodsState.unpackedFrameworksDir = this.frameworksDir;
        this.args = new CreateBuildSpecArgs.Builder()
            .setJobContext(jobContext)
            .setCocoapodsBuildState(this.cocoapodsState)
            .setConfigParser(new EmptyConfigParser())
            .build();
        this.args.buildDir = this.buildDir;
        this.args.configuration = "Debug";

    }

    @Test
    @EnabledOnOs({ OS.MAC })
    public void testResourceBundleParsing() throws IOException, ExtenderException {
        String jsonSpec = Files.readString(Path.of("test-data/pod_specs/UnityAds.json"));

        String[] simulatedFiles = new String[]{
            "UnityAds.xcframework/ios-arm64/UnityAds.framework/PrivacyInfo.xcprivacy",
            "UnityAds.xcframework/ios-arm64/UnityAds.framework/omid-session-client-v1.js",
            "UnityAds.xcframework/ios-arm64/UnityAds.framework/omsdk-v1.js",
            "UnityAds.xcframework/ios-arm64/UnityAds.framework/Info.plist"
        };
        createEmptyFiles(this.podsDir, "UnityAds", simulatedFiles);

        PodSpec unityAdsSpec = PodSpecParser.createPodSpec(PodSpecParser.parseJson(jsonSpec), PodUtils.Platform.IPHONEOS, null);
        PodBuildSpec buildSpec = new PodBuildSpec(this.args, unityAdsSpec);
        ResolvedPods resolvedPods = new ResolvedPods(this.cocoapodsState, List.of(buildSpec), new File(this.buildDir, "Podfile.lock"), new MainPodfile());

        File targetDir = new File(this.workingDir, "result");
        List<File> result = resolvedPods.createResourceBundles(targetDir, "arm64-ios");
        assertEquals(result.size(), 1);
        assertEquals("UnityAdsResources.bundle", result.get(0).getName());
        List<String> expectedFiles = List.of(
            "PrivacyInfo.xcprivacy",
            "omid-session-client-v1.js",
            "omsdk-v1.js",
            "Info.plist"
        );

        File bundleDir = new File(targetDir, "UnityAdsResources.bundle");
        assertTrue(bundleDir.exists());
        List<Path> resultContent = Files.list(bundleDir.toPath()).toList();
        assertEquals(expectedFiles.size(), resultContent.size());
        for (Path p : resultContent) {
            assertTrue(expectedFiles.contains(p.getFileName().toString()));
        }
    }

    @Test
    public void testPodResourceFromBundle() throws IOException, ExtenderException {
        String jsonSpec = Files.readString(Path.of("test-data/pod_specs/AXPracticalHUD.json"));
        String[] simulatedFiles = new String[]{
            "AXPracticalHUD/AXPracticalHUD/AXPracticalHUD.bundle/ax_hud_error@2x.png",
            "AXPracticalHUD/AXPracticalHUD/AXPracticalHUD.bundle/ax_hud_error@3x.png",
            "AXPracticalHUD/AXPracticalHUD/AXPracticalHUD.bundle/ax_hud_success@2x.png",
            "AXPracticalHUD/AXPracticalHUD/AXPracticalHUD.bundle/ax_hud_success@3x.png"
        };
        File podFolder = createEmptyFiles(this.podsDir, "AXPracticalHUD", simulatedFiles);
        File[] expectedFiles = new File[]{
            new File(podFolder, "AXPracticalHUD/AXPracticalHUD/AXPracticalHUD.bundle/ax_hud_error@2x.png"),
            new File(podFolder, "AXPracticalHUD/AXPracticalHUD/AXPracticalHUD.bundle/ax_hud_error@3x.png"),
            new File(podFolder, "AXPracticalHUD/AXPracticalHUD/AXPracticalHUD.bundle/ax_hud_success@2x.png"),
            new File(podFolder, "AXPracticalHUD/AXPracticalHUD/AXPracticalHUD.bundle/ax_hud_success@3x.png")
        };
        PodSpec podSpec = PodSpecParser.createPodSpec(PodSpecParser.parseJson(jsonSpec), PodUtils.Platform.IPHONEOS, null);
        PodBuildSpec buildSpec = new PodBuildSpec(this.args, podSpec);
        ResolvedPods resolvedPods = new ResolvedPods(this.cocoapodsState, List.of(buildSpec), new File(this.buildDir, "Podfile.lock"), new MainPodfile());

        List<File> result = resolvedPods.getAllPodResources();
        assertArrayEquals(expectedFiles, result.toArray());
    }

    @Test
    public void testSpecBraceExpanderSourceFiles() throws ExtenderException, IOException {
        String jsonSpec = Files.readString(Path.of("test-data/pod_specs/PubNub.json"));
        // create empty files to simulate sources
        String[] simulatedFiles = new String[]{
            "PubNub/Core/core1/core11.cpp",
            "PubNub/Core/core1/core12.cpp",
            "PubNub/Core/core1/core1.h",
            "PubNub/Core/core2/core21.cpp",
            "PubNub/Core/core2/core22.cpp",
            "PubNub/Core/core2/core2.h",

            "PubNub/Data/data1/data11.cpp",
            "PubNub/Data/data1/data12.cpp",
            "PubNub/Data/data1/data1.h",
            "PubNub/Data/data2/data21.cpp",
            "PubNub/Data/data2/data22.cpp",
            "PubNub/Data/data2/data2.h",

            "PubNub/Misc/misc1/misc11.cpp",
            "PubNub/Misc/misc1/misc12.cpp",
            "PubNub/Misc/misc1/misc1.h",
            "PubNub/Misc/misc2/misc21.cpp",
            "PubNub/Misc/misc2/misc22.cpp",
            "PubNub/Misc/misc2/misc2.h",

            "PubNub/Network/network1/network11.cpp",
            "PubNub/Network/network1/network12.cpp",
            "PubNub/Network/network1/network1.h",
            "PubNub/Network/network2/network21.cpp",
            "PubNub/Network/network2/network22.cpp",
            "PubNub/Network/network2/network2.h"
        };
        File pubNubFolder = createEmptyFiles(this.podsDir, "PubNub", simulatedFiles);

        PodSpec podSpec = PodSpecParser.createPodSpec(PodSpecParser.parseJson(jsonSpec), PodUtils.Platform.IPHONEOS, null);
        File[] expectedValues = new File[]{
            new File(pubNubFolder, "PubNub/Core/core1/core11.cpp"),
            new File(pubNubFolder, "PubNub/Core/core1/core12.cpp"),
            new File(pubNubFolder, "PubNub/Core/core2/core21.cpp"),
            new File(pubNubFolder, "PubNub/Core/core2/core22.cpp"),

            new File(pubNubFolder, "PubNub/Data/data1/data11.cpp"),
            new File(pubNubFolder, "PubNub/Data/data1/data12.cpp"),
            new File(pubNubFolder, "PubNub/Data/data2/data21.cpp"),
            new File(pubNubFolder, "PubNub/Data/data2/data22.cpp"),

            new File(pubNubFolder, "PubNub/Misc/misc1/misc11.cpp"),
            new File(pubNubFolder, "PubNub/Misc/misc1/misc12.cpp"),
            new File(pubNubFolder, "PubNub/Misc/misc2/misc21.cpp"),
            new File(pubNubFolder, "PubNub/Misc/misc2/misc22.cpp"),

            new File(pubNubFolder, "PubNub/Network/network1/network11.cpp"),
            new File(pubNubFolder, "PubNub/Network/network1/network12.cpp"),
            new File(pubNubFolder, "PubNub/Network/network2/network21.cpp"),
            new File(pubNubFolder, "PubNub/Network/network2/network22.cpp")
        };
        PodBuildSpec buildSpec = new PodBuildSpec(this.args, podSpec);
        buildSpec.addSubSpec(podSpec.subspecs.get(0));
        assertArrayEquals(expectedValues, buildSpec.sourceFiles.toArray());
    }
}
