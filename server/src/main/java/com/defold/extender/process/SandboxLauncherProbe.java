package com.defold.extender.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs {@code extender-sandbox --probe} once at startup and interprets the answer. The Linux
 * launcher reports {@code landlock_abi=<n> seccomp=<yes|no>}, the darwin launcher
 * {@code seatbelt=<yes|no> ...}.
 *
 * Landlock ABI 3 (kernel 6.2) is the first that mediates truncate(2); below it a path-based
 * truncate of a DAC-writable file under a read-only grant goes through, so older kernels do
 * not count as enforceable.
 */
final class SandboxLauncherProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxLauncherProbe.class);
    private static final Pattern LANDLOCK = Pattern.compile("landlock_abi=(\\d+)\\s+seccomp=(yes|no)");
    private static final Pattern SEATBELT = Pattern.compile("seatbelt=(yes|no)");
    private static final long PROBE_TIMEOUT_SECONDS = 10;
    static final int MIN_LANDLOCK_ABI = 3;

    private SandboxLauncherProbe() {}

    /**
     * @param description the probe line as reported, for the startup log
     * @param enforceable every kernel layer the launcher needs is available
     * @param missing     what is missing when not enforceable, else empty
     */
    record Result(String description, boolean enforceable, String missing) {}

    static Result parse(String probeOutput) {
        String output = probeOutput == null ? "" : probeOutput.strip();
        Matcher landlock = LANDLOCK.matcher(output);
        if (landlock.find()) {
            int abi;
            try {
                abi = Integer.parseInt(landlock.group(1));
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Unexpected sandbox launcher probe output: " + probeOutput, e);
            }
            boolean seccomp = "yes".equals(landlock.group(2));
            StringBuilder missing = new StringBuilder();
            if (abi == 0) {
                missing.append("Landlock is unavailable (kernel without CONFIG_SECURITY_LANDLOCK or lsm= list)");
            } else if (abi < MIN_LANDLOCK_ABI) {
                missing.append("Landlock ABI ").append(abi).append(" cannot mediate truncate(2); ABI ")
                        .append(MIN_LANDLOCK_ABI).append("+ (kernel 6.2+) is required");
            }
            if (!seccomp) {
                missing.append(missing.length() > 0 ? "; " : "").append("seccomp filters are unavailable");
            }
            return new Result(landlock.group(), missing.length() == 0, missing.toString());
        }
        Matcher seatbelt = SEATBELT.matcher(output);
        if (seatbelt.find()) {
            boolean available = "yes".equals(seatbelt.group(1));
            return new Result(output, available, available ? "" : "Seatbelt is unavailable (/usr/bin/sandbox-exec missing or refusing profiles)");
        }
        throw new IllegalStateException("Unexpected sandbox launcher probe output: " + probeOutput);
    }

    static void requireExecutable(Path launcher) {
        if (!Files.isRegularFile(launcher) || !Files.isExecutable(launcher)) {
            throw new IllegalStateException("extender.sandbox.enabled is true but the launcher " + launcher
                    + " is missing or not executable");
        }
    }

    static Result run(Path launcher) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(launcher.toString(), "--probe");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output;
        try {
            // the probe prints one line, which fits the pipe buffer: waiting first keeps the
            // timeout effective against a launcher that hangs without closing its stdout
            if (!p.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("Sandbox launcher probe timed out: " + launcher);
            }
            output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Sandbox launcher probe interrupted", e);
        }
        if (p.exitValue() != 0) {
            throw new IOException("Sandbox launcher probe failed (exit " + p.exitValue() + "): " + output.strip());
        }
        return parse(output);
    }

    /** Strict mode refuses to start without every kernel layer; otherwise the gap is only logged. */
    static void validate(SandboxConfiguration configuration, Result result) {
        if (result.enforceable()) {
            return;
        }
        if (configuration.isStrict()) {
            throw new IllegalStateException("Process sandbox cannot be enforced: " + result.missing()
                    + ". Set extender.sandbox.strict=false to run degraded.");
        }
        LOGGER.warn("Process sandbox is degraded: {}", result.missing());
    }
}
