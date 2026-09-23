package com.defold.extender.process;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.util.*;

import com.defold.extender.ExtenderException;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ProcessExecutor {
    private static final long FORCE_KILL_DELAY_SECONDS = 5;
    private static final ScheduledThreadPoolExecutor WATCHDOG = newWatchdog();

    /**
     * A cancelled watchdog holds its {@link Process} until its original deadline unless the
     * queue drops it, and there is one per subprocess; {@code Executors.newSingle...} returns a
     * wrapper that hides {@code setRemoveOnCancelPolicy}, so the pool is built directly.
     */
    private static ScheduledThreadPoolExecutor newWatchdog() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "process-watchdog");
            t.setDaemon(true);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private final StringBuffer output = new StringBuffer();
    private final Map<String, String> env = new HashMap<>();
    private final ProcessSandbox sandbox;
    private volatile SandboxPolicy policy = SandboxPolicy.toolchain();
    /** Set by {@link #setCommandTimeout}; null = the sandbox's limit for the command's policy. */
    private volatile Long commandTimeoutMillis;
    private File cwd = null;
    private boolean DM_DEBUG_COMMANDS = System.getenv("DM_DEBUG_COMMANDS") != null;
    private static AtomicInteger commandCounter = new AtomicInteger(0);

    public ProcessExecutor() {
        this(ProcessSandbox.current());
    }

    public ProcessExecutor(ProcessSandbox sandbox) {
        this.sandbox = sandbox;
    }

    public int execute(String command) throws IOException, InterruptedException {
        return execute(command, null);
    }

    public int execute(String command, SandboxPolicy policy) throws IOException, InterruptedException {
        // To avoid an issue where an extra space was interpreted as an argument
        List<String> args = CommandLineTokenizer.parse(command);
        return execute(args, policy);
    }

    public int execute(List<String> args) throws IOException, InterruptedException {
        return execute(args, null);
    }

    /**
     * Runs one command. The log always shows the command as written; when the sandbox is
     * enabled the process actually started is the launcher wrapping it.
     *
     * @param policy per-call override, or null for this executor's default policy
     */
    public int execute(List<String> args, SandboxPolicy policy) throws IOException, InterruptedException {
        putLog(String.join(" ", args) + "\n");

        int commandId = commandCounter.incrementAndGet();
        long startTime = System.currentTimeMillis();
        SandboxPolicy effectivePolicy = policy != null ? policy : this.policy;
        ProcessSandbox.Launch launch;
        try {
            launch = sandbox.prepare(args, cwd, env, effectivePolicy);
        } catch (IOException e) {
            throw new ProcessLaunchException("Cannot start " + String.join(" ", args) + ": " + e.getMessage(), e);
        }

        ProcessBuilder pb = new ProcessBuilder(launch.argv());
        if (cwd != null) {
            pb.directory(cwd);
        }
        pb.redirectErrorStream(true);

        Map<String, String> pbEnv = pb.environment();
        if (launch.env() != null) {
            pbEnv.clear();
            pbEnv.putAll(launch.env());
        } else {
            // env == null means "inherit as before", i.e. the sandbox is disabled. The policy's
            // own variables are hardening that does not depend on it (a pod's git must not reach
            // the keychain either way), so they are applied here too - prepare() merges them
            // into the scrubbed environment on the other branch.
            pbEnv.putAll(this.env);
            pbEnv.putAll(effectivePolicy.env());
        }

        if (DM_DEBUG_COMMANDS) {
            StringBuffer debugBuffer = new StringBuffer();
            debugBuffer.append(String.format("CMD %d: %s\n", commandId, String.join(" ", args)));
            if (launch.env() != null) {
                debugBuffer.append(String.format("\tSandboxed: %s\n", String.join(" ", launch.argv())));
            }
            debugBuffer.append(String.format("\tWorking dir: %s\n", this.cwd == null ? "(null)" : this.cwd.toString()));
            debugBuffer.append("\tEnvironment:\n");
            for (Map.Entry<String, String> envEntry : this.env.entrySet()) {
                debugBuffer.append(String.format("\t%s=%s\n", envEntry.getKey(), envEntry.getValue()));
            }
            System.out.println(debugBuffer.toString());
        }
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new ProcessLaunchException("Cannot start " + String.join(" ", args) + ": " + e.getMessage(), e);
        }

        Long override = this.commandTimeoutMillis;
        long timeout = override != null ? override : sandbox.commandTimeoutMillis(effectivePolicy);
        AtomicBoolean timedOut = new AtomicBoolean(false);
        ScheduledFuture<?> watchdog = null;
        if (timeout > 0) {
            watchdog = WATCHDOG.schedule(() -> {
                timedOut.set(true);
                terminate(p);
            }, timeout, TimeUnit.MILLISECONDS);
        }

        int exitValue;
        try {
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

            exitValue = p.waitFor();
        } finally {
            if (watchdog != null) {
                watchdog.cancel(false);
            }
        }

        if (DM_DEBUG_COMMANDS) {
            StringBuffer debugBuffer = new StringBuffer();
            debugBuffer.append(String.format("CMD %d: %s\n", commandId, String.join(" ", args)));
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

        // Only a command that actually failed can have been the one the watchdog killed: one
        // that finished 0 in the same instant the deadline expired succeeded, and reporting it
        // as a timeout would fail the whole job over a race.
        if (timedOut.get() && exitValue != 0) {
            String message = String.format("Command timed out after %d ms: %s\n", timeout, String.join(" ", args));
            putLog(message);
            throw new CommandTimeoutException(output.toString());
        }

        // note: a negative exit value means the process was terminated by a signal,
        // which is a failure just like a positive exit code
        if (exitValue != 0) {
            throw new IOException(output.toString());
        }

        return exitValue;
    }

    /**
     * Stops a command that overran its timeout. With the launcher, SIGTERM makes it kill its
     * whole process tree; without it (macOS, tests) the descendants are signalled directly so
     * nothing keeps the stdout pipe, and therefore the read loop, open.
     */
    private static void terminate(Process p) {
        List<ProcessHandle> descendants = p.descendants().toList();
        p.destroy();
        descendants.forEach(ProcessHandle::destroy);
        WATCHDOG.schedule(() -> {
            descendants.forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }, FORCE_KILL_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    public String getOutput() {
        return output.toString();
    }

    /** Writes the output to {@code file}, whose directory must resolve inside {@code jobDir}. */
    public void writeLog(File jobDir, File file) throws IOException {
        JobFiles.write(jobDir.toPath(), file.toPath(), getOutput().getBytes());
    }

    public void putEnv(String key, String value) {
        if (key == null || value == null) {
            putLog(String.format("ERROR: ProcessExecutor: avoided adding variable '%s': '%s' to the environment\n", key, value));
            return;
        }
        env.put(key, value);
    }

    public void putEnv(Map<String, String> inputEnv) {
        for (Map.Entry<String, String> entry : inputEnv.entrySet()) {
            putEnv(entry.getKey(), entry.getValue());
        }
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public void setCwd(File cwd) {
        this.cwd = cwd;
    }

    public File getCwd() {
        return cwd;
    }

    /** Default policy for commands run through this executor; toolchain (no network) unless changed. */
    public void setPolicy(SandboxPolicy policy) {
        this.policy = Objects.requireNonNull(policy);
    }

    public SandboxPolicy getPolicy() {
        return policy;
    }

    /** Wall-clock limit per command in milliseconds, for every policy; 0 disables it. */
    public void setCommandTimeout(long millis) {
        this.commandTimeoutMillis = millis;
    }

    /** The limit a command run under this executor's default policy gets. */
    public long getCommandTimeout() {
        Long override = this.commandTimeoutMillis;
        return override != null ? override : sandbox.commandTimeoutMillis(policy);
    }

    public ProcessSandbox getSandbox() {
        return sandbox;
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
        executeCommands(processExecutor, commands, null, null);
    }

    // onCommandComplete is invoked concurrently from the pool threads, once per successful command
    public static void executeCommands(ProcessExecutor processExecutor, List<String> commands, Runnable onCommandComplete) throws IOException, InterruptedException, ExtenderException {
        executeCommands(processExecutor, commands, onCommandComplete, null);
    }

    /**
     * @param policy per-call sandbox policy for every command, or null for the executor's default
     */
    public static void executeCommands(ProcessExecutor processExecutor, List<String> commands, Runnable onCommandComplete, SandboxPolicy policy) throws IOException, InterruptedException, ExtenderException {
        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        List<Callable<Void>> callables = new ArrayList<>();
        for (String command : commands) {
            callables.add(() -> {
                processExecutor.execute(command, policy);
                if (onCommandComplete != null) {
                    onCommandComplete.run();
                }
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
