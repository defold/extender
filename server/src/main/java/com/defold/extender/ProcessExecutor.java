package com.defold.extender;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.*;

public class ProcessExecutor {
    private final StringBuffer output = new StringBuffer();
    private final HashMap<String, String> env = new HashMap<>();
    private File cwd = null;
    private boolean DM_DEBUG_COMMANDS = System.getenv("DM_DEBUG_COMMANDS") != null;
    private int commandCounter = 0;

    public int execute(String command) throws IOException, InterruptedException {
        putLog(command + "\n");

        int commandId = commandCounter++;
        long startTime = System.currentTimeMillis();
        if (DM_DEBUG_COMMANDS) {
            System.out.printf("CMD %d: %s\n", commandId, command);
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
            long duration = System.currentTimeMillis() - startTime;
            String unit = "ms";
            double divisor = 1.0;
            if (duration > 750)
            {
                unit = "s";
                divisor = 1000.0;
            }
            double t = duration / divisor;
            System.out.printf("CMD %d took %f %s\n", commandId, t, unit);
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
        FileOutputStream os = new FileOutputStream(file);
        byte[] strToBytes = getOutput().getBytes();
        os.write(strToBytes);
    }

    public void putEnv(String key, String value) {
        if (key == null || value == null) {
            putLog(String.format("ERROR: ProcessExecutor: avoided adding variable '%s': '%s' to the environment\n", key, value));
            return;
        }
        env.put(key, value);
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
            callables.add(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    processExecutor.execute(command);
                    return null;
                }
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
