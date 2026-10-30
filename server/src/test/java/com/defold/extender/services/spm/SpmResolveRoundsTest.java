package com.defold.extender.services.spm;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.defold.extender.ExtenderException;
import com.defold.extender.services.spm.SwiftPackageManagerService.ResolveRounds;
import com.defold.extender.services.spm.SwiftPackageManagerService.ResolveRounds.Next;

public class SpmResolveRoundsTest {

    private static final long BUDGET = 900_000L;
    private static final ResolveRounds.ArtifactUrlLookup NO_LOOKUP = targetName -> {
        throw new AssertionError("no checksum mismatch expected, looked up " + targetName);
    };

    private static String missingRepo(String url) {
        return "Fetching from https://github.com/acme/Root.git\n"
            + "xcodebuild: error: " + SwiftPackageManagerService.RESOLUTION_FAILED_MARKER + ":\n"
            + "  Failed to clone repository " + url + ":\n"
            + "    fatal: unable to access '" + url + "/': transport 'https' not allowed\n";
    }

    private static String missingArtifact(String url, String targetName) {
        return "xcodebuild: error: " + SwiftPackageManagerService.RESOLUTION_FAILED_MARKER + ":\n"
            + "  failed downloading '" + url + "' which is required by binary target '" + targetName + "'\n";
    }

    private static String checksumMismatch(String targetName) {
        return "xcodebuild: error: " + SwiftPackageManagerService.RESOLUTION_FAILED_MARKER + ":\n"
            + "  checksum of downloaded artifact of binary target '" + targetName + "' (ab) "
            + SwiftPackageManagerService.CHECKSUM_MISMATCH_MARKER + " (cd)\n";
    }

    private static ResolveRounds seeded(String... urls) throws ExtenderException {
        ResolveRounds rounds = new ResolveRounds(BUDGET);
        for (String url : urls) {
            SwiftPackageManagerService.addRepo(rounds.repos, url);
        }
        return rounds;
    }

    @Test
    public void testKeepsGoingPastTwelveRoundsWhileEachNamesANewRepository() throws Exception {
        // SwiftPM gives up at the first repository it cannot clone: a shallow graph of 37
        // repositories (AdMob and its mediation adapters) is discovered one per round
        ResolveRounds rounds = seeded("https://github.com/acme/Root.git");
        for (int i = 1; i < 37; i++) {
            assertEquals(Next.DISCOVERED, rounds.afterFailure(missingRepo("https://github.com/acme/Adapter" + i + ".git"), NO_LOOKUP));
            assertEquals(1, rounds.newRepos);
            assertEquals(0, rounds.newArtifacts);
            rounds.requireWithinBudget(i * 1000L, i);
        }
        assertEquals(37, rounds.repos.size());
    }

    @Test
    public void testRoundNamingNothingNewFails() throws Exception {
        ResolveRounds compileError = seeded("https://github.com/acme/Root.git");
        assertEquals(Next.FAIL, compileError.afterFailure("Foo.swift:3:1: error: cannot find 'x' in scope", NO_LOOKUP));

        // a repository named again is no progress; the mirror refresh is the only retry left
        ResolveRounds known = seeded("https://github.com/acme/Root.git", "https://github.com/acme/Dep.git");
        String failure = missingRepo("https://github.com/acme/Dep.git");
        assertEquals(Next.REFRESH_MIRRORS, known.afterFailure(failure, NO_LOOKUP));
        assertEquals(0, known.newRepos);
        assertEquals(Next.FAIL, known.afterFailure(failure, NO_LOOKUP));
        assertEquals(2, known.repos.size());
    }

    @Test
    public void testRepositoryCapEndsAGraphThatKeepsGrowing() throws Exception {
        ResolveRounds rounds = seeded("https://github.com/acme/Root.git");
        int round = 0;
        ExtenderException e = null;
        while (e == null) {
            round++;
            String failure = missingRepo("https://github.com/acme/p" + round + ".git");
            try {
                assertEquals(Next.DISCOVERED, rounds.afterFailure(failure, NO_LOOKUP));
            } catch (ExtenderException thrown) {
                e = thrown;
            }
        }
        assertEquals(SwiftPackageManagerService.MAX_MIRRORED_PACKAGES, round);
        assertEquals(SwiftPackageManagerService.MAX_MIRRORED_PACKAGES, rounds.repos.size());
        assertTrue(e.getMessage().contains("more than " + SwiftPackageManagerService.MAX_MIRRORED_PACKAGES + " repositories"),
            e.getMessage());
    }

    @Test
    public void testArtifactCapEndsAGraphThatKeepsGrowing() throws Exception {
        ResolveRounds rounds = seeded("https://github.com/acme/Root.git");
        int round = 0;
        ExtenderException e = null;
        while (e == null) {
            round++;
            String failure = missingArtifact("https://dl.example.com/sdk/" + round + ".zip", "T" + round);
            try {
                assertEquals(Next.DISCOVERED, rounds.afterFailure(failure, NO_LOOKUP));
                assertEquals(1, rounds.newArtifacts);
            } catch (ExtenderException thrown) {
                e = thrown;
            }
        }
        assertEquals(SwiftPackageManagerService.MAX_BINARY_ARTIFACTS + 1, round);
        assertEquals(SwiftPackageManagerService.MAX_BINARY_ARTIFACTS, rounds.artifacts.size());
        assertTrue(e.getMessage().contains("more than " + SwiftPackageManagerService.MAX_BINARY_ARTIFACTS + " binary targets"),
            e.getMessage());
    }

    @Test
    public void testTimeBudgetExceededNamesTheBudgetAndTheProgress() throws Exception {
        assertEquals(BUDGET, new SpmServiceConfiguration().getResolveTimeBudget());

        ResolveRounds rounds = seeded("https://github.com/acme/Root.git");
        for (int i = 1; i <= 20; i++) {
            rounds.afterFailure(missingRepo("https://github.com/acme/p" + i + ".git"), NO_LOOKUP);
        }
        rounds.afterFailure(missingArtifact("https://dl.example.com/sdk/Foo.zip", "Foo"), NO_LOOKUP);
        assertDoesNotThrow(() -> rounds.requireWithinBudget(BUDGET - 1, 21));

        ExtenderException e = assertThrows(ExtenderException.class, () -> rounds.requireWithinBudget(BUDGET, 21));
        String message = e.getMessage();
        assertTrue(message.contains("15 minute time budget"), message);
        assertTrue(message.contains("extender.spm.resolve-time-budget"), message);
        assertTrue(message.contains("after 21 rounds it had grown to 21 repositories and 1 binary target archives"), message);
        assertTrue(message.contains(SwiftPackageManagerService.LOCK_FILENAME), message);

        ResolveRounds seconds = new ResolveRounds(90_000);
        e = assertThrows(ExtenderException.class, () -> seconds.requireWithinBudget(90_001, 3));
        assertTrue(e.getMessage().contains("90 second time budget"), e.getMessage());

        ResolveRounds off = new ResolveRounds(0);
        assertDoesNotThrow(() -> off.requireWithinBudget(Long.MAX_VALUE, 1000));
    }

    @Test
    public void testChecksumRefetchAndMirrorRefreshHappenOnceEach() throws Exception {
        String url = "https://github.com/getsentry/sentry-cocoa/releases/download/9.18.0/Sentry.xcframework.zip";
        ResolveRounds rounds = seeded("https://github.com/getsentry/sentry-cocoa.git");
        ResolveRounds.ArtifactUrlLookup lookup = targetName -> "Sentry".equals(targetName) ? url : null;

        assertEquals(Next.REFETCH_ARTIFACTS, rounds.afterFailure(checksumMismatch("Sentry"), lookup));
        assertEquals(Set.of(url), rounds.evictArtifacts);
        assertTrue(rounds.artifacts.containsKey(url));
        rounds.evictArtifacts.clear();

        // discovery in between resets neither retry
        assertEquals(Next.DISCOVERED, rounds.afterFailure(missingRepo("https://github.com/acme/Dep.git"), NO_LOOKUP));

        // the archive still mismatches after the refetch: the mirror refresh is the last retry
        assertEquals(Next.REFRESH_MIRRORS, rounds.afterFailure(checksumMismatch("Sentry"), lookup));
        assertEquals(Set.of(), rounds.evictArtifacts);
        assertEquals(Next.DISCOVERED, rounds.afterFailure(missingRepo("https://github.com/acme/Dep2.git"), NO_LOOKUP));
        assertEquals(Next.FAIL, rounds.afterFailure(checksumMismatch("Sentry"), lookup));
    }

    @Test
    public void testChecksumMismatchWithoutALiteralUrlFails() throws Exception {
        ResolveRounds rounds = seeded("https://github.com/acme/Root.git");
        ExtenderException e = assertThrows(ExtenderException.class,
            () -> rounds.afterFailure(checksumMismatch("Opaque"), targetName -> null));
        assertTrue(e.getMessage().contains("binary target Opaque"), e.getMessage());
        assertEquals(Set.of(), rounds.evictArtifacts);
    }
}
