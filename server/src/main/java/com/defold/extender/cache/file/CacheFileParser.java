package com.defold.extender.cache.file;

import com.defold.extender.cache.CacheEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Service
public class CacheFileParser {

    private ObjectMapper objectMapper = new ObjectMapper();

    public List<CacheEntry> parse(final File file) throws IOException {
        CacheWrapper wrapper = objectMapper.readerFor(CacheWrapper.class).readValue(file);
        return wrapper.getEntries();
    }

    public List<CacheEntry> parse(final InputStream inputStream) throws IOException {
        CacheWrapper wrapper = objectMapper.readerFor(CacheWrapper.class).readValue(inputStream);
        return wrapper.getEntries();
    }
}
