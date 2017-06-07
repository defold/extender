package com.defold.extender;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class ProcessExecutor {
    private final StringBuilder output = new StringBuilder();
    private final HashMap<String, String> env = new HashMap<>();

    int execute(String command) throws IOException, InterruptedException {
        output.append(command).append("\n");

        List<String> args = ProcessExecutor.splitCommandLine(command);
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);

        Map<String, String> pbEnv = pb.environment();
        pbEnv.putAll(this.env);

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

    // We have our own splitter to ensure we get proper command line splits for both unix/win paths
    static List<String> splitCommandLine(String command) {
        // https://regex101.com/r/U905Zh/3
        Pattern pattern = Pattern.compile("\\S*?([\"'])(?:(?=(\\\\?))\\2.)*?\\1\\S*|\\S+");

        List<String> args = new ArrayList<>();
        Matcher matcher = pattern.matcher(command);

        while(matcher.find()) {
            args.add(command.substring(matcher.start(), matcher.end()));
        }

        if (args.isEmpty()) {
            args.add(command);
        }
        return args;
    }

    String getOutput() {
        return output.toString();
    }

    void putEnv(String key, String value) {
        env.put(key, value);
    }
}
