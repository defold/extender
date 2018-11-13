package com.defold.extender.cache.info;

import com.defold.extender.cache.CacheEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

@Service
public class CacheInfoFileWriter {

    private ObjectMapper objectMapper = new ObjectMapper();

    public void write(List<CacheEntry> entries, OutputStream outputStream) throws IOException {
        objectMapper.writer().writeValue(outputStream, new CacheInfoWrapper(entries));
    }
}
