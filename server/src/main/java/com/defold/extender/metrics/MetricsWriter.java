package com.defold.extender.metrics;

import com.defold.extender.Timer;
import org.springframework.boot.actuate.metrics.GaugeService;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.util.Map;
import java.util.TreeMap;

public class MetricsWriter {

    private final GaugeService gaugeService;
    private final Timer timer;

    private final Map<String,Long> metrics = new TreeMap<>();

    public MetricsWriter(GaugeService gaugeService, Timer timer) {
        this.gaugeService = gaugeService;
        this.timer = timer;

        this.timer.start();
    }

    public MetricsWriter(final GaugeService gaugeService) {
        this(gaugeService, new Timer());
    }

    private void addMetric(final String name, final long value) {
        gaugeService.submit(name, value);
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

    @Override
    public String toString() {
        return "metrics=" + metrics;
    }
}
