package com.defold.extender.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


public class CocoaPodsServiceTest {
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
    public void testPodfileParserRegularSyntax() throws IOException {
        CocoaPodsService.MainPodfile mainPodfile = CocoaPodsService.createMainPodfile();
        CocoaPodsService.parsePodfiles(mainPodfile, List.of(this.regularPodfile));
        String[] expected = {"IronSourcePangleAdapter", "IronSourceSmaatoAdapter", "IronSourceSuperAwesomeAdapter", "IronSourceTencentAdapter", "IronSourceUnityAdsAdapter", "IronSourceYandexAdapter"};
        assertArrayEquals(expected, mainPodfile.podnames.toArray());
    }

    @Test
    public void testPodfileParserComments() throws IOException {
        CocoaPodsService.MainPodfile mainPodfile = CocoaPodsService.createMainPodfile();
        mainPodfile.platformMinVersion = "10.3";
        CocoaPodsService.parsePodfiles(mainPodfile, List.of(this.withCommentPodfile));
        assertEquals(mainPodfile.platformMinVersion, "12.0");
        String[] expected = {"OneTrust-CMP-XCFramework"};
        assertArrayEquals(expected, mainPodfile.podnames.toArray());
    }

    @Test
    public void testPodfileParserWrongSyntax() throws IOException {
        CocoaPodsService.MainPodfile mainPodfile = CocoaPodsService.createMainPodfile();
        CocoaPodsService.parsePodfiles(mainPodfile, List.of(this.wrongPodfile));
        String[] expected = {"IronSourceSuperAwesomeAdapter"};
        assertArrayEquals(expected, mainPodfile.podnames.toArray());
    }

    @Test
    public void testPodfileParserEmptyFile() throws IOException {
        CocoaPodsService.MainPodfile mainPodfile = CocoaPodsService.createMainPodfile();
        CocoaPodsService.parsePodfiles(mainPodfile, List.of(this.emptyPodfile));
        assertTrue(mainPodfile.podnames.isEmpty());
    }

    @Test
    public void testPodfileParserAll() throws IOException {
        CocoaPodsService.MainPodfile mainPodfile = CocoaPodsService.createMainPodfile();
        mainPodfile.platformMinVersion = "9.1";
        CocoaPodsService.parsePodfiles(mainPodfile, List.of(this.emptyPodfile, this.wrongPodfile, this.regularPodfile, this.withCommentPodfile));
        String[] expected = {"IronSourceSuperAwesomeAdapter", "IronSourcePangleAdapter", "IronSourceSmaatoAdapter", "IronSourceSuperAwesomeAdapter", "IronSourceTencentAdapter", "IronSourceUnityAdsAdapter", "IronSourceYandexAdapter", "OneTrust-CMP-XCFramework"};
        assertArrayEquals(expected, mainPodfile.podnames.toArray());
    }
}
