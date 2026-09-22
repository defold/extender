package com.defold.extender.process;

import java.io.IOException;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Validates the sandbox launcher at startup and installs the {@link ProcessSandbox} every
 * {@link ProcessExecutor} picks up. A failure here aborts the application context, which is
 * the intended fail-closed behaviour when {@code extender.sandbox.enabled} is true.
 */
@Configuration(proxyBeanMethods = false)
class ProcessSandboxInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessSandboxInitializer.class);

    @Bean
    ProcessSandbox processSandbox(SandboxConfiguration configuration) throws IOException {
        if (configuration.isEnabled()) {
            Path launcher = Path.of(configuration.getLauncherPath());
            SandboxLauncherProbe.requireExecutable(launcher);
            SandboxLauncherProbe.Result probe = SandboxLauncherProbe.run(launcher);
            SandboxLauncherProbe.validate(configuration, probe);
            if (configuration.resolveBackend() == SandboxConfiguration.Backend.LANDLOCK) {
                hideProcessFromChildren(configuration);
            }
            if (configuration.getReadOnlyPaths().isEmpty()) {
                LOGGER.warn("extender.sandbox.read-only-paths is empty; toolchains will not be able to read "
                        + "system directories");
            }
            LOGGER.info("Process sandbox enabled: backend={} launcher={} probe=[{}] strict={} timeout={}ms "
                            + "ro={} ro-env={} rw={} rwx={} image-rw={}",
                    configuration.resolveBackend(), launcher, probe.description(), configuration.isStrict(),
                    configuration.getCommandTimeout(), configuration.getReadOnlyPaths(),
                    configuration.getReadOnlyEnvVariables(), configuration.getReadWritePaths(),
                    configuration.getReadWriteExecPaths(), configuration.getImageReadWritePaths());
        } else {
            LOGGER.info("Process sandbox disabled (extender.sandbox.enabled=false)");
        }
        ProcessSandbox sandbox = new ProcessSandbox(configuration);
        ProcessSandbox.install(sandbox);
        return sandbox;
    }

    // /proc stays readable to the children (toolchains need /proc/self and the cpu/memory
    // files), so the server's own entries are closed from the other side
    private static void hideProcessFromChildren(SandboxConfiguration configuration) {
        if (!ProcessHardening.isLinux()) {
            return;
        }
        try {
            int dumpable = ProcessHardening.makeNonDumpable();
            if (dumpable != 0) {
                throw new IllegalStateException("prctl(PR_GET_DUMPABLE) still reports " + dumpable);
            }
            LOGGER.info("Server process is non-dumpable: /proc/{}/environ, maps and fd are closed to build subprocesses",
                    ProcessHandle.current().pid());
        } catch (Throwable e) {
            if (configuration.isStrict()) {
                throw new IllegalStateException("Cannot make the server process non-dumpable, so a build could read "
                        + "its environment from /proc. Set extender.sandbox.strict=false to run degraded.", e);
            }
            LOGGER.warn("Cannot make the server process non-dumpable; /proc/<pid>/environ stays readable to builds", e);
        }
    }
}
