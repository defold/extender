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
        assertThrows(IllegalArgumentException.class, () -> configuration.setCommandTimeout(-1));
        // 0 means "no limit", as it does in ProcessExecutor and ProcessSandbox
        configuration.setCommandTimeout(0);
        assertEquals(0, configuration.getCommandTimeout());
        assertThrows(IllegalArgumentException.class, () -> configuration.setLauncherPath(" "));
        assertThrows(IllegalArgumentException.class, () -> configuration.getLimits().setMaxProcesses(-1));
        assertThrows(IllegalArgumentException.class, () -> configuration.getLimits().setMaxFileSizeBytes(-1));
        configuration.setReadOnlyPaths(null);
        assertTrue(configuration.getReadOnlyPaths().isEmpty());
    }

    @Test
    public void testProbeOutputParsing() {
        SandboxLauncherProbe.Result result = SandboxLauncherProbe.parse("landlock_abi=4 seccomp=yes\n");
        assertTrue(result.enforceable());
        assertEquals("landlock_abi=4 seccomp=yes", result.description());
        assertEquals("", result.missing());

        SandboxLauncherProbe.Result none = SandboxLauncherProbe.parse("landlock_abi=0 seccomp=no");
        assertFalse(none.enforceable());
        assertTrue(none.missing().contains("Landlock"), none.missing());
        assertTrue(none.missing().contains("seccomp"), none.missing());
        assertFalse(SandboxLauncherProbe.parse("landlock_abi=3 seccomp=no").enforceable());
        assertTrue(SandboxLauncherProbe.parse("landlock_abi=3 seccomp=yes").enforceable());
        SandboxLauncherProbe.Result old = SandboxLauncherProbe.parse("landlock_abi=2 seccomp=yes");
        assertFalse(old.enforceable());
        assertTrue(old.missing().contains("truncate"), old.missing());
        assertThrows(IllegalStateException.class, () -> SandboxLauncherProbe.parse("landlock_abi=99999999999 seccomp=yes"));

        SandboxLauncherProbe.Result seatbelt = SandboxLauncherProbe.parse("seatbelt=yes sandbox_exec=/usr/bin/sandbox-exec\n");
        assertTrue(seatbelt.enforceable());
        assertTrue(seatbelt.description().contains("seatbelt=yes"), seatbelt.description());
        SandboxLauncherProbe.Result noSeatbelt = SandboxLauncherProbe.parse("seatbelt=no sandbox_exec=/usr/bin/sandbox-exec");
        assertFalse(noSeatbelt.enforceable());
        assertTrue(noSeatbelt.missing().contains("Seatbelt"), noSeatbelt.missing());

        assertThrows(IllegalStateException.class, () -> SandboxLauncherProbe.parse("garbage"));
        assertThrows(IllegalStateException.class, () -> SandboxLauncherProbe.parse(null));
    }

    @Test
    public void testStrictValidationRefusesMissingLayers() {
        SandboxConfiguration strict = new SandboxConfiguration();
        strict.setStrict(true);
        SandboxLauncherProbe.validate(strict, SandboxLauncherProbe.parse("landlock_abi=3 seccomp=yes"));
        SandboxLauncherProbe.validate(strict, SandboxLauncherProbe.parse("seatbelt=yes"));
        assertThrows(IllegalStateException.class,
                () -> SandboxLauncherProbe.validate(strict, SandboxLauncherProbe.parse("landlock_abi=0 seccomp=yes")));
        assertThrows(IllegalStateException.class,
                () -> SandboxLauncherProbe.validate(strict, SandboxLauncherProbe.parse("landlock_abi=3 seccomp=no")));
        assertThrows(IllegalStateException.class,
                () -> SandboxLauncherProbe.validate(strict, SandboxLauncherProbe.parse("seatbelt=no")));

        SandboxConfiguration lenient = new SandboxConfiguration();
        lenient.setStrict(false);
        SandboxLauncherProbe.validate(lenient, SandboxLauncherProbe.parse("landlock_abi=0 seccomp=no"));
        SandboxLauncherProbe.validate(lenient, SandboxLauncherProbe.parse("seatbelt=no"));
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
        assertTrue(result.enforceable());
        assertEquals(FakeSandboxLauncher.PROBE_OUTPUT, result.description());
    }

    @Test
    public void testDefaultsForBackendAndEnvironmentGrants() {
        SandboxConfiguration configuration = new SandboxConfiguration();
        assertEquals(SandboxConfiguration.Backend.AUTO, configuration.getBackend());
        assertEquals(List.of("DYNAMO_HOME", "MANIFEST_MERGE_TOOL"), configuration.getReadOnlyEnvVariables());
        assertTrue(configuration.getDarwin().getMachServices().isEmpty());
        assertEquals(List.of("TemporaryItems/", "xcrun_db$", "[0-9A-Fa-f-]+-[0-9]+-[0-9A-Fa-f]+(/|$)"), configuration.getDarwin().getUserTempPatterns());
        configuration.setDarwin(null);
        assertTrue(configuration.getDarwin().getDenyPaths().isEmpty());
    }

    @Test
    public void testBindsDarwinBlock() {
        SandboxConfiguration configuration = new Binder(new MapConfigurationPropertySource(Map.of(
                "extender.sandbox.backend", "seatbelt",
                "extender.sandbox.read-only-env-variables", "DYNAMO_HOME,PLATFORMSDK_DIR, DEVELOPER_DIR",
                "extender.sandbox.darwin.mach-services", "com.apple.lsd.mapdb,com.apple.FSEvents",
                "extender.sandbox.darwin.preference-domains", "kCFPreferencesAnyApplication",
                "extender.sandbox.darwin.deny-paths", "~/.ssh,/Library/Keychains",
                "extender.sandbox.darwin.deny-exec-paths", "/usr/bin/sudo",
                "extender.sandbox.darwin.home-links", "Library/Developer",
                "extender.sandbox.darwin.extra-rules[0]", "(allow sysctl-write)")))
                .bind("extender.sandbox", SandboxConfiguration.class)
                .get();

        assertEquals(SandboxConfiguration.Backend.SEATBELT, configuration.getBackend());
        assertEquals(SandboxConfiguration.Backend.SEATBELT, configuration.resolveBackend());
        assertEquals(List.of("DYNAMO_HOME", "PLATFORMSDK_DIR", "DEVELOPER_DIR"), configuration.getReadOnlyEnvVariables());
        assertEquals(List.of("com.apple.lsd.mapdb", "com.apple.FSEvents"), configuration.getDarwin().getMachServices());
        assertEquals(List.of("kCFPreferencesAnyApplication"), configuration.getDarwin().getPreferenceDomains());
        assertEquals(List.of("~/.ssh", "/Library/Keychains"), configuration.getDarwin().getDenyPaths());
        assertEquals(List.of("/usr/bin/sudo"), configuration.getDarwin().getDenyExecPaths());
        assertEquals(List.of("Library/Developer"), configuration.getDarwin().getHomeLinks());
        assertEquals(List.of("(allow sysctl-write)"), configuration.getDarwin().getExtraRules());
    }
}
