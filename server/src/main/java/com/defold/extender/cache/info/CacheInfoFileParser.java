package com.defold.extender.cache.info;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

@Service
public class CacheInfoFileParser {

    private ObjectMapper objectMapper = new ObjectMapper();

    public CacheInfoWrapper parse(final File file) throws IOException {
        return objectMapper.readerFor(CacheInfoWrapper.class).readValue(file);
    }

    public CacheInfoWrapper parse(final InputStream inputStream) throws IOException {
        return objectMapper.readerFor(CacheInfoWrapper.class).readValue(inputStream);
    }
}
