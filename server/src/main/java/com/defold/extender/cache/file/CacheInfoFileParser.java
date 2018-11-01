package com.defold.extender.cache.file;

import com.defold.extender.cache.CacheEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Service
public class CacheInfoFileParser {

    private ObjectMapper objectMapper = new ObjectMapper();

    public List<CacheEntry> parse(final File file) throws IOException {
        CacheInfoWrapper wrapper = objectMapper.readerFor(CacheInfoWrapper.class).readValue(file);
        return wrapper.getEntries();
    }

    public List<CacheEntry> parse(final InputStream inputStream) throws IOException {
        CacheInfoWrapper wrapper = objectMapper.readerFor(CacheInfoWrapper.class).readValue(inputStream);
        return wrapper.getEntries();
    }
}
