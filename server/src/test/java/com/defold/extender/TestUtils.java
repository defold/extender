package com.defold.extender;

import com.defold.extender.cache.CacheEntry;

public class TestUtils {
    public static final CacheEntry[] MOCK_CACHE_ENTRIES = {
            new CacheEntry("L1TgJED_SWKUvscZKt6dGq3FrCzn-eVkKyreZDoH32w", "path/to/file1", true),
            new CacheEntry("vaxrC39vSgxvEcRxP_KNwp-g9ExcZjpSgzsrVi1nvjQ", "another/path/to/file2", true),
            new CacheEntry("dfUd7asqp8gn9wDmOLBoSDr_UDEUBz78pQmhyIaApP8", "last/path/for/file3", true)
    };

    public static final CacheEntry[] CACHE_ENTRIES = {
            new CacheEntry("LYwvbZeMohcStfbeNsnTH6jpak-l2P-LAYjfuefBcbs", "dir/test1.txt", true),
            new CacheEntry("fzthrrNKjqFcZ1_92qavam-90DHtl4bcsrNbNRoTKzE", "dir2/test2.txt", true)
    };

}
