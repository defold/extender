package com.defold.extender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.defold.extender.process.ProcessUtils;

public class AsyncBuilderErrorFileTest {

    @TempDir
    File tmp;

    private String read(File file) throws IOException {
        return file.exists() ? Files.readString(file.toPath(), StandardCharsets.UTF_8) : "";
    }

    @Test
    public void outputOfASeparateExecutorIsWrittenInFull() throws Exception {
        File errorFile = new File(tmp, "error.txt");
        ExtenderException e = null;
        try {
            ProcessUtils.execCommand(List.of("sh", "-c", "printf 'FIRST-%s\\n' GRADLE-LINE; head -c 100000 /dev/zero | tr '\\0' 'g'; echo; exit 1"), null, null);
        } catch (ExtenderException ex) {
            e = ex;
        }

        AsyncBuilder.writeExceptionOutputToFile("extender log\n", e, errorFile);

        String written = read(errorFile);
        assertTrue(written.contains("FIRST-GRADLE-LINE"), "head of the output must not be lost");
        assertTrue(written.length() > 100_000);
        assertFalse(e.getCause().getMessage().contains("FIRST-GRADLE-LINE"), "the exception message only holds the tail");
    }

    @Test
    public void outputAlreadyInTheExtenderLogIsNotDuplicated() throws Exception {
        File errorFile = new File(tmp, "error.txt");
        String extenderLog = "cc -c main.cpp\nmain.cpp:1: error\n";
        ExtenderException e = new ExtenderException(new IOException("short"), extenderLog);

        AsyncBuilder.writeExceptionOutputToFile(extenderLog + "more output after the failure\n", e, errorFile);

        assertEquals("", read(errorFile));
    }

    @Test
    public void plainMessageIsLeftToTheStackTrace() throws Exception {
        File errorFile = new File(tmp, "error.txt");
        AsyncBuilder.writeExceptionOutputToFile(null, new ExtenderException("Only one app.manifest allowed!"), errorFile);
        assertEquals("", read(errorFile));
    }
}
