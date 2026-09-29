package com.defold.extender.client;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.message.BasicStatusLine;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

public class ExtenderProgressConsumerTest {
    @Test
    public void stopWaitsForRunningCallbackAndBlocksLaterOnes() throws Exception {
        String sse = "id:1\n"
                + "data:{\"stage\":\"SDK\",\"percent\":1,\"terminal\":false}\n"
                + "\n"
                + "id:2\n"
                + "data:{\"stage\":\"COMPILING\",\"percent\":50,\"terminal\":false}\n"
                + "\n";

        HttpEntity entity = Mockito.mock(HttpEntity.class);
        when(entity.getContent()).thenReturn(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)));
        HttpResponse response = Mockito.mock(HttpResponse.class);
        when(response.getStatusLine()).thenReturn(new BasicStatusLine(new ProtocolVersion("HTTP", 1, 1), 200, "OK"));
        when(response.getEntity()).thenReturn(entity);
        HttpClient httpClient = Mockito.mock(HttpClient.class);
        when(httpClient.execute(Mockito.any(HttpGet.class))).thenReturn(response);

        List<String> stages = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        ExtenderProgressListener listener = (stage, detail, percent, currentFile, totalFiles) -> {
            stages.add(stage);
            callbackEntered.countDown();
            try {
                releaseCallback.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        ExtenderProgressConsumer consumer = new ExtenderProgressConsumer(httpClient, "http://localhost", "job1", HttpGet::new, listener);
        Thread consumerThread = new Thread(consumer);
        consumerThread.start();
        assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));

        CountDownLatch stopReturned = new CountDownLatch(1);
        Thread stopper = new Thread(() -> {
            consumer.stop();
            stopReturned.countDown();
        });
        stopper.start();
        assertFalse(stopReturned.await(200, TimeUnit.MILLISECONDS), "stop() returned while a callback was running");

        releaseCallback.countDown();
        assertTrue(stopReturned.await(5, TimeUnit.SECONDS));
        consumerThread.join(5000);

        assertEquals(List.of("SDK"), stages);
    }
}
