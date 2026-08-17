package com.defold.extender.client;

import org.apache.http.HttpEntity;
import org.apache.http.StatusLine;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.entity.EntityBuilder;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;


import org.apache.james.mime4j.dom.BinaryBody;
import org.apache.james.mime4j.dom.Body;
import org.apache.james.mime4j.dom.Entity;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.dom.Multipart;
import org.apache.james.mime4j.message.DefaultMessageBuilder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicStatusLine;
import org.apache.http.ProtocolVersion;

public class ExtenderClientTest extends Mockito {
    @BeforeAll
    public static void beforeClass() {
        File buildDir = new File("build");
        buildDir.mkdirs();
    }

    @AfterAll
    public static void cleanup() {
        File buildLog = new File("build.log");
        buildLog.delete();
    }

    @Test
    public void testClientHeaders() throws Exception {
        try {
            final String HDR_NAME_1 = "x-custom-defold-header1";
            final String HDR_VALUE_1 = "my custom header1";
            final String HDR_NAME_2 = "x-custom-defold-header2";
            final String HDR_VALUE_2 = "my custom header2";
            DefaultHttpClient httpClient = Mockito.mock(DefaultHttpClient.class);

            ExtenderClient extenderClient = new ExtenderClient(null, httpClient, "http://localhost");
            extenderClient.setHeader(HDR_NAME_1, HDR_VALUE_1);
            extenderClient.setHeader(HDR_NAME_2, HDR_VALUE_2);

            HttpUriRequest request = extenderClient.createGetRequest("http://localhost/health");
            assertEquals(HDR_VALUE_1, request.getFirstHeader(HDR_NAME_1).getValue());
            assertEquals(HDR_VALUE_2, request.getFirstHeader(HDR_NAME_2).getValue());
        }
        catch (Exception e) {
            System.out.println("ERROR LOG:");
            throw e;
        }
    }

    @Test()
    public void testClientHandleHTTPError() throws ClientProtocolException, IOException, ExtenderClientException, ClassNotFoundException, InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException, NoSuchMethodException, SecurityException, NoSuchFieldException {
        DefaultHttpClient httpClient = Mockito.mock(DefaultHttpClient.class);
        CloseableHttpResponse httpResponse = Mockito.mock(CloseableHttpResponse.class);
        StatusLine statusLine = Mockito.mock(StatusLine.class);

        when(statusLine.getStatusCode()).thenReturn(401);
        when(httpResponse.getStatusLine()).thenReturn(statusLine);
        when(httpResponse.getEntity()).thenReturn(
            EntityBuilder.create()
            .setStream(new ByteArrayInputStream("Unauthorized".getBytes()))
            .build()
        );
        when(httpClient.execute(Mockito.any(HttpGet.class))).thenReturn(httpResponse);
        when(httpClient.execute(Mockito.any(HttpPost.class))).thenReturn(httpResponse);

        File cacheDir = new File("build");
        File targetDir = new File("output");
        File log = new File("build.log");

        File a = new File("build/a");
        File b = new File("build/b");
        File c = new File("build/c");
        FileExtenderResource aRes = new FileExtenderResource(a);
        FileExtenderResource bRes = new FileExtenderResource(b);
        FileExtenderResource cRes = new FileExtenderResource(c);

        a.deleteOnExit();
        b.deleteOnExit();
        c.deleteOnExit();

        TestUtils.writeToFile("build/a", "a");
        TestUtils.writeToFile("build/b", "b");
        TestUtils.writeToFile("build/c", "c");

        List<ExtenderResource> inputFiles = new ArrayList<>();
        inputFiles.add(aRes);
        inputFiles.add(bRes);
        inputFiles.add(cRes);

        Class<?> mockExtenderClientClass = Class.forName("com.defold.extender.client.ExtenderClient");
        ExtenderClient extenderClient = (ExtenderClient) mockExtenderClientClass.getDeclaredConstructor(String.class, File.class).newInstance("http://localhost", cacheDir);
        Field field = mockExtenderClientClass.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(extenderClient, httpClient);

        assertThrows(ExtenderClientException.class, () -> {
            extenderClient.build("js-web", "aaaaaaaa", inputFiles, targetDir, log);
        });
        
    }

    private HttpResponse mockResponse(int statusCode, byte[] body, Header... headers) {
        CloseableHttpResponse response = Mockito.mock(CloseableHttpResponse.class);
        when(response.getStatusLine()).thenReturn(
                new BasicStatusLine(new ProtocolVersion("HTTP", 1, 1), statusCode, ""));
        when(response.getEntity()).thenReturn(
                EntityBuilder.create().setStream(new ByteArrayInputStream(body)).build());
        for (Header header : headers) {
            when(response.getFirstHeader(header.getName())).thenReturn(header);
        }
        return response;
    }

    // mocks a full successful async build: /query 404, /build_async accepted,
    // /job_status done, /job_result zip bytes, /job_progress as given
    private DefaultHttpClient mockBuildHttpClient(int progressStatusCode, String progressSseBody) throws IOException {
        DefaultHttpClient httpClient = Mockito.mock(DefaultHttpClient.class);
        when(httpClient.execute(Mockito.any(HttpPost.class))).thenAnswer(invocation -> {
            HttpPost request = invocation.getArgument(0);
            if (request.getURI().toString().contains("/build_async")) {
                return mockResponse(200, "job123".getBytes(), new BasicHeader("X-TraceId", "trace123"));
            }
            return mockResponse(404, "no cache".getBytes()); // /query: caching unsupported
        });
        when(httpClient.execute(Mockito.any(HttpGet.class))).thenAnswer(invocation -> {
            HttpGet request = invocation.getArgument(0);
            String uri = request.getURI().toString();
            if (uri.contains("/job_progress")) {
                return mockResponse(progressStatusCode, progressSseBody.getBytes(StandardCharsets.UTF_8));
            }
            if (uri.contains("/job_status")) {
                return mockResponse(200, "1".getBytes());
            }
            if (uri.contains("/job_result")) {
                return mockResponse(200, "zipcontent".getBytes());
            }
            return mockResponse(404, new byte[0]);
        });
        return httpClient;
    }

    private static List<ExtenderResource> progressTestSources(File dir) throws IOException {
        File src = new File(dir, "source.cpp");
        Files.write(src.toPath(), "int main() {}".getBytes());
        src.deleteOnExit();
        List<ExtenderResource> sources = new ArrayList<>();
        sources.add(new FileExtenderResource(src));
        return sources;
    }

    @Test
    public void testBuildWithProgressListener() throws Exception {
        String sse = "id:1\n"
                + "data:{\"jobId\":\"job123\",\"seq\":1,\"ts\":1,\"stage\":\"SDK\",\"detail\":\"Downloading\",\"percent\":1,\"terminal\":false}\n"
                + "\n"
                + ":ka\n"
                + "\n"
                + "id:2\n"
                + "data:{\"jobId\":\"job123\",\"seq\":2,\"ts\":2,\"stage\":\"COMPILING\",\"detail\":\"ext1\",\"percent\":55,\"extension\":\"ext1\",\"currentFile\":12,\"totalFiles\":34,\"terminal\":false}\n"
                + "\n"
                + "id:3\n"
                + "data:{\"jobId\":\"job123\",\"seq\":3,\"ts\":3,\"stage\":\"SUCCESS\",\"detail\":\"done\",\"percent\":100,\"terminal\":true}\n"
                + "\n";

        String oldSleepTimeout = System.setProperty("com.defold.extender.client.build-sleep-timeout", "10");
        try {
            File cacheDir = Files.createTempDirectory("progress-test-cache").toFile();
            cacheDir.deleteOnExit();
            File destination = new File(cacheDir, "build.zip");
            File log = new File(cacheDir, "build.log");

            ExtenderClient extenderClient = new ExtenderClient(new ExtenderClientCache(cacheDir),
                    mockBuildHttpClient(200, sse), "http://localhost");

            List<String> events = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch terminalSeen = new CountDownLatch(1);
            ExtenderProgressListener listener = (stage, detail, percent, currentFile, totalFiles) -> {
                events.add(String.format("%s:%d:%d/%d", stage, percent, currentFile, totalFiles));
                if ("SUCCESS".equals(stage) || "ERROR".equals(stage)) {
                    terminalSeen.countDown();
                }
            };

            extenderClient.build("x86_64-linux", "testsdk1", progressTestSources(cacheDir), destination, log, listener);

            assertTrue(destination.exists());
            assertEquals("zipcontent", new String(Files.readAllBytes(destination.toPath())));
            assertTrue(terminalSeen.await(5, TimeUnit.SECONDS), "listener never saw the terminal event");
            assertTrue(events.contains("SDK:1:-1/-1"), "events: " + events);
            assertTrue(events.contains("COMPILING:55:12/34"), "events: " + events);
            assertTrue(events.contains("SUCCESS:100:-1/-1"), "events: " + events);
        } finally {
            restoreProperty("com.defold.extender.client.build-sleep-timeout", oldSleepTimeout);
        }
    }

    @Test
    public void testBuildSucceedsWhenServerHasNoProgressSupport() throws Exception {
        String oldSleepTimeout = System.setProperty("com.defold.extender.client.build-sleep-timeout", "10");
        try {
            File cacheDir = Files.createTempDirectory("progress-test-cache-404").toFile();
            cacheDir.deleteOnExit();
            File destination = new File(cacheDir, "build.zip");
            File log = new File(cacheDir, "build.log");

            // old server: /job_progress answers 404; the build must be unaffected
            ExtenderClient extenderClient = new ExtenderClient(new ExtenderClientCache(cacheDir),
                    mockBuildHttpClient(404, "not found"), "http://localhost");

            List<String> events = Collections.synchronizedList(new ArrayList<>());
            ExtenderProgressListener listener = (stage, detail, percent, currentFile, totalFiles) ->
                    events.add(stage);

            extenderClient.build("x86_64-linux", "testsdk2", progressTestSources(cacheDir), destination, log, listener);

            assertTrue(destination.exists());
            assertEquals("zipcontent", new String(Files.readAllBytes(destination.toPath())));
            assertTrue(events.isEmpty(), "no progress events expected from an old server, got: " + events);
        } finally {
            restoreProperty("com.defold.extender.client.build-sleep-timeout", oldSleepTimeout);
        }
    }

    private static void restoreProperty(String name, String oldValue) {
        if (oldValue == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, oldValue);
        }
    }

    private static Stream<Arguments> uploadData() {
        return Stream.of(
            Arguments.of("{\"files\":[{\"cached\":true,\"path\":\"build/a\"},{\"cached\":true,\"path\":\"build/b\"},{\"cached\":false,\"path\":\"build/c\"}]}", List.of("build/c")),
            Arguments.of("{\"files\":[]}", List.of("build/a", "build/b", "build/c")),
            Arguments.of(null, List.of("build/a", "build/b", "build/c"))
        );
    }
    @ParameterizedTest
    @MethodSource("uploadData")
    public void testUploadStructure(String cacheResponse, List<String> expectedFilenames) throws Exception {
        try {
            ExtenderClient extenderClient = Mockito.mock(ExtenderClient.class);
            when(extenderClient.queryCache(anyList())).thenReturn(cacheResponse);
            when(extenderClient.createBuildRequestPayload(anyList())).thenCallRealMethod();

            File a = new File("build/a");
            File b = new File("build/b");
            File c = new File("build/c");
            File e = new File("build/.DS_Store");
            FileExtenderResource aRes = new FileExtenderResource(a);
            FileExtenderResource bRes = new FileExtenderResource(b);
            FileExtenderResource cRes = new FileExtenderResource(c);
            FileExtenderResource eRes = new FileExtenderResource(e);

            a.deleteOnExit();
            b.deleteOnExit();
            c.deleteOnExit();
            e.deleteOnExit();

            TestUtils.writeToFile("build/a", "a");
            TestUtils.writeToFile("build/b", "b");
            TestUtils.writeToFile("build/c", "c");
            TestUtils.writeToFile("build/e", "e");

            List<ExtenderResource> inputFiles = new ArrayList<>();
            inputFiles.add(aRes);
            inputFiles.add(bRes);
            inputFiles.add(cRes);
            inputFiles.add(eRes);

            HttpEntity entity = extenderClient.createBuildRequestPayload(inputFiles);
            assertNotNull(entity);
            
            DefaultMessageBuilder builder = new DefaultMessageBuilder();
            builder.setContentDecoding(true);

            String headers = "Content-Type: " + entity.getContentType().getValue() + "\r\n\r\n";

            byte[] byteHeader = headers.getBytes();
            byte[] byteBody = EntityUtils.toByteArray(entity);
            byte[] res = new byte[byteHeader.length + byteBody.length];
            for (int i = 0; i < byteHeader.length; ++i) {
                res[i] = byteHeader[i];
            }
            for (int i = byteHeader.length; i < byteHeader.length + byteBody.length; ++i) {
                res[i] = byteBody[i - byteHeader.length];
            }
            Message message = builder.parseMessage(new ByteArrayInputStream(res));
            Body body = message.getBody();

            assertTrue(body instanceof Multipart);
            Multipart multipart = (Multipart) body;
            List<Entity> parts = multipart.getBodyParts();

            Entity sourceCodeArchiveEntity = null;

            for (Entity part : parts) {
                if (part.getFilename().equals(ExtenderClient.SOURCE_CODE_ARCHIVE_MAGIC_NAME)) {
                    sourceCodeArchiveEntity = part;
                    break;
                }
            }
            assertNotNull(sourceCodeArchiveEntity);
            assertTrue(sourceCodeArchiveEntity.getBody() instanceof BinaryBody);
            BinaryBody archiveBody = (BinaryBody)sourceCodeArchiveEntity.getBody();
            try (ZipInputStream zis = new ZipInputStream(archiveBody.getInputStream())) {
                ZipEntry zipEntry = zis.getNextEntry();
                List<String> zipEntriesFilenames = new ArrayList<>();
                while (zipEntry != null) {
                    zipEntriesFilenames.add(zipEntry.getName());
                    zipEntry = zis.getNextEntry();
                }
                assertTrue(
                    expectedFilenames.size() == zipEntriesFilenames.size() &&
                    expectedFilenames.containsAll(zipEntriesFilenames) &&
                    zipEntriesFilenames.containsAll(expectedFilenames)
                );
            }
        }
        catch (Exception e) {
            System.out.println("ERROR LOG:");
            throw e;
        }
    }
}
