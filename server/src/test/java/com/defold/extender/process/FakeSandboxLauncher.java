package com.defold.extender.process;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A stand-in for {@code extender-sandbox} that works on any host with {@code /bin/sh}: it
 * answers {@code --probe}, records the argv it was given, and execs whatever follows {@code --}.
 */
final class FakeSandboxLauncher {
    static final String PROBE_OUTPUT = "landlock_abi=4 seccomp=yes";

    private FakeSandboxLauncher() {}

    /** Writes the launcher into {@code dir}; the recorded argv lands in {@code dir/argv.txt}. */
    static Path write(Path dir) throws IOException {
        Path record = dir.resolve("argv.txt");
        Path script = dir.resolve("extender-sandbox");
        String body = "#!/bin/sh\n"
                + "if [ \"$1\" = \"--probe\" ]; then echo \"" + PROBE_OUTPUT + "\"; exit 0; fi\n"
                + "printf '%s\\n' \"$@\" > \"" + record + "\"\n"
                + "while [ \"$#\" -gt 0 ] && [ \"$1\" != \"--\" ]; do shift; done\n"
                + "shift\n"
                + "exec \"$@\"\n";
        Files.writeString(script, body, StandardCharsets.UTF_8);
        File file = script.toFile();
        if (!file.setExecutable(true, false)) {
            throw new IOException("Cannot make " + script + " executable");
        }
        return script;
    }

    static List<String> recordedArgv(Path dir) throws IOException {
        return Files.readAllLines(dir.resolve("argv.txt"), StandardCharsets.UTF_8);
    }

    static SandboxConfiguration configuration(Path launcher) {
        SandboxConfiguration configuration = new SandboxConfiguration();
        configuration.setEnabled(true);
        configuration.setLauncherPath(launcher.toString());
        configuration.setStrict(true);
        // the fake speaks the Landlock dialect, whatever the host OS
        configuration.setBackend(SandboxConfiguration.Backend.LANDLOCK);
        return configuration;
    }
}
