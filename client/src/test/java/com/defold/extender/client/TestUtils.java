package com.defold.extender.client;

import java.io.File;
import java.nio.file.attribute.FileTime;
import java.nio.file.Files;
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
        Files.setLastModifiedTime(f.toPath(), FileTime.fromMillis(Instant.now().toEpochMilli() + 23));
    }
}
