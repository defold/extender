package com.defold.extender.cache.info;

import com.defold.extender.cache.CacheEntry;
import com.defold.extender.TestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.FileInputStream;
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

        CacheEntry entry2 = entries.get(1);
        assertEquals(TestUtils.CACHE_ENTRIES[1].getPath(), entry2.getPath());
        assertEquals(TestUtils.CACHE_ENTRIES[1].getKey(), entry2.getKey());
    }

    @Test
    public void parseInputStream() throws Exception {
        CacheInfoFileParser parser = new CacheInfoFileParser();
        File file = new File(ClassLoader.getSystemResource("upload/ne-cache-info.json").toURI());

        CacheInfoWrapper info = parser.parse(new FileInputStream(file));
        List<CacheEntry> entries = info.getEntries();

        assertEquals(1, info.getVersion());
        assertEquals("sha256", info.getHashType());

        CacheEntry entry1 = entries.get(0);
        assertEquals(TestUtils.CACHE_ENTRIES[0].getPath(), entry1.getPath());
        assertEquals(TestUtils.CACHE_ENTRIES[0].getKey(), entry1.getKey());

        CacheEntry entry2 = entries.get(1);
        assertEquals(TestUtils.CACHE_ENTRIES[1].getPath(), entry2.getPath());
        assertEquals(TestUtils.CACHE_ENTRIES[1].getKey(), entry2.getKey());
    }

    @Test
    public void parseOldFile() throws Exception {
        CacheInfoFileParser parser = new CacheInfoFileParser();
        File file = new File(ClassLoader.getSystemResource("upload/old-ne-cache-info.json").toURI());

        CacheInfoWrapper info = parser.parse(file);
        assertEquals(0, info.getVersion());
        assertEquals(null, info.getHashType());
    }
}
