package com.defold.extender.remote;

import com.defold.extender.ExtenderException;
import org.junit.Before;
import org.junit.Test;
import org.junit.Ignore;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@Ignore
public class RemoteEngineBuilderTest {

    private RemoteEngineBuilder remoteEngineBuilder;
    private RestTemplate restTemplate;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws IOException {
        final String remoteBuilderBaseUrl = "https://test.darwin-build.defold.com";
        final HttpEntity<MultiValueMap<String, Object>> httpEntity = mock(HttpEntity.class);

        restTemplate = mock(RestTemplate.class);

        remoteEngineBuilder = spy(new RemoteEngineBuilder(restTemplate, remoteBuilderBaseUrl));
        doReturn(httpEntity).when(remoteEngineBuilder).createMultipartRequest(any(File.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void buildSuccessfully() throws IOException, ExtenderException {
        final File directory = mock(File.class);
        final String content = "hejhej";

        when(restTemplate.postForEntity(
                anyString(),
                any(HttpEntity.class),
                any(Class.class)))
                .thenReturn(new ResponseEntity<>(content.getBytes(), HttpStatus.OK));

        byte[] bytes = remoteEngineBuilder.build(directory, "armv7-ios", "a6876bc5s");

        assertEquals(content, new String(bytes));
    }

    @Test(expected = ExtenderException.class)
    @SuppressWarnings("unchecked")
    public void buildShouldThrowException() throws IOException, ExtenderException {
        final File directory = mock(File.class);
        final String content = "Internal server error";

        when(restTemplate.postForEntity(
                anyString(),
                any(HttpEntity.class),
                any(Class.class)))
                .thenReturn(new ResponseEntity<>(content.getBytes(), HttpStatus.INTERNAL_SERVER_ERROR));

        remoteEngineBuilder.build(directory, "armv7-ios", "a6876bc5s");
    }
}
