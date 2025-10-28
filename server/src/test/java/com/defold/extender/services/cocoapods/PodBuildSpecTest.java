package com.defold.extender.services.cocoapods;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

import javax.naming.InvalidNameException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import com.defold.extender.ExtenderException;
import com.defold.extender.TestUtils;

public class PodBuildSpecTest {

    private Map<String, Object> jobContext;
    private File workingDir;
    private File buildDir;
    private File podsDir;

    @BeforeEach
    public void setUp(TestInfo testInfo) throws IOException, InvalidNameException {
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
        this.workingDir.deleteOnExit();
    }

    @Test
    public void testValueSubstitution() throws IOException, ExtenderException {
        String jsonSpec = Files.readString(Path.of("test-data/pod_specs/Sentry.json"));
        XCConfigParser parser = new XCConfigParser(this.buildDir, this.podsDir, PodUtils.Platform.IPHONEOS, "Debug", "arm64");
        File sentryTargetFolder = Path.of(this.podsDir.toString(), "Target Support Files", "Sentry").toFile();
        sentryTargetFolder.mkdirs();
        Files.copy(Path.of("test-data/xcconfigs/Sentry.xcconfig"), Path.of(sentryTargetFolder.toString(), "Sentry.debug.xcconfig"), StandardCopyOption.REPLACE_EXISTING);

        PodSpec podSpec = PodSpecParser.createPodSpec(PodSpecParser.parseJson(jsonSpec), PodUtils.Platform.IPHONEOS, null);

        CocoaPodsServiceBuildState cocoapodsState = new CocoaPodsServiceBuildState();
        cocoapodsState.workingDir = this.workingDir;
        cocoapodsState.podsDir = this.podsDir;
        cocoapodsState.selectedPlatform = PodUtils.Platform.IPHONEOS;

        CreateBuildSpecArgs args = new CreateBuildSpecArgs.Builder()
            .setConfigParser(parser)
            .setCocoapodsBuildState(cocoapodsState)
            .setJobContext(this.jobContext)
            .build();
        args.buildDir = this.buildDir;
        args.configuration = "Debug";
        PodBuildSpec buildSpec = new PodBuildSpec(args, podSpec);
        assertTrue(buildSpec.flags.c.contains("-DAPPLICATION_EXTENSION_API_ONLY_YES"));
        assertTrue(buildSpec.flags.objc.contains("-DAPPLICATION_EXTENSION_API_ONLY_YES"));
    }

    @Test
    public void testCompilationFlags() throws IOException, ExtenderException {
        String jsonSpec = Files.readString(Path.of("test-data/pod_specs/Sentry.json"));
        XCConfigParser parser = new XCConfigParser(this.buildDir, this.podsDir, PodUtils.Platform.IPHONEOS, "Debug", "arm64");
        File sentryTargetFolder = Path.of(this.podsDir.toString(), "Target Support Files", "Sentry").toFile();
        sentryTargetFolder.mkdirs();
        Files.copy(Path.of("test-data/xcconfigs/Sentry.xcconfig"), Path.of(sentryTargetFolder.toString(), "Sentry.debug.xcconfig"), StandardCopyOption.REPLACE_EXISTING);

        PodSpec podSpec = PodSpecParser.createPodSpec(PodSpecParser.parseJson(jsonSpec), PodUtils.Platform.IPHONEOS, null);
        CocoaPodsServiceBuildState cocoapodsState = new CocoaPodsServiceBuildState();
        cocoapodsState.workingDir = this.workingDir;
        cocoapodsState.podsDir = this.podsDir;
        cocoapodsState.selectedPlatform = PodUtils.Platform.IPHONEOS;

        CreateBuildSpecArgs args = new CreateBuildSpecArgs.Builder()
            .setConfigParser(parser)
            .setCocoapodsBuildState(cocoapodsState)
            .setJobContext(this.jobContext)
            .build();
        args.buildDir = this.buildDir;
        args.configuration = "Debug";

        PodBuildSpec buildSpec = new PodBuildSpec(args, podSpec);
        // check handle APPLICATION_EXTENSION_API_ONLY flag
        assertTrue(buildSpec.flags.c.contains("-fapplication-extension"));
        assertTrue(buildSpec.flags.objc.contains("-fapplication-extension"));
        assertTrue(buildSpec.flags.swift.contains("-application-extension"));
        // check SWIFT_INCLUDE_PATHS
        assertTrue(buildSpec.flags.swift.contains(String.format("-I%s/Sentry/Sources/Sentry/include", this.podsDir.toString())));
    }

    @Test
    public void testNestedPodSpecName() throws IOException, ExtenderException {
        String jsonSpec = Files.readString(Path.of("test-data/pod_specs/YandexMobileMetrica.json"));
        XCConfigParser parser = new XCConfigParser(this.buildDir, this.podsDir, PodUtils.Platform.IPHONEOS, "Debug", "arm64");
        File sentryTargetFolder = Path.of(this.podsDir.toString(), "Target Support Files", "YandexMobileMetrica").toFile();
        sentryTargetFolder.mkdirs();
        Files.copy(Path.of("test-data/xcconfigs/YandexMobileMetrica.xcconfig"), Path.of(sentryTargetFolder.toString(), "YandexMobileMetrica.debug.xcconfig"), StandardCopyOption.REPLACE_EXISTING);

        PodSpec podSpec = PodSpecParser.createPodSpec(PodSpecParser.parseJson(jsonSpec), PodUtils.Platform.IPHONEOS, null);
        CocoaPodsServiceBuildState cocoapodsState = new CocoaPodsServiceBuildState();
        cocoapodsState.workingDir = this.workingDir;
        cocoapodsState.podsDir = this.podsDir;
        cocoapodsState.selectedPlatform = PodUtils.Platform.IPHONEOS;

        CreateBuildSpecArgs args = new CreateBuildSpecArgs.Builder()
            .setConfigParser(parser)
            .setCocoapodsBuildState(cocoapodsState)
            .setJobContext(this.jobContext)
            .build();
        args.buildDir = this.buildDir;
        args.configuration = "Debug";

        PodBuildSpec buildSpec = new PodBuildSpec(args, podSpec);
        assertEquals(buildSpec.name, "YandexMobileMetrica");
    }
}
