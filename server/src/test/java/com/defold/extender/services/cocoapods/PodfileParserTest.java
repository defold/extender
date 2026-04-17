package com.defold.extender.services.cocoapods;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class PodfileParserTest {
    private File emptyPodfile;
    private File withCommentPodfile;
    private File regularPodfile;
    private File wrongPodfile;

    @BeforeEach
    public void setUp() {
        this.emptyPodfile = new File("test-data/podfiles/empty.Podfile");
        this.regularPodfile = new File("test-data/podfiles/regular.Podfile");
        this.wrongPodfile = new File("test-data/podfiles/wrong.Podfile");
        this.withCommentPodfile = new File("test-data/podfiles/with_comments.Podfile");
    }

    @Test
    public void testPodfileParserRegularSyntax() throws IOException, PodfileParsingException {
        PodfileParser.ParseResult res = CocoaPodsService.parsePodfiles(List.of(this.regularPodfile), "ios", "11.0");
        List<String> expected = List.of("IronSourcePangleAdapter", "IronSourceSmaatoAdapter", "IronSourceSuperAwesomeAdapter", "IronSourceTencentAdapter", "IronSourceUnityAdsAdapter", "IronSourceYandexAdapter");
        assertTrue(res.podNames.containsAll(expected) && expected.containsAll(res.podNames));
    }

    @Test
    public void testPodfileParserComments() throws IOException, PodfileParsingException {
        PodfileParser.ParseResult res = CocoaPodsService.parsePodfiles(List.of(this.withCommentPodfile), "ios", "10.3");
        assertEquals(res.minVersion, "12.0");
        List<String> expected = List.of("OneTrust-CMP-XCFramework");
        assertTrue(expected.containsAll(res.podNames) && res.podNames.containsAll(expected));
    }

    @Test
    public void testPodfileParserWrongSyntax() throws IOException, PodfileParsingException {
        PodfileParser.ParseResult res = CocoaPodsService.parsePodfiles(List.of(this.wrongPodfile), "ios", "11.0");
        List<String> expected = List.of("IronSourceSuperAwesomeAdapter");
        assertTrue(expected.containsAll(res.podNames) && res.podNames.containsAll(expected));
    }

    @Test
    public void testPodfileParserEmptyFile() throws IOException, PodfileParsingException {
        PodfileParser.ParseResult res = CocoaPodsService.parsePodfiles(List.of(this.emptyPodfile), "ios", "11.0");
        assertTrue(res.podNames.isEmpty());
    }

    @Test
    public void testPodfileParserAll() throws IOException, PodfileParsingException {
        PodfileParser.ParseResult result = CocoaPodsService.parsePodfiles(List.of(this.emptyPodfile, this.wrongPodfile, this.regularPodfile, this.withCommentPodfile), "ios", "9.1");
        List<String> expected = List.of("IronSourceSuperAwesomeAdapter", "IronSourcePangleAdapter", "IronSourceSmaatoAdapter", "IronSourceSuperAwesomeAdapter", "IronSourceTencentAdapter", "IronSourceUnityAdsAdapter", "IronSourceYandexAdapter", "OneTrust-CMP-XCFramework");
        assertTrue(expected.containsAll(result.podNames) && result.podNames.containsAll(expected));
    }

    @Test
    public void testCompareVersions() throws PodfileParsingException {
        assertThrows(NullPointerException.class, () -> { PodfileParser.compareVersions(null, null); });
        assertEquals(0, PodfileParser.compareVersions("12.0", "12.0"));
        assertEquals(0, PodfileParser.compareVersions("12.0", "12.0."));
        assertTrue(PodfileParser.compareVersions("13.0.1", "13.0") > 0);
        assertTrue(PodfileParser.compareVersions("9.3", "12.6.1") < 0);
        assertThrows(PodfileParsingException.class, () -> { PodfileParser.compareVersions("9.3", "unknown"); });
        assertThrows(NullPointerException.class, () -> { PodfileParser.compareVersions("9.3", null); });
        // Semver pre-release and build metadata are ignored during comparison
        assertEquals(0, PodfileParser.compareVersions("1.0.0-beta", "1.0.0"));
        assertEquals(0, PodfileParser.compareVersions("2.0.0-rc.1", "2.0.0"));
        assertEquals(0, PodfileParser.compareVersions("1.2.3+build.7", "1.2.3"));
        assertTrue(PodfileParser.compareVersions("1.0.1-beta", "1.0.0") > 0);
    }

    private static Stream<Arguments> mergeVersionsData() {
        return Stream.of(
            Arguments.of("tvos", "3.0"),
            Arguments.of("ios", "11.0"),
            Arguments.of("macos", "10.15")
        );
    }

    @Test
    public void testParseResultMergeEmpty() {
        PodfileParser.ParseResult emptyResult = new PodfileParser.ParseResult();

        PodfileParser.ParseResult test = new PodfileParser.ParseResult();
        assertDoesNotThrow(() -> { test.mergeWith(emptyResult); });
        assertTrue(test.minVersion == null);
        assertTrue(test.platform == null);
        assertTrue(test.useFrameworks);
        assertTrue(test.podDefinitions.isEmpty());
        assertTrue(test.podNames.isEmpty());
    }

    @ParameterizedTest
    @MethodSource("mergeVersionsData")
    public void testParseResultMergeNonEmpty(String platform, String version) {
        PodfileParser.ParseResult nonEmpty = new PodfileParser.ParseResult(platform, version);

        PodfileParser.ParseResult test = new PodfileParser.ParseResult();
        assertDoesNotThrow(() -> { test.mergeWith(nonEmpty); });
        assertEquals(version, test.minVersion);
        assertEquals(platform, test.platform);
        assertTrue(test.useFrameworks);
        assertTrue(test.podDefinitions.isEmpty());
        assertTrue(test.podNames.isEmpty());
    }

    @Test
    public void testParseResultMismatchPlatform() {
        PodfileParser.ParseResult baseResult = new PodfileParser.ParseResult("ios", "13.0");

        PodfileParser.ParseResult unsupportedPlatform = new PodfileParser.ParseResult("tvos", "3.0");
        assertThrows(PodfileParsingException.class, () -> { baseResult.mergeWith(unsupportedPlatform); });
        assertEquals("13.0", baseResult.minVersion);
        assertEquals("ios", baseResult.platform);
        assertTrue(baseResult.useFrameworks);
        assertTrue(baseResult.podDefinitions.isEmpty());
        assertTrue(baseResult.podNames.isEmpty());
    }

    @Test
    public void testParseResultMergeDiffVersions() {
        PodfileParser.ParseResult lessVersion = new PodfileParser.ParseResult("macos", "10.21");
        PodfileParser.ParseResult greaterVersion = new PodfileParser.ParseResult("macos", "11.3");
        PodfileParser.ParseResult sameVersion = new PodfileParser.ParseResult("macos", "11.0");

        PodfileParser.ParseResult test1 = new PodfileParser.ParseResult("macos", "11.0");
        assertDoesNotThrow(() -> { test1.mergeWith(lessVersion); });
        assertEquals("11.0", test1.minVersion);

        PodfileParser.ParseResult test2 = new PodfileParser.ParseResult("macos", "11.0");
        assertDoesNotThrow(() -> { test2.mergeWith(greaterVersion); });
        assertEquals("11.3", test2.minVersion);

        PodfileParser.ParseResult test3 = new PodfileParser.ParseResult("macos", "11.0");
        assertDoesNotThrow(() -> { test3.mergeWith(sameVersion); });
        assertEquals("11.0", test3.minVersion);
    }

    @Test
    public void testParseResultUseFrameworkFlag() {
        PodfileParser.ParseResult falseFlag = new PodfileParser.ParseResult("ios", "11.0");
        falseFlag.useFrameworks = false;
        PodfileParser.ParseResult trueFlag = new PodfileParser.ParseResult("ios", "11.0");
        trueFlag.useFrameworks = true;

        PodfileParser.ParseResult baseFalseTest1 = new PodfileParser.ParseResult("ios", "11.0");
        baseFalseTest1.useFrameworks = false;
        assertDoesNotThrow(() -> { baseFalseTest1.mergeWith(falseFlag); });
        assertFalse(baseFalseTest1.useFrameworks);

        PodfileParser.ParseResult baseFalseTest2 = new PodfileParser.ParseResult("ios", "11.0");
        baseFalseTest2.useFrameworks = false;
        assertDoesNotThrow(() -> { baseFalseTest2.mergeWith(trueFlag); });
        assertTrue(baseFalseTest2.useFrameworks);

        PodfileParser.ParseResult baseTrueTest1 = new PodfileParser.ParseResult("ios", "11.0");
        baseTrueTest1.useFrameworks = true;
        assertDoesNotThrow(() -> { baseTrueTest1.mergeWith(falseFlag); });
        assertTrue(baseTrueTest1.useFrameworks);

        PodfileParser.ParseResult baseTrueTest2 = new PodfileParser.ParseResult("ios", "11.0");
        baseTrueTest2.useFrameworks = true;
        assertDoesNotThrow(() -> { baseTrueTest2.mergeWith(trueFlag); });
        assertTrue(baseTrueTest2.useFrameworks);
    }

    // ========== Injection Prevention Tests ==========

    @Test
    @Tag("security")
    public void testInjectionSystemCallIgnored() throws IOException, PodfileParsingException {
        // pod 'SomePod'; system('id > /tmp/pwned.txt') should NOT match the pod pattern
        File injectionFile = new File("test-data/podfiles/injection_system.Podfile");
        PodfileParser.ParseResult res = CocoaPodsService.parsePodfiles(List.of(injectionFile), "ios", "13.0");
        assertTrue(res.podNames.isEmpty(), "system() injection payload should be rejected");
        assertTrue(res.podDefinitions.isEmpty(), "system() injection payload should produce no pod definitions");
    }

    @Test
    @Tag("security")
    public void testInjectionReverseShellIgnored() throws IOException, PodfileParsingException {
        File injectionFile = new File("test-data/podfiles/injection_reverse_shell.Podfile");
        PodfileParser.ParseResult res = CocoaPodsService.parsePodfiles(List.of(injectionFile), "ios", "13.0");
        assertTrue(res.podNames.isEmpty(), "Reverse shell injection payload should be rejected");
        assertTrue(res.podDefinitions.isEmpty());
    }

    @Test
    @Tag("security")
    public void testInjectionBacktickIgnored() throws IOException, PodfileParsingException {
        File injectionFile = new File("test-data/podfiles/injection_backtick.Podfile");
        PodfileParser.ParseResult res = CocoaPodsService.parsePodfiles(List.of(injectionFile), "ios", "13.0");
        assertTrue(res.podNames.isEmpty(), "Backtick injection payload should be rejected");
        assertTrue(res.podDefinitions.isEmpty());
    }

    @Test
    @Tag("security")
    public void testInjectionInterpolationIgnored() throws IOException, PodfileParsingException {
        // pod 'SomePod', "#{system('whoami')}" uses double quotes, should not match
        File injectionFile = new File("test-data/podfiles/injection_interpolation.Podfile");
        PodfileParser.ParseResult res = CocoaPodsService.parsePodfiles(List.of(injectionFile), "ios", "13.0");
        assertTrue(res.podNames.isEmpty(), "Ruby interpolation injection payload should be rejected");
        assertTrue(res.podDefinitions.isEmpty());
    }

    @Test
    @Tag("security")
    public void testInjectionMultilineOnlyLegitPodsKept() throws IOException, PodfileParsingException {
        // File has: legitimate pod, injection pod, another legitimate pod
        // Only the legitimate pods should survive
        File injectionFile = new File("test-data/podfiles/injection_multiline.Podfile");
        PodfileParser.ParseResult res = CocoaPodsService.parsePodfiles(List.of(injectionFile), "ios", "13.0");
        List<String> expectedNames = List.of("LegitPod", "AnotherLegit");
        assertTrue(res.podNames.containsAll(expectedNames) && expectedNames.containsAll(res.podNames),
            "Only legitimate pods should be kept, injection line should be dropped");
        assertFalse(res.podNames.contains("EvilPod"), "EvilPod with injection payload should be rejected");
        // Verify definitions are sanitized (reconstructed)
        assertTrue(res.podDefinitions.contains("pod 'LegitPod', '1.0'"));
        assertTrue(res.podDefinitions.contains("pod 'AnotherLegit', '2.0'"));
        assertEquals(2, res.podDefinitions.size());
    }

    @Test
    @Tag("security")
    public void testSanitizePodDefinitionReconstructsCleanLine() {
        assertEquals("pod 'MyPod'", PodfileParser.sanitizePodDefinition("MyPod", null));
        assertEquals("pod 'MyPod', '1.0.3'", PodfileParser.sanitizePodDefinition("MyPod", "1.0.3"));
        assertEquals("pod 'MyPod', '~>2.0'", PodfileParser.sanitizePodDefinition("MyPod", "~>2.0"));
        assertEquals("pod 'Firebase/Analytics', '>=8.0'", PodfileParser.sanitizePodDefinition("Firebase/Analytics", ">=8.0"));
        assertEquals("pod 'MyPod', '1.0.0-beta'", PodfileParser.sanitizePodDefinition("MyPod", "1.0.0-beta"));
        assertEquals("pod 'MyPod', '2.0.0-rc.1'", PodfileParser.sanitizePodDefinition("MyPod", "2.0.0-rc.1"));
    }

    @Test
    @Tag("security")
    public void testPreReleaseVersionsAccepted() throws IOException, PodfileParsingException {
        // Versions with hyphens and alphabetic pre-release tags should be accepted
        File preReleasePodfile = new File("test-data/podfiles/prerelease_versions.Podfile");
        PodfileParser.ParseResult res = CocoaPodsService.parsePodfiles(List.of(preReleasePodfile), "ios", "13.0");
        List<String> expectedNames = List.of("SomePod", "AnotherPod", "ThirdPod");
        assertTrue(res.podNames.containsAll(expectedNames) && expectedNames.containsAll(res.podNames),
            "Pods with pre-release versions should be accepted");
        assertTrue(res.podDefinitions.contains("pod 'SomePod', '1.0.0-beta'"));
        assertTrue(res.podDefinitions.contains("pod 'AnotherPod', '~>2.0.0-rc.1'"));
        assertTrue(res.podDefinitions.contains("pod 'ThirdPod', '3.0.0-alpha+build.123'"));
    }

    @Test
    @Tag("security")
    public void testSanitizedDefinitionsFromRegularPodfile() throws IOException, PodfileParsingException {
        PodfileParser.ParseResult res = CocoaPodsService.parsePodfiles(List.of(this.regularPodfile), "ios", "11.0");
        // All definitions should be sanitized (reconstructed from parsed components)
        for (String def : res.podDefinitions) {
            // Every definition must start with "pod '" and contain no semicolons, backticks, or double quotes
            assertTrue(def.startsWith("pod '"), "Definition should start with pod quote: " + def);
            assertFalse(def.contains(";"), "Definition should not contain semicolons: " + def);
            assertFalse(def.contains("`"), "Definition should not contain backticks: " + def);
            assertFalse(def.contains("\""), "Definition should not contain double quotes: " + def);
            assertFalse(def.contains("system"), "Definition should not contain system calls: " + def);
        }
    }

    @Test
    @Tag("security")
    public void testCommentStrippingPreservesPod() throws IOException, PodfileParsingException {
        // with_comments.Podfile has: pod 'OneTrust-CMP-XCFramework', '202407.1.0.0' # some in one line
        PodfileParser.ParseResult res = CocoaPodsService.parsePodfiles(List.of(this.withCommentPodfile), "ios", "10.3");
        assertTrue(res.podDefinitions.contains("pod 'OneTrust-CMP-XCFramework', '202407.1.0.0'"),
            "Pod definition should be preserved without trailing comment");
    }

    // ========== End Injection Prevention Tests ==========

    @Test
    public void testParseResultPodDefinitions() {
        PodfileParser.ParseResult result1 = new PodfileParser.ParseResult("ios", "12.0");
        result1.podDefinitions.addAll(
            List.of(
                "pod 'testPod1', '1.0.3'",
                "pod 'testPod2', '~>0.0.4'",
                "pod 'testPod3/subspec1'"
            )
        );
        result1.podNames.addAll(List.of("testPod1", "testPod2", "testPod3"));
        
        PodfileParser.ParseResult result2 = new PodfileParser.ParseResult("ios", "12.0");
        result2.podDefinitions.addAll(
            List.of(
                "pod 'testPod5', '1.0.3'",
                "pod 'testPod3/subspec2', '~>0.0.4'",
                "pod 'testPod2'",
                "pod 'testPod3/subspec1'"
            )
        );
        result2.podNames.addAll(List.of("testPod5", "testPod3", "testPod2"));

        List<String> expectedDefinitions1 = List.of(
            "pod 'testPod1', '1.0.3'",
            "pod 'testPod2', '~>0.0.4'",
            "pod 'testPod3/subspec1'"
        );
        List<String> expectedNames1 = List.of("testPod1", "testPod2", "testPod3");
        List<String> expectedDefinitions2 = List.of(
            "pod 'testPod1', '1.0.3'",
            "pod 'testPod2', '~>0.0.4'",
            "pod 'testPod3/subspec1'",
            "pod 'testPod5', '1.0.3'",
            "pod 'testPod3/subspec2', '~>0.0.4'",
            "pod 'testPod2'"
        );
        List<String> expectedNames2 = List.of("testPod1", "testPod2", "testPod3", "testPod5");

        PodfileParser.ParseResult test = new PodfileParser.ParseResult("ios", "12.0");
        assertDoesNotThrow(() -> { test.mergeWith(result1); });
        assertTrue(test.podDefinitions.containsAll(expectedDefinitions1) && expectedDefinitions1.containsAll(test.podDefinitions));
        assertTrue(test.podNames.containsAll(expectedNames1) && expectedNames1.containsAll(test.podNames));

        assertDoesNotThrow(() -> { test.mergeWith(result2); });
        assertTrue(test.podDefinitions.containsAll(expectedDefinitions2) && expectedDefinitions2.containsAll(test.podDefinitions));
        assertTrue(test.podNames.containsAll(expectedNames2) && expectedNames2.containsAll(test.podNames));
    }
}
