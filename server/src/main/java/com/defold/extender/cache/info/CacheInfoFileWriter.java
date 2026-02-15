package com.defold.extender.cache.info;

import com.defold.extender.cache.CacheEntry;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

@Service
public class CacheInfoFileWriter {

    private ObjectMapper objectMapper = new ObjectMapper();

    public void write(int version, String hashType, List<CacheEntry> entries, OutputStream outputStream) throws IOException {
        objectMapper.writer().writeValue(outputStream, new CacheInfoWrapper(version, hashType, entries));
    }
}
