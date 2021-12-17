package com.defold.extender.metrics;

import com.defold.extender.Timer;
import org.junit.Before;
import org.junit.Test;
import io.micrometer.core.instrument.MeterRegistry;

import javax.servlet.http.HttpServletRequest;

import java.io.File;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
/*

Temporarily disabled until we've updated to a new data base

public class MetricsWriterTest {

    private MetricsWriter metricsWriter;
    private MeterRegistry meterRegistry;
    private Timer timer;

    @Before
    public void setUp() {
        meterRegistry = mock(MeterRegistry.class);
        timer = mock(Timer.class);

        metricsWriter = new MetricsWriter(meterRegistry, timer);

        when(timer.start()).thenReturn(12345L);
    }

    @Test
    public void measureReceivedRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContentLength()).thenReturn(500);

        metricsWriter.measureReceivedRequest(request);

        verify(meterRegistry, times(2)).submit(any(String.class), any(Double.class));
        verify(meterRegistry).submit("job.receive", 12345L);
        verify(meterRegistry).submit("job.requestSize", 500L);
    }

    @Test
    public void measureSdkDownload() {
        String sdkVersion = "sdk-sha1";
        metricsWriter.measureSdkDownload(sdkVersion);

        verify(meterRegistry, times(2)).submit(any(String.class), any(Double.class));
        verify(meterRegistry).submit("job.sdkDownload", 12345L);
        verify(meterRegistry).submit("job.sdk." + sdkVersion, 1);
    }

    @Test
    public void measureEngineBuild() {
        metricsWriter.measureEngineBuild("ios");

        verify(meterRegistry, times(1)).submit(any(String.class), any(Double.class));
        verify(meterRegistry).submit("job.build.ios", 12345L);
    }

    @Test
    public void measureZipFiles() {
        File file = mock(File.class);
        when(file.length()).thenReturn(3456273424L);

        metricsWriter.measureZipFiles(file);

        verify(meterRegistry, times(2)).submit(any(String.class), any(Double.class));
        verify(meterRegistry).submit("job.zip", 12345L);
        verify(meterRegistry).submit("job.zipSize", 3456273424L);
    }

    @Test
    public void measureSentResponse() {
        metricsWriter.measureSentResponse();

        verify(meterRegistry, times(1)).submit(any(String.class), any(Double.class));
        verify(meterRegistry).submit("job.write", 12345L);
    }

    @Test
    public void measureCacheUpload() {
        metricsWriter.measureCacheUpload(987234L);

        verify(meterRegistry, times(2)).submit(any(String.class), any(Double.class));
        verify(meterRegistry).submit("job.cache.upload", 12345L);
        verify(meterRegistry).submit("job.cache.uploadSize", 987234L);
    }

    @Test
    public void measureCacheDownload() {
        metricsWriter.measureCacheDownload(987234L);

        verify(meterRegistry, times(2)).submit(any(String.class), any(Double.class));
        verify(meterRegistry).submit("job.cache.download", 12345L);
        verify(meterRegistry).submit("job.cache.downloadSize", 987234L);
    }
}
*/
