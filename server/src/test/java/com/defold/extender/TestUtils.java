package com.defold.extender;

import com.defold.extender.cache.CacheEntry;

public class TestUtils {
    public static final CacheEntry[] MOCK_CACHE_ENTRIES = {
            new CacheEntry("L1TgJED/SWKUvscZKt6dGq3FrCzn+eVkKyreZDoH32w=", "path/to/file1", true),
            new CacheEntry("vaxrC39vSgxvEcRxP/KNwp+g9ExcZjpSgzsrVi1nvjQ=", "another/path/to/file2", true),
            new CacheEntry("dfUd7asqp8gn9wDmOLBoSDr/UDEUBz78pQmhyIaApP8=", "last/path/for/file3", true)
    };

    public static final CacheEntry[] CACHE_ENTRIES = {
            new CacheEntry("LYwvbZeMohcStfbeNsnTH6jpak+l2P+LAYjfuefBcbs=", "dir/test1.txt", true),
            new CacheEntry("sP8bj1xw4xwbBaetvug4PDzHuK8ukoKiJdc9EVXgc28=", "dir2/test2.txt", true)
    };

}
