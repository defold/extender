package com.defold.extender.metrics;

import com.defold.extender.Timer;
import org.junit.Before;
import org.junit.Test;
import org.springframework.boot.actuate.metrics.GaugeService;

import javax.servlet.http.HttpServletRequest;

import java.io.File;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MetricsWriterTest {

    private MetricsWriter metricsWriter;
    private GaugeService gaugeService;
    private Timer timer;

    @Before
    public void setUp() {
        gaugeService = mock(GaugeService.class);
        timer = mock(Timer.class);

        metricsWriter = new MetricsWriter(gaugeService, timer);

        when(timer.start()).thenReturn(12345L);
    }

    @Test
    public void measureReceivedRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContentLength()).thenReturn(500);

        metricsWriter.measureReceivedRequest(request);

        verify(gaugeService, times(2)).submit(any(String.class), any(Double.class));
        verify(gaugeService).submit("job.receive", 12345L);
        verify(gaugeService).submit("job.requestSize", 500L);
    }

    @Test
    public void measureSdkDownload() {
        metricsWriter.measureSdkDownload();

        verify(gaugeService, times(1)).submit(any(String.class), any(Double.class));
        verify(gaugeService).submit("job.sdkDownload", 12345L);
    }

    @Test
    public void measureEngineBuild() {
        metricsWriter.measureEngineBuild("ios");

        verify(gaugeService, times(1)).submit(any(String.class), any(Double.class));
        verify(gaugeService).submit("job.build.ios", 12345L);
    }

    @Test
    public void measureZipFiles() {
        File file = mock(File.class);
        when(file.length()).thenReturn(3456273424L);

        metricsWriter.measureZipFiles(file);

        verify(gaugeService, times(2)).submit(any(String.class), any(Double.class));
        verify(gaugeService).submit("job.zip", 12345L);
        verify(gaugeService).submit("job.zipSize", 3456273424L);
    }

    @Test
    public void measureSentResponse() {
        metricsWriter.measureSentResponse();

        verify(gaugeService, times(1)).submit(any(String.class), any(Double.class));
        verify(gaugeService).submit("job.write", 12345L);
    }
}