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
import java.util.*;

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
    public void testBuild() throws IOException, InterruptedException, ExtenderException {
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


    @Test
    public void testFilterFiles() throws IOException, InterruptedException, ExtenderException {

		String[] arr = { 
		    "a.cpp", "a.inl", "a.h", 
		    "a.cxx", "a.hpp",
		    "a.CPP", "a.hxx",
		    "a.CC", "a.CXX",
		    "a.txt", "a.o", "a.obj",
		    "a.cpp.bak", "a.cpp_",
		    "a.m", "a.bogus", "a.mm"
		};

		Collection<File> src = new ArrayList<File>();
		for ( String k : arr ) {
			src.add( new File(k) );
		}

		String[] expectednames = { 
		    "a.cpp", "a.cxx",
		    "a.CPP", "a.CC", "a.CXX",
		    "a.m", "a.mm"
		};

		List<File> expected = new ArrayList<File>();
		for ( String k : expectednames ) {
			expected.add( new File(k) );
		}

    	List<File> result = Extender.filterFiles(src, "(?i).*(.cpp|.c|.cc|.cxx|.c++|.mm|.m)");

        assertEquals( expected, result );
    }

    @Test
    public void testMergeList() throws IOException, InterruptedException, ExtenderException {
		String[] a = {  "1", "2", "2", "3", "4" };
		String[] b = {  "3", "5", "4", "5" };

        List<String> c = Extender.mergeLists(Arrays.asList(a), Arrays.asList(b));

		String[] expected = { "1", "2", "3", "4", "5" };

        assertEquals( expected, c.toArray() );
    }


    @Test
    public void testMergeContext() throws IOException, InterruptedException, ExtenderException {
        Map<String, Object> a = new HashMap<String, Object>();
        Map<String, Object> b = new HashMap<String, Object>();

        String[] a_frameworks = {  "a", "b", "b", "c" };
        a.put("frameworks", Arrays.asList(a_frameworks));
        String[] a_defines = {  "A", "B" };
        a.put("defines", Arrays.asList(a_defines));

        String[] b_frameworks = {  "a", "d" };
        b.put("frameworks", Arrays.asList(b_frameworks));

        Map<String, Object> result = Extender.mergeContexts(a, b);

        Map<String, Object> expected = new HashMap<String, Object>();
        String[] expected_frameworks = {  "a", "b", "c", "d" };
        expected.put("frameworks", Arrays.asList(expected_frameworks));
        String[] expected_defines = {  "A", "B" };
        expected.put("defines", Arrays.asList(expected_defines));

        assertEquals( expected, result );
    }

    @Test
    public void testListTypes()
    {
        List<Object> a = new ArrayList<Object>();
        a.add("a");
        a.add("b");
        a.add("c");
        a.add("d");
    	assertTrue( Extender.isListOfStrings(a) );


        List<Object> b = new ArrayList<Object>();
        b.add("a");
        b.add("b");
        b.add(1);
        b.add(2);
    	assertTrue( !Extender.isListOfStrings(b) );
    }

    @Test
    public void testCollectLibraries()
    {
    	// The folder contains a library and a text file
    	List<String> result = Extender.collectLibraries( new File("test-data/ext/lib/x86-osx"), "lib(.+).a" );

		String[] expected = { "alib" };
        assertEquals( expected, result.toArray() );
    }

}
