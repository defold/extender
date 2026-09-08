package com.defold.extender.services.spm;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

import org.springframework.core.io.FileSystemResource;

import com.defold.extender.ExtenderException;
import com.defold.extender.process.ProcessSandbox;
import com.defold.extender.process.SandboxConfiguration;
import com.defold.extender.services.cocoapods.PodUtils;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The property the whole two-step design exists for: a Package.swift is executed during
 * resolution and must never reach the network. The package built here has a manifest that
 * opens a socket to a listener this test runs and reports the result through the build error,
 * once under the sandbox and once without it.
 *
 * Installs the process sandbox, which every {@link com.defold.extender.process.ProcessExecutor}
 * in the JVM picks up, so the class runs on its own.
 */
@EnabledOnOs({OS.MAC})
@EnabledIfSystemProperty(named = "extender.test.spmE2e", matches = "true")
@Isolated
public class SpmManifestNetworkTest {

    private static final Path LAUNCHER_SOURCE =
        Path.of("scripts/standalone/sandbox/extender-sandbox-darwin.c").toAbsolutePath();
    private static Path launcher;

    @BeforeAll
    static void compileLauncher(@TempDir Path dir) throws Exception {
        assumeTrue(Files.isRegularFile(LAUNCHER_SOURCE), "launcher source not found at " + LAUNCHER_SOURCE);
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/cc")), "no C compiler");
        launcher = dir.resolve("extender-sandbox");
        Process cc = new ProcessBuilder("/usr/bin/cc", "-O2", "-o", launcher.toString(), LAUNCHER_SOURCE.toString())
                .redirectErrorStream(true).start();
        String out = new String(cc.getInputStream().readAllBytes());
        assertTrue(cc.waitFor(2, TimeUnit.MINUTES) && cc.exitValue() == 0, "cc failed: " + out);
        Process probe = new ProcessBuilder(launcher.toString(), "--probe").redirectErrorStream(true).start();
        String description = new String(probe.getInputStream().readAllBytes()).strip();
        probe.waitFor(1, TimeUnit.MINUTES);
        assumeTrue(description.contains("seatbelt=yes"), "Seatbelt unavailable: " + description);
    }

    @Test
    public void manifestsCannotReachTheNetworkWhileResolving(@TempDir File rootDir) throws Exception {
        String beacon = runBeaconBuild(rootDir, true);
        assertTrue(beacon.contains("connect FAILED"),
            "a Package.swift reached the network while resolving: " + beacon);
    }

    /** The control: the denial above comes from the sandbox and not from the beacon itself. */
    @Test
    public void theBeaconReachesTheNetworkWithoutTheSandbox(@TempDir File rootDir) throws Exception {
        String beacon = runBeaconBuild(rootDir, false);
        assertTrue(beacon.contains("connect OK"), beacon);
    }

    /** Builds a package whose manifest probes the network, and returns what it reported. */
    private static String runBeaconBuild(File rootDir, boolean sandboxed) throws Exception {
        SandboxConfiguration configuration = new SandboxConfiguration();
        configuration.setEnabled(sandboxed);
        configuration.setBackend(SandboxConfiguration.Backend.SEATBELT);
        configuration.setLauncherPath(launcher.toString());
        configuration.setStrict(true);
        configuration.setReadOnlyPaths(List.of("/usr", "/bin", "/sbin", "/System", "/Library",
            "/private/etc", "/opt", "/private/var/db/timezone"));
        configuration.setReadWritePaths(List.of("/dev"));
        configuration.setReadOnlyEnvVariables(List.of("DEVELOPER_DIR"));
        ProcessSandbox.install(new ProcessSandbox(configuration));
        try (ServerSocket listener = new ServerSocket(0)) {
            SwiftPackageManagerService service = new SwiftPackageManagerService(
                new FileSystemResource("src/main/resources/template.package-swift"),
                new FileSystemResource("src/main/resources/template.project-yml"),
                new SpmServiceConfiguration(), new SimpleMeterRegistry());
            service.swiftVersion = "6.0";
            service.wrapperMachOType = "staticlib";
            service.defaultDeveloperDir = "/Applications/Xcode.app/Contents/Developer";
            service.xcodegenPath = new File("/opt/homebrew/bin/xcodegen").exists()
                ? "/opt/homebrew/bin/xcodegen" : "xcodegen";
            service.mirrorRefreshIntervalMillis = 600_000;
            service.homeDirPrefix = new File(rootDir, "spm-cache").getAbsolutePath();
            service.runAfterStartup();

            // SwiftPM keys its fingerprint store by <identity, version> in the real home, so a
            // repository built fresh here needs a name no earlier run has recorded
            String name = "Beacon" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
            File repo = new File(rootDir, "repos/" + name);
            new File(repo, "Sources/" + name).mkdirs();
            Files.writeString(new File(repo, "Package.swift").toPath(), SpmBeacon.manifest(name, listener.getLocalPort()));
            Files.writeString(new File(repo, "Sources/" + name + "/" + name + ".swift").toPath(), "public let x = 1\n");
            git(repo, "git", "init", "--quiet");
            git(repo, "git", "add", "-A");
            git(repo, "git", "-c", "user.email=e@example.com", "-c", "user.name=E", "commit", "--quiet", "-m", "init");
            git(repo, "git", "tag", "1.0.0");

            // seeded straight into the mirror store: the build must not need a network at all
            String key = SpmManifestParser.canonicalKey("https://github.com/acme/" + name + ".git");
            File mirrorsDir = new File(service.ensureCacheDirInitialized().toFile(),
                SwiftPackageManagerService.MIRRORS_SUBDIR);
            File mirror = new File(mirrorsDir, SwiftPackageManagerService.mirrorDirName(key));
            mirror.getParentFile().mkdirs();
            git(rootDir, "git", "clone", "--mirror", "--quiet", repo.getAbsolutePath(), mirror.getAbsolutePath());
            Files.write(new File(mirrorsDir, mirror.getName() + SwiftPackageManagerService.FETCH_MARKER_SUFFIX)
                .toPath(), new byte[0]);

            File manifestDir = new File(rootDir, "job/upload/spmext/osx");
            manifestDir.mkdirs();
            File manifest = new File(manifestDir, "SwiftPackages.json");
            Files.writeString(manifest.toPath(),
                "{ \"platform\": \"osx\", \"minVersion\": \"11.0\", \"packages\": [" +
                "  { \"url\": \"https://github.com/acme/" + name + ".git\", \"from\": \"1.0.0\"," +
                "    \"products\": [\"" + name + "\"] } ] }");

            SpmServiceBuildState buildState = new SpmServiceBuildState();
            buildState.workingDir = new File(rootDir, "job/SwiftPackageManagerService");
            buildState.packageDir = new File(buildState.workingDir, "Package");
            buildState.wrapperDir = new File(buildState.workingDir, "Wrapper");
            buildState.derivedDataDir = new File(buildState.workingDir, "DerivedData");
            buildState.moduleCacheDir = new File(buildState.workingDir, "ModuleCache");
            buildState.clonedSourcePackagesDir = new File(buildState.workingDir, "clonedSourcePackages");
            buildState.buildLogFile = new File(buildState.workingDir, "build.log");
            buildState.selectedPlatform = PodUtils.Platform.MACOSX;
            buildState.buildArch = System.getProperty("os.arch").equals("aarch64") ? "arm64" : "x86_64";
            new File(buildState.packageDir, "Sources/" + SpmServiceBuildState.AGGREGATOR_NAME).mkdirs();
            new File(buildState.wrapperDir, "Sources").mkdirs();
            buildState.derivedDataDir.mkdirs();
            buildState.moduleCacheDir.mkdirs();
            buildState.clonedSourcePackagesDir.mkdirs();

            // the manifest fails the build on purpose: what it reports is the point
            ExtenderException e = assertThrows(ExtenderException.class, () -> service.resolveDependencies(
                List.of(manifest), buildState, Map.of("env.MACOS_VERSION_MIN", "11.0"), false));
            assertTrue(e.getMessage().contains(SpmBeacon.MARKER), e.getMessage());
            return e.getMessage();
        } finally {
            ProcessSandbox.install(ProcessSandbox.disabled());
        }
    }

    private static void git(File cwd, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(cwd).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor() == 0, String.join(" ", command) + ":\n" + output);
    }
}
