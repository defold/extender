package com.defold.extender.services.spm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SpmLockFileTest {

    private static File fixture(String name) {
        return new File("test-data/swiftpackages/" + name);
    }

    @Test
    public void testParsesVersion3Pins() throws IOException, SpmManifestParsingException {
        Map<String, SpmLockFile.Pin> pins = SpmLockFile.parse(fixture("lock_v2.json"));

        assertEquals(List.of("github.com/firebase/firebase-ios-sdk", "github.com/getsentry/sentry-cocoa",
            "github.com/apple/swift-protobuf"), List.copyOf(pins.keySet()));
        SpmLockFile.Pin firebase = pins.get("github.com/firebase/firebase-ios-sdk");
        assertEquals("https://github.com/firebase/firebase-ios-sdk.git", firebase.location);
        assertEquals("0123456789abcdef0123456789abcdef01234567", firebase.revision);
        assertEquals("12.0.0", firebase.label);
        // the location is kept exactly as SwiftPM will use it (here without .git)
        assertEquals("https://github.com/getsentry/sentry-cocoa", pins.get("github.com/getsentry/sentry-cocoa").location);
        assertEquals("main", pins.get("github.com/apple/swift-protobuf").label);
    }

    @Test
    public void testParsesLegacyVersion1Pins() throws IOException, SpmManifestParsingException {
        Map<String, SpmLockFile.Pin> pins = SpmLockFile.parse(fixture("lock_v1.json"));

        assertEquals(1, pins.size());
        SpmLockFile.Pin sentry = pins.get("github.com/getsentry/sentry-cocoa");
        assertEquals("https://github.com/getsentry/sentry-cocoa.git", sentry.location);
        assertEquals("89abcdef0123456789abcdef0123456789abcdef", sentry.revision);
        assertEquals("9.0.0", sentry.label);
    }

    @Test
    public void testIgnoresRegistryAndLocalPins() throws IOException, SpmManifestParsingException {
        // an author's lock file may pin packages the wrapper never references; only remote
        // source-control pins can be mirrored, the rest are skipped rather than fatal
        assertTrue(SpmLockFile.parse(fixture("lock_registry.json")).isEmpty());
    }

    @Test
    public void testRejectsRevisionThatIsNotACommitHash() {
        SpmManifestParsingException e = assertThrows(SpmManifestParsingException.class,
            () -> SpmLockFile.parse(fixture("lock_bad_revision.json")));
        assertTrue(e.getMessage().contains("invalid revision"), e.getMessage());
    }

    @Test
    public void testLocationsGoThroughUrlSanitizer() {
        SpmManifestParsingException e = assertThrows(SpmManifestParsingException.class,
            () -> SpmLockFile.parse(fixture("lock_ssh_location.json")));
        assertTrue(e.getMessage().contains("sentry-cocoa"), e.getMessage());
    }

    @Test
    public void testMissingAndMalformedFiles(@TempDir File dir) throws IOException {
        assertThrows(SpmManifestParsingException.class, () -> SpmLockFile.parse(new File(dir, "Package.resolved")));

        File notJson = new File(dir, "a.resolved");
        Files.writeString(notJson.toPath(), "{ pins: [");
        assertThrows(SpmManifestParsingException.class, () -> SpmLockFile.parse(notJson));

        File noPins = new File(dir, "b.resolved");
        Files.writeString(noPins.toPath(), "{ \"version\": 2 }");
        assertTrue(assertThrows(SpmManifestParsingException.class, () -> SpmLockFile.parse(noPins))
            .getMessage().contains("pins"));

        File conflicting = new File(dir, "c.resolved");
        Files.writeString(conflicting.toPath(), "{ \"pins\": [" +
            pin("https://github.com/a/b.git", "0123456789abcdef0123456789abcdef01234567") + "," +
            pin("https://github.com/a/b", "89abcdef0123456789abcdef0123456789abcdef") + "], \"version\": 2 }");
        assertTrue(assertThrows(SpmManifestParsingException.class, () -> SpmLockFile.parse(conflicting))
            .getMessage().contains("twice"));

        File tooMany = new File(dir, "d.resolved");
        StringBuilder many = new StringBuilder("{ \"pins\": [");
        for (int i = 0; i <= SpmLockFile.MAX_PINS; i++) {
            if (i > 0) {
                many.append(',');
            }
            many.append(pin("https://github.com/a/b" + i + ".git", "0123456789abcdef0123456789abcdef01234567"));
        }
        Files.writeString(tooMany.toPath(), many.append("], \"version\": 2 }").toString());
        assertTrue(assertThrows(SpmManifestParsingException.class, () -> SpmLockFile.parse(tooMany))
            .getMessage().contains("max"));
    }

    @Test
    public void testPinWithoutVersionOrBranchHasNoLabel(@TempDir File dir) throws IOException, SpmManifestParsingException {
        File lock = new File(dir, "Package.resolved");
        Files.writeString(lock.toPath(), "{ \"pins\": [" +
            pin("https://github.com/a/b.git", "0123456789abcdef0123456789abcdef01234567") + "], \"version\": 2 }");
        SpmLockFile.Pin pin = SpmLockFile.parse(lock).get("github.com/a/b");
        assertNull(pin.label);
        assertEquals("https://github.com/a/b.git@0123456789ab", pin.toString());
    }

    private static String pin(String location, String revision) {
        return "{ \"identity\": \"x\", \"kind\": \"remoteSourceControl\", \"location\": \"" + location
            + "\", \"state\": { \"revision\": \"" + revision + "\" } }";
    }
}
