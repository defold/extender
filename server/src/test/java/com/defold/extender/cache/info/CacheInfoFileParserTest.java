package com.defold.extender.cache.info;

import com.defold.extender.cache.CacheEntry;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;

import static org.junit.Assert.*;

public class CacheInfoFileParserTest {

    @Test
    public void parseFile() throws Exception {
        CacheInfoFileParser parser = new CacheInfoFileParser();
        File file = new File(ClassLoader.getSystemResource("upload/ne-cache-info.json").toURI());
        List<CacheEntry> entries = parser.parse(file);

        assertEquals(2, entries.size());

        CacheEntry entry1 = entries.get(0);
        assertEquals("dir/test1.txt", entry1.getPath());
        assertEquals("LYwvbZeMohcStfbeNsnTH6jpak-l2P-LAYjfuefBcbs", entry1.getKey());

        CacheEntry entry2 = entries.get(1);
        assertEquals("dir2/test2.txt", entry2.getPath());
        assertEquals("fzthrrNKjqFcZ1_92qavam-90DHtl4bcsrNbNRoTKzE", entry2.getKey());
    }

    @Test
    public void parseInputStream() throws Exception {
        CacheInfoFileParser parser = new CacheInfoFileParser();
        File file = new File(ClassLoader.getSystemResource("upload/ne-cache-info.json").toURI());
        List<CacheEntry> entries = parser.parse(new FileInputStream(file));

        assertEquals(2, entries.size());

        CacheEntry entry1 = entries.get(0);
        assertEquals("dir/test1.txt", entry1.getPath());
        assertEquals("LYwvbZeMohcStfbeNsnTH6jpak-l2P-LAYjfuefBcbs", entry1.getKey());

        CacheEntry entry2 = entries.get(1);
        assertEquals("dir2/test2.txt", entry2.getPath());
        assertEquals("fzthrrNKjqFcZ1_92qavam-90DHtl4bcsrNbNRoTKzE", entry2.getKey());
    }
}