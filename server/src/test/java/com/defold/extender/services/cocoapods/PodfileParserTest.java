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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class PodfileParserTest {
    private File emptyPodfile;
    private File withCommentPodfile;
    private File regularPodfile;
    private File wrongPodfile;

    @BeforeEach
    public void setUp(TestInfo testInfo) {
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
    public void testCompareVersions() {
        assertThrows(NullPointerException.class, () -> { PodfileParser.compareVersions(null, null); });
        assertEquals(0, PodfileParser.compareVersions("12.0", "12.0"));
        assertEquals(0, PodfileParser.compareVersions("12.0", "12.0."));
        assertTrue(PodfileParser.compareVersions("13.0.1", "13.0") > 0);
        assertTrue(PodfileParser.compareVersions("9.3", "12.6.1") < 0);
        assertThrows(NumberFormatException.class, () -> { PodfileParser.compareVersions("9.3", "unknown"); });
        assertThrows(NullPointerException.class, () -> { PodfileParser.compareVersions("9.3", null); });
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
