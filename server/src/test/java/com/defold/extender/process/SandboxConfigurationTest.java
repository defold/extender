package com.defold.extender.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

public class SandboxConfigurationTest {

    @Test
    public void testDefaultsAreOffAndStrict() {
        SandboxConfiguration configuration = new SandboxConfiguration();
        assertFalse(configuration.isEnabled());
        assertTrue(configuration.isStrict());
        assertEquals("/usr/local/bin/extender-sandbox", configuration.getLauncherPath());
        assertEquals(1_200_000L, configuration.getCommandTimeout());
        assertEquals(0, configuration.getLimits().getCpuSeconds());
        assertTrue(configuration.getLimits().getMaxProcesses() > 0);
        assertTrue(configuration.getReadOnlyPaths().isEmpty());
    }

    @Test
    public void testBindsFromServerConfiguration() {
        SandboxConfiguration configuration = new Binder(new MapConfigurationPropertySource(Map.of(
                "extender.sandbox.enabled", "true",
                "extender.sandbox.launcher-path", "/opt/bin/sandbox",
                "extender.sandbox.strict", "false",
                "extender.sandbox.command-timeout", 5000,
                "extender.sandbox.read-only-paths", "/usr, /lib,/etc/passwd",
                "extender.sandbox.read-write-paths", "/dev",
                "extender.sandbox.env-deny-patterns", "AWS_.*,GOOGLE_APPLICATION_CREDENTIALS",
                "extender.sandbox.limits.max-processes", 7,
                "extender.sandbox.limits.cpu-seconds", 0)))
                .bind("extender.sandbox", SandboxConfiguration.class)
                .get();

        assertTrue(configuration.isEnabled());
        assertEquals("/opt/bin/sandbox", configuration.getLauncherPath());
        assertFalse(configuration.isStrict());
        assertEquals(5000, configuration.getCommandTimeout());
        assertEquals(List.of("/usr", "/lib", "/etc/passwd"), configuration.getReadOnlyPaths());
        assertEquals(List.of("/dev"), configuration.getReadWritePaths());
        assertEquals(List.of("AWS_.*", "GOOGLE_APPLICATION_CREDENTIALS"), configuration.getEnvDenyPatterns());
        assertEquals(7, configuration.getLimits().getMaxProcesses());
        assertEquals(0, configuration.getLimits().getCpuSeconds());
    }

    @Test
    public void testBindsFromEnvironmentVariables() {
        // This is how the Docker images hand over their writable tool state directories. Spring
        // applies the underscore/dash relaxed mapping only to the source named systemEnvironment.
        SystemEnvironmentPropertySource env = new SystemEnvironmentPropertySource("systemEnvironment", Map.of(
                "EXTENDER_SANDBOX_ENABLED", "true",
                "EXTENDER_SANDBOX_STRICT", "false",
                "EXTENDER_SANDBOX_IMAGEREADWRITEPATHS", "/var/extender/emcache_4.0.6,/var/extender/ems_temp",
                "EXTENDER_SANDBOX_LIMITS_MAXOPENFILES", "512"));
        SandboxConfiguration configuration = new Binder(ConfigurationPropertySources.from(env))
                .bind("extender.sandbox", SandboxConfiguration.class)
                .get();

        assertTrue(configuration.isEnabled());
        assertFalse(configuration.isStrict());
        assertEquals(List.of("/var/extender/emcache_4.0.6", "/var/extender/ems_temp"), configuration.getImageReadWritePaths());
        assertEquals(512, configuration.getLimits().getMaxOpenFiles());
    }

    @Test
    public void testRejectsInvalidValues() {
        SandboxConfiguration configuration = new SandboxConfiguration();
        assertThrows(IllegalArgumentException.class, () -> configuration.setCommandTimeout(0));
        assertThrows(IllegalArgumentException.class, () -> configuration.setLauncherPath(" "));
        assertThrows(IllegalArgumentException.class, () -> configuration.getLimits().setMaxProcesses(-1));
        assertThrows(IllegalArgumentException.class, () -> configuration.getLimits().setMaxFileSizeBytes(-1));
        configuration.setReadOnlyPaths(null);
        assertTrue(configuration.getReadOnlyPaths().isEmpty());
    }

    @Test
    public void testProbeOutputParsing() {
        SandboxLauncherProbe.Result result = SandboxLauncherProbe.parse("landlock_abi=4 seccomp=yes\n");
        assertEquals(4, result.landlockAbi());
        assertTrue(result.landlock());
        assertTrue(result.seccomp());

        SandboxLauncherProbe.Result none = SandboxLauncherProbe.parse("landlock_abi=0 seccomp=no");
        assertFalse(none.landlock());
        assertFalse(none.seccomp());

        assertThrows(IllegalStateException.class, () -> SandboxLauncherProbe.parse("garbage"));
        assertThrows(IllegalStateException.class, () -> SandboxLauncherProbe.parse(null));
    }

    @Test
    public void testStrictValidationRefusesMissingLayers() {
        SandboxConfiguration strict = new SandboxConfiguration();
        strict.setStrict(true);
        SandboxLauncherProbe.validate(strict, new SandboxLauncherProbe.Result(1, true));
        assertThrows(IllegalStateException.class,
                () -> SandboxLauncherProbe.validate(strict, new SandboxLauncherProbe.Result(0, true)));
        assertThrows(IllegalStateException.class,
                () -> SandboxLauncherProbe.validate(strict, new SandboxLauncherProbe.Result(3, false)));

        SandboxConfiguration lenient = new SandboxConfiguration();
        lenient.setStrict(false);
        SandboxLauncherProbe.validate(lenient, new SandboxLauncherProbe.Result(0, false));
    }

    @Test
    public void testMissingLauncherIsRejected(@TempDir Path dir) {
        assertThrows(IllegalStateException.class,
                () -> SandboxLauncherProbe.requireExecutable(dir.resolve("extender-sandbox")));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    public void testProbeRunsLauncher(@TempDir Path dir) throws IOException {
        Path launcher = FakeSandboxLauncher.write(dir);
        SandboxLauncherProbe.requireExecutable(launcher);
        SandboxLauncherProbe.Result result = SandboxLauncherProbe.run(launcher);
        assertEquals(4, result.landlockAbi());
        assertTrue(result.seccomp());
    }
}
