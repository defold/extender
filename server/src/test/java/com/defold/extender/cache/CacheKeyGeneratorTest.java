package com.defold.extender.cache;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;

import static org.junit.Assert.*;

public class CacheKeyGeneratorTest {

    private String generate(final String filename) throws URISyntaxException, IOException {
        CacheKeyGenerator cacheKeyGenerator = new CacheKeyGenerator();
        File file = new File(ClassLoader.getSystemResource(filename).toURI());
        return cacheKeyGenerator.generate(file);
    }

    @Test
    public void generateKeyForFile() throws IOException, URISyntaxException {
        assertEquals("LYwvbZeMohcStfbeNsnTH6jpak+l2P+LAYjfuefBcbs=", generate("upload/dir/test1.txt"));
    }

    @Test
    public void generateKeyForAnotherFile() throws IOException, URISyntaxException {
        assertEquals("sP8bj1xw4xwbBaetvug4PDzHuK8ukoKiJdc9EVXgc28=", generate("upload/dir2/test2.txt"));
    }
}