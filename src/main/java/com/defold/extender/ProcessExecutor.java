package com.defold.extender;

import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ProcessExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessExecutor.class);
    private final StringBuilder output = new StringBuilder();

    void log(String msg) {
    	output.append(msg).append("\n");

    	LOGGER.debug(msg + "\n");
    }

    int execute(String command) throws IOException, InterruptedException {
        log(command);

        String[] args = command.split(" ");

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        byte[] buf = new byte[16 * 1024];
        InputStream is = p.getInputStream();

        StringBuilder sb = new StringBuilder();

        int n;
        do {
            n = is.read(buf);
            if (n > 0) {
                sb.append(new String(buf, 0, n));
            }
        }
        while (n > 0);

        int exitValue = p.waitFor();

        log(sb.toString());

        if (exitValue > 0) {
            throw new IOException(sb.toString());
        }

        return exitValue;
    }

    String getOutput() {
        return output.toString();
    }
}
