package com.defold.extender;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class TimerTest {

    @Test
    public void startReturnsTheElapsedTimeSinceTheLastCall() throws InterruptedException {
        Timer timer = new Timer();
        timer.start();

        Thread.sleep(100);
        long lap = timer.start();

        // Deliberately loose: Thread.sleep only guarantees a lower bound, and clock granularity
        // on Windows is ~15ms.
        assertTrue(lap >= 90, String.format("Expected a lap of at least 90ms, got %d", lap));
    }

    @Test
    public void startResetsTheBaselineSoLapsDoNotAccumulate() throws InterruptedException {
        Timer timer = new Timer();
        timer.start();

        Thread.sleep(100);
        long firstLap = timer.start();
        long secondLap = timer.start();

        assertTrue(firstLap >= 90, String.format("Expected a first lap of at least 90ms, got %d", firstLap));
        // Without the reset inside start(), the second lap would still measure from the original
        // baseline and come back >= 100ms. This is the mechanism that made calling
        // measureRemoteEngineBuild(buildTimer.start(), ...) twice halve the reported build time.
        assertTrue(secondLap < 90, String.format("Expected the baseline to reset, but the second lap was %d", secondLap));
    }

    @Test
    public void theFirstCallOnAnUnprimedTimerReturnsMillisSinceEpoch() {
        long before = System.currentTimeMillis();
        long firstLap = new Timer().start();
        long after = System.currentTimeMillis();

        // The baseline starts at 0, so an unprimed Timer reports wall-clock epoch millis rather than
        // a duration. That is why MetricsWriter's constructor and buildAsync both prime the timer
        // before measuring anything.
        assertTrue(firstLap >= before && firstLap <= after,
                String.format("Expected epoch millis in [%d, %d], got %d", before, after, firstLap));
    }
}
