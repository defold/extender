package com.defold.extender.client;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;

public class TestUtils {
    public static void writeToFile(String path, String msg) throws IOException {
        File f = new File(path);
        FileWriter fwr = new FileWriter(f);
        fwr.write(msg);
        fwr.flush();
        fwr.close();
        f.setLastModified(Instant.now().toEpochMilli() + 23);
    }
}
