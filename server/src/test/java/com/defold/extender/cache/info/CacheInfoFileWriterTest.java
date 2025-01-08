package com.defold.extender.cache.info;

import com.defold.extender.cache.CacheEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

public class CacheInfoFileWriterTest {

    private static final CacheEntry[] ENTRIES = {
            new CacheEntry("675fef8ef8", "foo/bar.jar", true),
            new CacheEntry("234bc895fe73", "dir/asdf.zip", false)
    };

    private static final String JSON = "{\"version\":1,\"hashType\":\"sha256\",\"files\":[{\"key\":\"675fef8ef8\",\"path\":\"foo/bar.jar\",\"cached\":true}," +
            "{\"key\":\"234bc895fe73\",\"path\":\"dir/asdf.zip\",\"cached\":false}]}";

    @Test
    public void write() throws Exception {
        CacheInfoFileWriter cacheInfoFileWriter = new CacheInfoFileWriter();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        cacheInfoFileWriter.write(1, "sha256", Arrays.asList(ENTRIES), outputStream);

        ByteArrayInputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
        byte[] buffer = new byte[inputStream.available()];
        inputStream.read(buffer, 0, inputStream.available());

        assertEquals(JSON, new String(buffer));
    }
}
