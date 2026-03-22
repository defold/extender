package com.defold.extender.cache.info;

import com.defold.extender.cache.CacheEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

public class CacheInfoWrapperTest {

    @Test
    public void gettersReturnConstructedValues() {
        List<CacheEntry> entries = Arrays.asList(
                new CacheEntry("abc123", "foo/bar.jar", true),
                new CacheEntry("def456", "baz/qux.zip", false)
        );

        CacheInfoWrapper wrapper = new CacheInfoWrapper(1, "sha256", entries);

        assertEquals(1, wrapper.getVersion());
        assertEquals("sha256", wrapper.getHashType());
        assertSame(entries, wrapper.getEntries());
    }

    @Test
    public void defaultConstructorProducesZeroVersionAndNullFields() {
        CacheInfoWrapper wrapper = new CacheInfoWrapper();

        assertEquals(0, wrapper.getVersion());
        assertNull(wrapper.getHashType());
        assertNull(wrapper.getEntries());
    }

    @Test
    public void emptyEntriesList() {
        CacheInfoWrapper wrapper = new CacheInfoWrapper(2, "md5", Collections.emptyList());

        assertEquals(2, wrapper.getVersion());
        assertEquals("md5", wrapper.getHashType());
        assertNotNull(wrapper.getEntries());
        assertTrue(wrapper.getEntries().isEmpty());
    }

    @Test
    public void nullHashType() {
        CacheInfoWrapper wrapper = new CacheInfoWrapper(1, null, Collections.emptyList());

        assertNull(wrapper.getHashType());
    }
}
