package com.defold.extender.services.cocoapods;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.naming.InvalidNameException;

import org.junit.jupiter.api.AfterEach;
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
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (this.workingDir != null && this.workingDir.exists()) {
            deleteRecursively(this.workingDir.toPath());
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        Files.walk(path)
            .sorted((a, b) -> b.compareTo(a)) // reverse order to delete children first
            .forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    // ignore cleanup errors
                }
            });
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
        File metricaTargetFolder = Path.of(this.podsDir.toString(), "Target Support Files", "YandexMobileMetrica").toFile();
        metricaTargetFolder.mkdirs();
        Files.copy(Path.of("test-data/xcconfigs/YandexMobileMetrica.xcconfig"), Path.of(metricaTargetFolder.toString(), "YandexMobileMetrica.debug.xcconfig"), StandardCopyOption.REPLACE_EXISTING);

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

    @Test
    public void testPublicHeaderPatternMatch() throws IOException, ExtenderException {
        String jsonSpec = Files.readString(Path.of("test-data/pod_specs/Sentry.json"));
        XCConfigParser parser = new XCConfigParser(this.buildDir, this.podsDir, PodUtils.Platform.IPHONEOS, "Debug", "arm64");
        File sentryTargetFolder = Path.of(this.podsDir.toString(), "Target Support Files", "Sentry").toFile();
        sentryTargetFolder.mkdirs();
        Files.copy(Path.of("test-data/xcconfigs/Sentry.xcconfig"), Path.of(sentryTargetFolder.toString(), "Sentry.debug.xcconfig"), StandardCopyOption.REPLACE_EXISTING);

        // Files matching public_header_files pattern: Sources/Sentry/Public/*.h
        List<String> publicHeaderFiles = List.of(
            "Sources/Sentry/Public/SentrySDK.h",
            "Sources/Sentry/Public/SentryClient.h",
            "Sources/Sentry/Public/SentryOptions.h"
        );

        // Files NOT matching the pattern (should not be included in publicHeaders)
        List<String> otherFiles = List.of(
            "Sources/Sentry/Private/SentryPrivate.h",
            "Sources/Sentry/SentryInternal.h",
            "Sources/Sentry/Public/SentrySDK.m",
            "Sources/SentryCrash/SentryCrash.h"
        );

        // Create all files
        Path sentrySourcesDir = Path.of(this.podsDir.toString(), "Sentry");
        for (String filePath : publicHeaderFiles) {
            Path fullPath = sentrySourcesDir.resolve(filePath);
            Files.createDirectories(fullPath.getParent());
            Files.createFile(fullPath);
        }
        for (String filePath : otherFiles) {
            Path fullPath = sentrySourcesDir.resolve(filePath);
            Files.createDirectories(fullPath.getParent());
            Files.createFile(fullPath);
        }

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
        buildSpec.addSubSpec(podSpec.getSubspec("Core"));

        // Verify only the public_header_files pattern matches are included
        for (String headerFile : publicHeaderFiles) {
            Path expectedPath = sentrySourcesDir.resolve(headerFile);
            assertTrue(
                buildSpec.publicHeaders.stream().anyMatch(h -> h.getAbsolutePath().equals(expectedPath.toString())),
                "publicHeaders should contain: " + expectedPath
            );
        }

        assertEquals(publicHeaderFiles.size(), buildSpec.publicHeaders.size(),
            "publicHeaders should contain exactly " + publicHeaderFiles.size() + " files");
    }

    @Test
    public void testNoPublicHeaderPattern() throws IOException, ExtenderException {
        String jsonSpec = Files.readString(Path.of("test-data/pod_specs/AppAuth.json"));
        XCConfigParser parser = new XCConfigParser(this.buildDir, this.podsDir, PodUtils.Platform.IPHONEOS, "Debug", "arm64");
        File appAuthTargetFolder = Path.of(this.podsDir.toString(), "Target Support Files", "AppAuth").toFile();
        appAuthTargetFolder.mkdirs();
        Files.copy(Path.of("test-data/xcconfigs/AppAuth.xcconfig"), Path.of(appAuthTargetFolder.toString(), "AppAuth.debug.xcconfig"), StandardCopyOption.REPLACE_EXISTING);

        List<String> tmpFiles = List.of(
            "AppAuth.h",
            "AppAuth/iOS/OIDAuthState+IOS.h",
            "AppAuth/iOS/OIDAuthState+IOS.m",
            "AppAuth/iOS/OIDAuthorizationService+IOS.h",
            "AppAuth/iOS/OIDAuthorizationService+IOS.m",
            "AppAuth/iOS/OIDExternalUserAgentCatalyst.h",
            "AppAuth/iOS/OIDExternalUserAgentCatalyst.m",
            "AppAuth/iOS/OIDExternalUserAgentIOS.h",
            "AppAuth/iOS/OIDExternalUserAgentIOS.m",
            "AppAuth/iOS/OIDExternalUserAgentIOSCustomBrowser.h",
            "AppAuth/iOS/OIDExternalUserAgentIOSCustomBrowser.m",
            "AppAuthCore.h",
            "AppAuthCore/OIDAuthState.h",
            "AppAuthCore/OIDAuthState.m",
            "AppAuthCore/OIDAuthStateChangeDelegate.h",
            "AppAuthCore/OIDAuthStateErrorDelegate.h",
            "AppAuthCore/OIDAuthorizationRequest.h",
            "AppAuthCore/OIDAuthorizationRequest.m",
            "AppAuthCore/OIDAuthorizationResponse.h",
            "AppAuthCore/OIDAuthorizationResponse.m",
            "AppAuthCore/OIDAuthorizationService.h",
            "AppAuthCore/OIDAuthorizationService.m",
            "AppAuthCore/OIDClientMetadataParameters.h",
            "AppAuthCore/OIDClientMetadataParameters.m",
            "AppAuthCore/OIDDefines.h",
            "AppAuthCore/OIDEndSessionRequest.h",
            "AppAuthCore/OIDEndSessionRequest.m",
            "AppAuthCore/OIDEndSessionResponse.h",
            "AppAuthCore/OIDEndSessionResponse.m",
            "AppAuthCore/OIDError.h",
            "AppAuthCore/OIDError.m",
            "AppAuthCore/OIDErrorUtilities.h",
            "AppAuthCore/OIDErrorUtilities.m",
            "AppAuthCore/OIDExternalUserAgent.h",
            "AppAuthCore/OIDExternalUserAgentRequest.h",
            "AppAuthCore/OIDExternalUserAgentSession.h",
            "AppAuthCore/OIDFieldMapping.h",
            "AppAuthCore/OIDFieldMapping.m",
            "AppAuthCore/OIDGrantTypes.h",
            "AppAuthCore/OIDGrantTypes.m",
            "AppAuthCore/OIDIDToken.h",
            "AppAuthCore/OIDIDToken.m",
            "AppAuthCore/OIDRegistrationRequest.h",
            "AppAuthCore/OIDRegistrationRequest.m",
            "AppAuthCore/OIDRegistrationResponse.h",
            "AppAuthCore/OIDRegistrationResponse.m",
            "AppAuthCore/OIDResponseTypes.h",
            "AppAuthCore/OIDResponseTypes.m",
            "AppAuthCore/OIDScopeUtilities.h",
            "AppAuthCore/OIDScopeUtilities.m",
            "AppAuthCore/OIDScopes.h",
            "AppAuthCore/OIDScopes.m",
            "AppAuthCore/OIDServiceConfiguration.h",
            "AppAuthCore/OIDServiceConfiguration.m",
            "AppAuthCore/OIDServiceDiscovery.h",
            "AppAuthCore/OIDServiceDiscovery.m",
            "AppAuthCore/OIDTokenRequest.h",
            "AppAuthCore/OIDTokenRequest.m",
            "AppAuthCore/OIDTokenResponse.h",
            "AppAuthCore/OIDTokenResponse.m",
            "AppAuthCore/OIDTokenUtilities.h",
            "AppAuthCore/OIDTokenUtilities.m",
            "AppAuthCore/OIDURLQueryComponent.h",
            "AppAuthCore/OIDURLQueryComponent.m",
            "AppAuthCore/OIDURLSessionProvider.h",
            "AppAuthCore/OIDURLSessionProvider.m",
            "AppAuthCore/Resources/PrivacyInfo.xcprivacy"
        );

        // Create empty files at enumerated paths
        Path appAuthSourcesDir = Path.of(this.podsDir.toString(), "AppAuth", "Sources");
        for (String filePath : tmpFiles) {
            Path fullPath = appAuthSourcesDir.resolve(filePath);
            Files.createDirectories(fullPath.getParent());
            Files.createFile(fullPath);
        }


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
        buildSpec.addSubSpec(podSpec.getSubspec("Core"));
        buildSpec.addSubSpec(podSpec.getSubspec("ExternalUserAgent"));
        List<String> expectedHeaders = tmpFiles.stream()
            .filter(f -> f.endsWith(".h"))
            .toList();

        for (String headerFile : expectedHeaders) {
            Path expectedPath = appAuthSourcesDir.resolve(headerFile);
            assertTrue(
                buildSpec.publicHeaders.stream().anyMatch(h -> h.getAbsolutePath().equals(expectedPath.toString())),
                "publicHeaders should contain: " + expectedPath
            );
        }

        assertEquals(expectedHeaders.size(), buildSpec.publicHeaders.size(),
            "publicHeaders should contain exactly " + expectedHeaders.size() + " files");
    }
}
