package com.defold.extender;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.embedded.EmbeddedWebApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringBootTest(webEnvironment= SpringBootTest.WebEnvironment.RANDOM_PORT, value = "extender.defoldSdkPath = test-data/sdk")
public class ExtenderTest {

    @Autowired
    EmbeddedWebApplicationContext server;

    @Value("${local.server.port}")
    int port;

    @Test
    public void testBuild() throws IOException, InterruptedException {
        Extender extender = new Extender("x86-osx", new File("test-data/ext"), new File("test-data/sdk/a/defoldsdk"));
        File engine = extender.buildEngine();
        assertTrue(engine.isFile());
        extender.dispose();
    }

    @Test
    public void testRemoteBuild() throws IOException {
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        File root = new File("test-data");
        for (String s : new String[] {"ext/ext.manifest", "ext/src/test_ext.cpp", "ext/include/test_ext.h", "ext/lib/x86-osx/libalib.a"}) {
            builder.addBinaryBody(s, new File(root, s));
        }

        HttpEntity entity = builder.build();
        String url = String.format("http://localhost:%d/build/x86-osx/a", port);
        HttpPost request = new HttpPost(url);

        request.setEntity(entity);

        try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
            HttpResponse response = client.execute(request);
            assertEquals(200, response.getStatusLine().getStatusCode());
        }
    }
}
