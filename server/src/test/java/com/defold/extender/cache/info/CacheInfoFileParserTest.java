package com.defold.extender.cache.info;

import com.defold.extender.cache.CacheEntry;
import com.defold.extender.TestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

public class CacheInfoFileParserTest {

    @Test
    public void parseFile() throws Exception {
        CacheInfoFileParser parser = new CacheInfoFileParser();
        File file = new File(ClassLoader.getSystemResource("upload/ne-cache-info.json").toURI());

        CacheInfoWrapper info = parser.parse(file);
        List<CacheEntry> entries = info.getEntries();

        assertEquals(1, info.getVersion());
        assertEquals("sha256", info.getHashType());
        assertEquals(2, entries.size());

        CacheEntry entry1 = entries.get(0);
        assertEquals(TestUtils.CACHE_ENTRIES[0].getPath(), entry1.getPath());
        assertEquals(TestUtils.CACHE_ENTRIES[0].getKey(), entry1.getKey());
        assertTrue(entry1.isCached());

        CacheEntry entry2 = entries.get(1);
        assertEquals(TestUtils.CACHE_ENTRIES[1].getPath(), entry2.getPath());
        assertEquals(TestUtils.CACHE_ENTRIES[1].getKey(), entry2.getKey());
        assertTrue(entry2.isCached());
    }

    @Test
    public void parseInputStream() throws Exception {
        CacheInfoFileParser parser = new CacheInfoFileParser();
        File file = new File(ClassLoader.getSystemResource("upload/ne-cache-info.json").toURI());

        try (InputStream is = new FileInputStream(file)) {
            CacheInfoWrapper info = parser.parse(is);
            List<CacheEntry> entries = info.getEntries();

            assertEquals(1, info.getVersion());
            assertEquals("sha256", info.getHashType());
            assertEquals(2, entries.size());

            CacheEntry entry1 = entries.get(0);
            assertEquals(TestUtils.CACHE_ENTRIES[0].getPath(), entry1.getPath());
            assertEquals(TestUtils.CACHE_ENTRIES[0].getKey(), entry1.getKey());

            CacheEntry entry2 = entries.get(1);
            assertEquals(TestUtils.CACHE_ENTRIES[1].getPath(), entry2.getPath());
            assertEquals(TestUtils.CACHE_ENTRIES[1].getKey(), entry2.getKey());
        }
    }

    @Test
    public void parseOldFile() throws Exception {
        CacheInfoFileParser parser = new CacheInfoFileParser();
        File file = new File(ClassLoader.getSystemResource("upload/old-ne-cache-info.json").toURI());

        CacheInfoWrapper info = parser.parse(file);
        assertEquals(0, info.getVersion());
        assertNull(info.getHashType());
    }

    @Test
    public void parseOldFileHasEntries() throws Exception {
        CacheInfoFileParser parser = new CacheInfoFileParser();
        File file = new File(ClassLoader.getSystemResource("upload/old-ne-cache-info.json").toURI());

        CacheInfoWrapper info = parser.parse(file);
        List<CacheEntry> entries = info.getEntries();

        assertNotNull(entries);
        assertEquals(2, entries.size());
        assertEquals(TestUtils.CACHE_ENTRIES[0].getPath(), entries.get(0).getPath());
        assertEquals(TestUtils.CACHE_ENTRIES[0].getKey(), entries.get(0).getKey());
    }

    @Test
    public void parseCachedFalseEntry() throws Exception {
        String json = "{\"version\":1,\"hashType\":\"sha256\",\"files\":[" +
                "{\"key\":\"abc\",\"path\":\"a/b.jar\",\"cached\":false}" +
                "]}";
        CacheInfoFileParser parser = new CacheInfoFileParser();

        CacheInfoWrapper info = parser.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        assertEquals(1, info.getEntries().size());
        assertFalse(info.getEntries().get(0).isCached());
    }

    @Test
    public void parseEmptyFilesArray() throws Exception {
        String json = "{\"version\":1,\"hashType\":\"sha256\",\"files\":[]}";
        CacheInfoFileParser parser = new CacheInfoFileParser();

        CacheInfoWrapper info = parser.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        assertNotNull(info.getEntries());
        assertTrue(info.getEntries().isEmpty());
    }

    @Test
    public void parseInvalidJsonThrowsException() {
        CacheInfoFileParser parser = new CacheInfoFileParser();
        byte[] invalid = "not valid json".getBytes(StandardCharsets.UTF_8);

        assertThrows(Exception.class, () -> parser.parse(new ByteArrayInputStream(invalid)));
    }

    @Test
    public void parseCachedNullThrowsMismatchedInputException() throws IOException {
        InputStream is = new FileInputStream(new File("test-data/cache/test1.json"));
        CacheInfoFileParser parser = new CacheInfoFileParser();

        assertDoesNotThrow(() -> parser.parse(is));
    }

    @Test
    public void parseCachedMissingFieldThrowsMismatchedInputException() throws FileNotFoundException {
        InputStream is = new FileInputStream(new File("test-data/cache/test2.json"));
        CacheInfoFileParser parser = new CacheInfoFileParser();

        assertDoesNotThrow(() -> parser.parse(is));
    }
}
