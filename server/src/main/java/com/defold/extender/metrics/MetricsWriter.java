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

    private void addMetric(final String name, final long value) {
        registry.gauge(name, value);
        metrics.put(name, value);
    }

    public void measureReceivedRequest(final HttpServletRequest request) {
        addMetric("job.receive", timer.start());
        addMetric("job.requestSize", request.getContentLength());
    }

    public void measureSdkDownload(String sdk) {
        addMetric("job.sdkDownload", timer.start());
        addMetric("job.sdk." + sdk, 1);
    }

    public void measureGradleDownload(List<File> packages, long cacheSize) {
        addMetric("job.gradle.download", timer.start());
        addMetric("job.gradle.cacheSize", cacheSize);
        // for (File file : packages) {
        //     addMetric("job.gradle.package." + file.getName().replace('.', '-'), 1);
        // }
    }

    public void measureEngineBuild(final String platform) {
        addMetric("job.build." + platform, timer.start());
    }

    public void measureRemoteEngineBuild(final String platform) {
        addMetric("job.remoteBuild." + platform, timer.start());
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

    public static void metricsGauge(MeterRegistry registry, String id, long value) {
        registry.gauge(id, value);
    }

    public static void metricsTimer(MeterRegistry registry, String id, long millis) {
        Timer timer = registry.timer(id);
        timer.record(millis, TimeUnit.SECONDS);
    }

    public static void metricsCounterIncrement(MeterRegistry registry, String id) {
        Counter counter = registry.counter(id);
        counter.increment();
    }

    @Override
    public String toString() {
        return "metrics=" + metrics;
    }
}
