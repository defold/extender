package com.defold.extender;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;

class ProcessExecutor {
    private final StringBuilder output = new StringBuilder();

    int execute(String command) throws IOException, InterruptedException {
        output.append(command).append("\n");

        // To avoid an issue where an extra space was interpreted as an argument
        ArrayList<String> args = new ArrayList<>(Arrays.asList(command.split(" ")));
        args.removeAll(Arrays.asList(""));

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

        output.append(sb.toString()).append("\n");

        if (exitValue > 0) {
            throw new IOException(sb.toString());
        }

        return exitValue;
    }

    String getOutput() {
        return output.toString();
    }
}
