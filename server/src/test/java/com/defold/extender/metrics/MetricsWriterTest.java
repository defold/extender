package com.defold.extender.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

public class MetricsWriterTest {

    private static final String REMOTE_BUILD = "extender.job.remoteBuild";
    private static final String PLATFORM = "x86_64-osx";

    private static Timer remoteBuildTimer(SimpleMeterRegistry registry, String platform) {
        return registry.timer(REMOTE_BUILD, "platform", platform);
    }

    @Test
    public void measureRemoteEngineBuildRecordsTheGivenDurationUnderThePlatformTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new MetricsWriter(registry).measureRemoteEngineBuild(1234L, PLATFORM);

        Timer timer = remoteBuildTimer(registry, PLATFORM);
        assertEquals(1, timer.count());
        assertEquals(1234.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.001);
    }

    @Test
    public void everyCallToMeasureRemoteEngineBuildRecordsOneMoreSample() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsWriter writer = new MetricsWriter(registry);

        writer.measureRemoteEngineBuild(300L, PLATFORM);
        writer.measureRemoteEngineBuild(100L, PLATFORM);

        // buildAsync used to call this once at the end of the try block and again in the finally
        // block. Because Timer.start() resets, the second sample was ~0ms: the count doubled and the
        // reported mean halved. This pins the contract that RemoteEngineBuilderTest's
        // verify(metrics, times(1)) relies on.
        Timer timer = remoteBuildTimer(registry, PLATFORM);
        assertEquals(2, timer.count());
        assertEquals(400.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.001);
        assertEquals(200.0, timer.mean(TimeUnit.MILLISECONDS), 0.001);
    }

    @Test
    public void samplesForDifferentPlatformsAreRecordedSeparately() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsWriter writer = new MetricsWriter(registry);

        writer.measureRemoteEngineBuild(1000L, PLATFORM);
        writer.measureRemoteEngineBuild(2000L, "arm64-ios");

        assertEquals(1, remoteBuildTimer(registry, PLATFORM).count());
        assertEquals(1000.0, remoteBuildTimer(registry, PLATFORM).totalTime(TimeUnit.MILLISECONDS), 0.001);
        assertEquals(1, remoteBuildTimer(registry, "arm64-ios").count());
        assertEquals(2000.0, remoteBuildTimer(registry, "arm64-ios").totalTime(TimeUnit.MILLISECONDS), 0.001);
    }

    @Test
    public void theNoDurationOverloadRecordsExactlyOneSampleFromTheInternalTimer() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new MetricsWriter(registry).measureRemoteEngineBuild(PLATFORM);

        assertEquals(1, remoteBuildTimer(registry, PLATFORM).count());
    }

    @Test
    public void theConstructorPrimesTheInternalTimerSoTheFirstSampleIsNotEpochMillis() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsWriter writer = new MetricsWriter(registry);

        writer.measureRemoteEngineBuild(PLATFORM);

        // An unprimed com.defold.extender.Timer returns System.currentTimeMillis() on its first
        // start(). Were the constructor not to prime it, this would record ~1.7e12 milliseconds.
        double totalMillis = remoteBuildTimer(registry, PLATFORM).totalTime(TimeUnit.MILLISECONDS);
        assertTrue(totalMillis < 60_000,
                String.format("Expected a sane duration, got %f ms", totalMillis));
    }

    @Test
    public void measureEngineBuildAndMeasureRemoteEngineBuildUseDistinctMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsWriter writer = new MetricsWriter(registry);

        writer.measureRemoteEngineBuild(500L, PLATFORM);

        assertEquals(1, remoteBuildTimer(registry, PLATFORM).count());
        assertEquals(0, registry.timer("extender.job.build", "platform", PLATFORM).count());
    }
}
