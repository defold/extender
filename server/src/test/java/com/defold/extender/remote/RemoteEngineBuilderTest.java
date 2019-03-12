package com.defold.extender.remote;

import com.defold.extender.ExtenderException;
import org.apache.http.HttpEntity;
import org.apache.http.StatusLine;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.message.BasicHttpResponse;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

public class RemoteEngineBuilderTest {

    private RemoteEngineBuilder remoteEngineBuilder;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws IOException {
        final String remoteBuilderBaseUrl = "https://test.darwin-build.defold.com";
        final HttpEntity httpEntity = mock(HttpEntity.class);

        remoteEngineBuilder = spy(new RemoteEngineBuilder(remoteBuilderBaseUrl));
        doReturn(httpEntity).when(remoteEngineBuilder).buildHttpEntity(any(File.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void buildSuccessfully() throws IOException, ExtenderException {
        final File directory = mock(File.class);
        final String content = "hejhej";

        HttpEntity httpEntity = new ByteArrayEntity(content.getBytes(), ContentType.MULTIPART_FORM_DATA);

        StatusLine statusLine = mock(StatusLine.class);
        doReturn(200).when(statusLine).getStatusCode();

        BasicHttpResponse response = mock(BasicHttpResponse.class);
        doReturn(httpEntity).when(response).getEntity();
        doReturn(statusLine).when(response).getStatusLine();

        doReturn(response)
                .when(remoteEngineBuilder)
                .sendRequest(anyString(), anyString(), any(HttpEntity.class));

        byte[] bytes = remoteEngineBuilder.build(directory, "armv7-ios", "a6876bc5s");

        assertEquals(content, new String(bytes));
    }

    @Test(expected = RemoteBuildException.class)
    @SuppressWarnings("unchecked")
    public void buildShouldThrowException() throws IOException, ExtenderException {
        final File directory = mock(File.class);
        final String content = "Internal server error";

        HttpEntity httpEntity = new ByteArrayEntity(content.getBytes(), ContentType.MULTIPART_FORM_DATA);

        StatusLine statusLine = mock(StatusLine.class);
        doReturn(500).when(statusLine).getStatusCode();

        BasicHttpResponse response = mock(BasicHttpResponse.class);
        doReturn(httpEntity).when(response).getEntity();
        doReturn(statusLine).when(response).getStatusLine();

        doReturn(response)
                .when(remoteEngineBuilder)
                .sendRequest(anyString(), anyString(), any(HttpEntity.class));

        remoteEngineBuilder.build(directory, "armv7-ios", "a6876bc5s");
    }
}
