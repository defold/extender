package com.defold.extender.metrics;

import jakarta.servlet.http.HttpServletRequest;
import java.io.File;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

public class MetricsWriter {

    private final MeterRegistry registry;
    private final com.defold.extender.Timer timer;

    public MetricsWriter(MeterRegistry registry, com.defold.extender.Timer timer) {
        this.registry = registry;
        this.timer = timer;

        this.timer.start();
    }

    public MetricsWriter(final MeterRegistry registry) {
        this(registry, new com.defold.extender.Timer());
    }

    public void measureReceivedRequest(final HttpServletRequest request) {
        metricsGauge(this.registry, "extender.job.receive", timer.start());
        metricsGauge(this.registry, "extender.job.requestSize", request.getContentLengthLong());
    }

    public void measureSdkDownload(String sdk) {
        metricsGauge(this.registry, "extender.job.sdkDownload", timer.start());
        metricsCounterIncrement(registry, "extender.job.sdk", "job_sdk", sdk);
    }

    public void measureGradleDownload(long cacheSize) {
        metricsGauge(this.registry, "extender.job.gradle.download", timer.start());
        metricsGauge(this.registry, "extender.job.gradle.cacheSize", cacheSize);
    }

    public void measureCocoaPodsInstallation() {
        metricsGauge(this.registry, "extender.job.cocoapods.install", timer.start());
    }

    public void measureEngineBuild(final String platform) {
        metricsGauge(this.registry, "extender.job.build", timer.start(), "platform", platform);
    }

    public void measureRemoteEngineBuild(final String platform) {
        metricsGauge(this.registry, "extender.job.remoteBuild", timer.start(), "platform", platform);
    }

    public void measureRemoteEngineBuild(long duration, final String platform) {
        metricsGauge(this.registry, "extender.job.remoteBuild", duration, "platform", platform);
    }

    public void measureZipFiles(final File zipFile) {
        metricsGauge(this.registry, "extender.job.zip", timer.start());
        metricsGauge(this.registry, "extender.job.zipSize", zipFile.length());
    }

    public void measureSentResponse() {
        metricsGauge(this.registry, "extender.job.write", timer.start());
    }

    public void measureCacheUpload(long uploadSize) {
        metricsGauge(this.registry, "extender.job.cache.upload", timer.start());
        metricsGauge(this.registry, "extender.job.cache.uploadSize", uploadSize);
    }

    public void measureCacheDownload(long downloadSize) {
        metricsGauge(this.registry, "extender.job.cache.download", timer.start());
        metricsGauge(this.registry, "extender.job.cache.downloadSize", downloadSize);
    }

    public void measureCounterBuild(String platform, String sdk, String buildType, Boolean isSuccessfull) {
        measureCounterBuild("platform", platform, "sdk", sdk, "type", buildType, "success", isSuccessfull.toString());
    }

    private void measureCounterBuild(String... tags) {
        metricsCounterIncrement(this.registry, "extender.build.task", tags);
    }

    public static void metricsGauge(MeterRegistry registry, String id, long value, String... tags) {
        registry.gauge(id, Tags.of(tags), value);
    }

    public static void metricsTimer(MeterRegistry registry, String id, long millis, String... tags) {
        registry.timer(id, Tags.of(tags)).record(millis, TimeUnit.MILLISECONDS);
    }

    public static void metricsCounterIncrement(MeterRegistry registry, String id, String... tags) {
        registry.counter(id, Tags.of(tags)).increment();
    }
}
