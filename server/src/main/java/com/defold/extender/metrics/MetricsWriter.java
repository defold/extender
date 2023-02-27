package com.defold.extender.metrics;

//import com.defold.extender.Timer;
//import org.springframework.boot.actuate.metrics.GaugeService;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Tags;

public class MetricsWriter {

    private final MeterRegistry registry;
    private final com.defold.extender.Timer timer;

    private final Map<String,Long> metrics = new TreeMap<>();

    public MetricsWriter(MeterRegistry registry, com.defold.extender.Timer timer) {
        this.registry = registry;
        this.timer = timer;

        this.timer.start();
    }

    public MetricsWriter(final MeterRegistry registry) {
        this(registry, new com.defold.extender.Timer());
    }

    private void addMetric(final String name, final long value, String... tags) {
        registry.gauge(name, Tags.of(tags), value);
        metrics.put(name, value);
    }

    public void measureReceivedRequest(final HttpServletRequest request) {
        addMetric("job.receive", timer.start());
        addMetric("job.requestSize", request.getContentLength());
    }

    public void measureSdkDownload(String sdk) {
        addMetric("job.sdkDownload", timer.start());
        metricsCounterIncrement(registry, "job.sdk", "job_sdk", sdk);
    }

    public void measureGradleDownload(long cacheSize) {
        addMetric("job.gradle.download", timer.start());
        addMetric("job.gradle.cacheSize", cacheSize);
    }

    public void measureCocoaPodsInstallation() {
        addMetric("job.cocoapods.install", timer.start());
    }

    public void measureEngineBuild(final String platform) {
        addMetric("job.build", timer.start(), "platform", platform);
    }

    public void measureRemoteEngineBuild(final String platform) {
        addMetric("job.remoteBuild", timer.start(), "platform", platform);
    }

    public void measureZipFiles(final File zipFile) {
        addMetric("job.zip", timer.start());
        addMetric("job.zipSize", zipFile.length());
    }

    public void measureSentResponse() {
        addMetric("job.write", timer.start());
    }

    public void measureCacheUpload(long uploadSize) {
        addMetric("job.cache.upload", timer.start());
        addMetric("job.cache.uploadSize", uploadSize);
    }

    public void measureCacheDownload(long downloadSize) {
        addMetric("job.cache.download", timer.start());
        addMetric("job.cache.downloadSize", downloadSize);
    }

    public static void metricsGauge(MeterRegistry registry, String id, long value, String... tags) {
        registry.gauge(id, Tags.of(tags), value);
    }

    public static void metricsTimer(MeterRegistry registry, String id, long millis, String... tags) {
        Timer timer = registry.timer(id, tags);
        timer.record(millis, TimeUnit.SECONDS);
    }

    public static void metricsCounterIncrement(MeterRegistry registry, String id, String... tags) {
        Counter counter = registry.counter(id, tags);
        counter.increment();
    }

    @Override
    public String toString() {
        return "metrics=" + metrics;
    }
}
