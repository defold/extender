package com.defold.extender.process;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.util.*;
import java.util.stream.Collectors;

import com.defold.extender.ExtenderException;

import java.util.concurrent.*;

public class ProcessExecutor {
    private final StringBuffer output = new StringBuffer();
    private final Map<String, String> env = new HashMap<>();
    private File cwd = null;
    private boolean DM_DEBUG_COMMANDS = System.getenv("DM_DEBUG_COMMANDS") != null;
    private int commandCounter = 0;

    public int execute(String command) throws IOException, InterruptedException {
        // To avoid an issue where an extra space was interpreted as an argument
        List<String> args = Arrays.stream(command.split(" "))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        return execute(args);
    }

    public int execute(List<String> args) throws IOException, InterruptedException {
        putLog(String.join(" ", args) + "\n");

        int commandId = commandCounter++;
        long startTime = System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder(args);
        if (cwd != null) {
            pb.directory(cwd);
        }
        pb.redirectErrorStream(true);

        Map<String, String> pbEnv = pb.environment();
        pbEnv.putAll(this.env);

        Process p = pb.start();

        byte[] buf = new byte[16 * 1024];
        InputStream is = p.getInputStream();

        int n;
        do {
            n = is.read(buf);
            if (n > 0) {
                putLog(new String(buf, 0, n));
            }
        }
        while (n > 0);

        int exitValue = p.waitFor();

        if (DM_DEBUG_COMMANDS) {
            StringBuffer debugBuffer = new StringBuffer();
            debugBuffer.append(String.format("CMD %d: %s\n", commandId, String.join(" ", args)));
            debugBuffer.append(String.format("\tWorking dir: \n", this.cwd == null ? "(null)" : this.cwd.toString()));
            debugBuffer.append("\tEnvrionment:\n");
            for (Map.Entry<String, String> envEntry : this.env.entrySet()) {
                debugBuffer.append(String.format("\t%s=%s\n", envEntry.getKey(), envEntry.getValue()));
            }
            debugBuffer.append(String.format("\tExit code: %d", exitValue));
            long duration = System.currentTimeMillis() - startTime;
            String unit = "ms";
            double divisor = 1.0;
            if (duration > 750)
            {
                unit = "s";
                divisor = 1000.0;
            }
            double t = duration / divisor;
            debugBuffer.append(String.format("\tCommand took %f %s", t, unit));
            System.out.println(debugBuffer.toString());
        }

        if (exitValue > 0) {
            throw new IOException(output.toString());
        }

        return exitValue;
    }

    public String getOutput() {
        return output.toString();
    }

    public void writeLog(File file) throws IOException {
        try (FileOutputStream os = new FileOutputStream(file)) {
            byte[] strToBytes = getOutput().getBytes();
            os.write(strToBytes);
        }
    }

    public void putEnv(String key, String value) {
        if (key == null || value == null) {
            putLog(String.format("ERROR: ProcessExecutor: avoided adding variable '%s': '%s' to the environment\n", key, value));
            return;
        }
        env.put(key, value);
    }

    public void putEnv(Map<String, String> inputEnv) throws NullPointerException {
        env.putAll(inputEnv);
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public void setCwd(File cwd) {
        this.cwd = cwd;
    }

    public void putLog(String msg) {
        // OOM can happen when running tests with org.gradle.logging.level=debug
        try {
            output.append(msg);
        }
        catch (OutOfMemoryError e) {
            int l = output.length();
            output.delete(0, l / 2);
            output.insert(0, "(truncated)\n");
            output.append(msg);
        }
    }

    public static void executeCommands(ProcessExecutor processExecutor, List<String> commands) throws IOException, InterruptedException, ExtenderException {
        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        List<Callable<Void>> callables = new ArrayList<>();
        for (String command : commands) {
            callables.add(() -> {
                processExecutor.execute(command);
                return null;
            });
        }
        List<Future<Void>> futures = executor.invokeAll(callables);
        try {
            for (Future<Void> future : futures) {
                future.get();
            }
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException) {
                throw (IOException)e.getCause();
            } else if (e.getCause() instanceof InterruptedException) {
                throw (InterruptedException)e.getCause();
            } else {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                e.printStackTrace(pw);
                throw new ExtenderException(sw.toString());
            }
        } finally {
            executor.shutdown();
        }
    }
}
