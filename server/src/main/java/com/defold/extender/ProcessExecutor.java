package com.defold.extender;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.util.*;
import java.util.stream.Collectors;

public class ProcessExecutor {
    private final StringBuilder output = new StringBuilder();
    private final HashMap<String, String> env = new HashMap<>();
    private File cwd = null;
    private boolean DM_DEBUG_COMMANDS = true;//System.getenv("DM_DEBUG_COMMANDS") != null;

    public int execute(String command) throws IOException, InterruptedException {
        output.append(command).append("\n");

        if (DM_DEBUG_COMMANDS) {
            System.out.println("CMD: " + command);
        }

        // To avoid an issue where an extra space was interpreted as an argument
        List<String> args = Arrays.stream(command.split(" "))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        ProcessBuilder pb = new ProcessBuilder(args);
        if (cwd != null ) {
            pb.directory(cwd);
        }
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

    public String getOutput() {
        return output.toString();
    }

    public void writeLog(File file) throws IOException {
        FileOutputStream os = new FileOutputStream(file);
        byte[] strToBytes = getOutput().getBytes();
        os.write(strToBytes);
    }

    public void putEnv(String key, String value) {
        env.put(key, value);
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public void setCwd(File cwd) {
        this.cwd = cwd;
    }
}
