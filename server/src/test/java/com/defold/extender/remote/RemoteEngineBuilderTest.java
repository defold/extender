// package com.defold.extender.remote;

// import com.defold.extender.ExtenderException;

// import org.apache.http.HttpEntity;
// import org.apache.http.StatusLine;
// import org.apache.http.entity.ByteArrayEntity;
// import org.apache.http.entity.ContentType;
// import org.apache.http.message.BasicHttpResponse;
// import org.junit.jupiter.api.BeforeEach;
// import org.junit.jupiter.api.Test;

// import java.io.File;
// import java.io.IOException;
// import java.util.Optional;
// import java.io.ByteArrayOutputStream;

// import static org.junit.jupiter.api.Assertions.assertEquals;
// import static org.junit.jupiter.api.Assertions.assertThrows;
// import static org.mockito.ArgumentMatchers.any;
// import static org.mockito.ArgumentMatchers.anyString;
// import static org.mockito.Mockito.doReturn;
// import static org.mockito.Mockito.mock;
// import static org.mockito.Mockito.spy;
// import io.micrometer.tracing.test.simple.SimpleTracer;

// public class RemoteEngineBuilderTest {

//     private RemoteEngineBuilder remoteEngineBuilder;
//     final RemoteInstanceConfig remoteBuilderConfig = new RemoteInstanceConfig("https://test.darwin-build.defold.com", "osx-latest", true);

//     @BeforeEach
//     public void setUp() throws IOException {
//         final HttpEntity httpEntity = mock(HttpEntity.class);

//         remoteEngineBuilder = spy(new RemoteEngineBuilder(Optional.empty(), "/var/tmp/results", 5000, 240000, new SimpleTracer(), null));
//         doReturn(httpEntity).when(remoteEngineBuilder).buildHttpEntity(any(File.class));
//     }

//     @Test
//     public void buildSuccessfully() throws IOException, ExtenderException {
//         final File directory = mock(File.class);
//         final String content = "hejhej";

//         HttpEntity httpEntity = new ByteArrayEntity(content.getBytes(), ContentType.MULTIPART_FORM_DATA);

//         StatusLine statusLine = mock(StatusLine.class);
//         doReturn(200).when(statusLine).getStatusCode();

//         BasicHttpResponse response = mock(BasicHttpResponse.class);
//         doReturn(httpEntity).when(response).getEntity();
//         doReturn(statusLine).when(response).getStatusLine();

//         doReturn(response)
//                 .when(remoteEngineBuilder)
//                 .sendRequest(anyString(), anyString(), anyString(), any(HttpEntity.class));

//         ByteArrayOutputStream baos = new ByteArrayOutputStream();
//         remoteEngineBuilder.build(remoteBuilderConfig, directory, "armv7-ios", "a6876bc5s", baos);

//         byte[] bytes = baos.toByteArray();
//         assertEquals(content, new String(bytes));
//     }

//     @Test
//     public void buildShouldThrowException() throws IOException, ExtenderException {
//         final File directory = mock(File.class);
//         final String content = "Internal server error";

//         HttpEntity httpEntity = new ByteArrayEntity(content.getBytes(), ContentType.MULTIPART_FORM_DATA);

//         StatusLine statusLine = mock(StatusLine.class);
//         doReturn(500).when(statusLine).getStatusCode();

//         BasicHttpResponse response = mock(BasicHttpResponse.class);
//         doReturn(httpEntity).when(response).getEntity();
//         doReturn(statusLine).when(response).getStatusLine();

//         doReturn(response)
//                 .when(remoteEngineBuilder)
//                 .sendRequest(anyString(), anyString(), anyString(), any(HttpEntity.class));

//         ByteArrayOutputStream baos = new ByteArrayOutputStream();
//         assertThrows(RemoteBuildException.class, () -> {
//             remoteEngineBuilder.build(remoteBuilderConfig, directory, "armv7-ios", "a6876bc5s", baos);
//         });
        
//     }
// }
