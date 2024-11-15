package com.defold.extender.metrics;

import jakarta.servlet.http.HttpServletRequest;
import java.io.File;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.BaseUnits;

public class MetricsWriter {

    private final MeterRegistry registry;
    private final com.defold.extender.Timer timer;

    public MetricsWriter(MeterRegistry registry, com.defold.extender.Timer timer) {
        this.registry = registry;
        this.timer = timer;

        this.timer.start();

        // preregister and configure summary metrics
        DistributionSummary.builder("extender.job.requestSize").baseUnit(BaseUnits.BYTES).register(registry);
        DistributionSummary.builder("extender.job.zipSize").baseUnit(BaseUnits.BYTES).register(registry);
        DistributionSummary.builder("extender.job.cache.uploadSize").baseUnit(BaseUnits.BYTES).register(registry);
        DistributionSummary.builder("extender.job.cache.uploadCount").baseUnit(BaseUnits.FILES).register(registry);
        DistributionSummary.builder("extender.job.cache.downloadSize").baseUnit(BaseUnits.BYTES).register(registry);
        DistributionSummary.builder("extender.job.cache.downloadCount").baseUnit(BaseUnits.FILES).register(registry);
    }

    public MetricsWriter(final MeterRegistry registry) {
        this(registry, new com.defold.extender.Timer());
    }

    public void measureReceivedRequest(final HttpServletRequest request) {
        metricsTimer(this.registry, "extender.job.receive", timer.start());
        metricsSummary(this.registry, "extender.job.requestSize", request.getContentLengthLong());
    }

    public void measureSdkDownload(String sdk) {
        metricsTimer(this.registry, "extender.job.sdkDownload", timer.start());
        metricsCounterIncrement(registry, "extender.job.sdk", "job_sdk", sdk);
    }

    public void measureGradleDownload() {
        metricsTimer(this.registry, "extender.job.gradle.download", timer.start());
    }

    public void measureCocoaPodsInstallation() {
        metricsTimer(this.registry, "extender.job.cocoapods.install", timer.start());
    }

    public void measureEngineBuild(final String platform) {
        metricsTimer(this.registry, "extender.job.build", timer.start(), "platform", platform);
    }

    public void measureRemoteEngineBuild(final String platform) {
        metricsTimer(this.registry, "extender.job.remoteBuild", timer.start(), "platform", platform);
    }

    public void measureRemoteEngineBuild(long duration, final String platform) {
        metricsTimer(this.registry, "extender.job.remoteBuild", duration, "platform", platform);
    }

    public void measureZipFiles(final File zipFile) {
        metricsTimer(this.registry, "extender.job.zip", timer.start());
        metricsSummary(this.registry, "extender.job.zipSize", zipFile.length());
    }

    public void measureSentResponse() {
        metricsTimer(this.registry, "extender.job.write", timer.start());
    }

    public void measureCacheUpload(long uploadSize, int uploadCount) {
        metricsTimer(this.registry, "extender.job.cache.upload", timer.start());
        metricsSummary(this.registry, "extender.job.cache.uploadSize", uploadSize);
        metricsSummary(this.registry, "extender.job.cache.uploadCount", uploadCount);
    }

    public void measureCacheDownload(long downloadSize, int downloadCount) {
        metricsTimer(this.registry, "extender.job.cache.download", timer.start());
        metricsSummary(this.registry, "extender.job.cache.downloadSize", downloadSize);
        metricsSummary(this.registry, "extender.job.cache.downloadCount", downloadCount);
    }

    public void measureCounterBuild(String platform, String sdk, String buildType, Boolean isSuccessfull) {
        measureCounterBuild("platform", platform, "sdk", sdk, "type", buildType, "success", isSuccessfull.toString());
    }

    private void measureCounterBuild(String... tags) {
        metricsCounterIncrement(this.registry, "extender.build.task", tags);
    }

    public static void metricsSummary(MeterRegistry registry, String id, long value, String... tags) {
        registry.summary(id, tags).record(value);
    }

    public static void metricsTimer(MeterRegistry registry, String id, long millis, String... tags) {
        registry.timer(id, Tags.of(tags)).record(millis, TimeUnit.MILLISECONDS);
    }

    public static void metricsCounterIncrement(MeterRegistry registry, String id, String... tags) {
        registry.counter(id, Tags.of(tags)).increment();
    }

    public void measureBuildTarget(String target) {
        metricsCounterIncrement(this.registry, "extender.build.target", "target", target);
    }
}
