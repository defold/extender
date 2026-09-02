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

/** Runs {@code extender-sandbox --probe} once at startup and interprets the answer. */
final class SandboxLauncherProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxLauncherProbe.class);
    private static final Pattern OUTPUT = Pattern.compile("landlock_abi=(\\d+)\\s+seccomp=(yes|no)");
    private static final long PROBE_TIMEOUT_SECONDS = 10;

    private SandboxLauncherProbe() {}

    record Result(int landlockAbi, boolean seccomp) {
        boolean landlock() {
            return landlockAbi > 0;
        }
    }

    static Result parse(String probeOutput) {
        Matcher m = OUTPUT.matcher(probeOutput == null ? "" : probeOutput);
        if (!m.find()) {
            throw new IllegalStateException("Unexpected sandbox launcher probe output: " + probeOutput);
        }
        return new Result(Integer.parseInt(m.group(1)), "yes".equals(m.group(2)));
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
            output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("Sandbox launcher probe timed out: " + launcher);
            }
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

    /** Strict mode refuses to start without both kernel layers; otherwise the gap is only logged. */
    static void validate(SandboxConfiguration configuration, Result result) {
        StringBuilder missing = new StringBuilder();
        if (!result.landlock()) {
            missing.append("Landlock is unavailable (kernel without CONFIG_SECURITY_LANDLOCK or lsm= list)");
        }
        if (!result.seccomp()) {
            missing.append(missing.length() > 0 ? "; " : "").append("seccomp filters are unavailable");
        }
        if (missing.length() == 0) {
            return;
        }
        if (configuration.isStrict()) {
            throw new IllegalStateException("Process sandbox cannot be enforced: " + missing
                    + ". Set extender.sandbox.strict=false to run degraded.");
        }
        LOGGER.warn("Process sandbox is degraded: {}", missing);
    }
}
