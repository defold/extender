package com.defold.extender.cache;

import com.defold.extender.cache.file.CacheFileWriter;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.junit.Assert.*;

public class CacheFileWriterTest {

    private static final CacheEntry[] ENTRIES = {
            new CacheEntry("foo/bar.jar", "675fef8ef8", true),
            new CacheEntry("dir/asdf.zip", "234bc895fe73", false)
    };

    private static final String JSON = "{\"files\":[{\"key\":\"foo/bar.jar\",\"path\":\"675fef8ef8\",\"cached\":true}," +
            "{\"key\":\"dir/asdf.zip\",\"path\":\"234bc895fe73\",\"cached\":false}]}";

    @Test
    public void write() throws Exception {
        CacheFileWriter cacheFileWriter = new CacheFileWriter();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        cacheFileWriter.write(Arrays.asList(ENTRIES), outputStream);

        ByteArrayInputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
        byte[] buffer = new byte[inputStream.available()];
        inputStream.read(buffer, 0, inputStream.available());

        assertEquals(JSON, new String(buffer));
    }
}