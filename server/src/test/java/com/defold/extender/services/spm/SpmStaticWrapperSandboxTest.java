package com.defold.extender.services.spm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
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

import com.defold.extender.process.ProcessSandbox;
import com.defold.extender.process.SandboxConfiguration;
import com.defold.extender.services.cocoapods.PodUtils;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * A binary target holding a bare static library, built into the static wrapper under the
 * process sandbox. Xcode names such a library once per package product that reaches it, and
 * libtool -static archives every repeat: the wrapper must still hold each member once.
 *
 * Installs the process sandbox, which every {@link com.defold.extender.process.ProcessExecutor}
 * in the JVM picks up, so the class runs on its own.
 */
@EnabledOnOs({OS.MAC})
@EnabledIfSystemProperty(named = "extender.test.spmE2e", matches = "true")
@Isolated
public class SpmStaticWrapperSandboxTest {

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
    public void staticLibraryBinaryTargetIsArchivedOnce(@TempDir File rootDir) throws Exception {
        String developerDir = System.getenv().getOrDefault("DEVELOPER_DIR", "/Applications/Xcode.app/Contents/Developer");
        SandboxConfiguration configuration = new SandboxConfiguration();
        configuration.setEnabled(true);
        configuration.setBackend(SandboxConfiguration.Backend.SEATBELT);
        configuration.setLauncherPath(launcher.toString());
        configuration.setStrict(true);
        configuration.setReadOnlyPaths(List.of("/usr", "/bin", "/sbin", "/System", "/Library",
            "/private/etc", "/opt", "/private/var/db/timezone"));
        configuration.setReadWritePaths(List.of("/dev"));
        configuration.setReadOnlyEnvVariables(List.of("DEVELOPER_DIR"));
        ProcessSandbox.install(new ProcessSandbox(configuration));
        try {
            SwiftPackageManagerService service = new SwiftPackageManagerService(
                new FileSystemResource("src/main/resources/template.package-swift"),
                new FileSystemResource("src/main/resources/template.project-yml"),
                new SpmServiceConfiguration(), new SimpleMeterRegistry());
            service.swiftVersion = "6.0";
            service.wrapperMachOType = "staticlib";
            service.defaultDeveloperDir = developerDir;
            service.xcodegenPath = new File("/opt/homebrew/bin/xcodegen").exists()
                ? "/opt/homebrew/bin/xcodegen" : "xcodegen";
            service.mirrorRefreshIntervalMillis = 600_000;
            service.homeDirPrefix = new File(rootDir, "spm-cache").getAbsolutePath();
            service.runAfterStartup();

            // SwiftPM keys its fingerprint store by <identity, version> in the real home, so a
            // repository built fresh here needs a name no earlier run has recorded
            String name = "Bare" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
            File repo = new File(rootDir, "repos/" + name);
            makeStaticLibraryPackage(repo, name, developerDir);

            String key = SpmManifestParser.canonicalKey("https://github.com/acme/" + name + ".git");
            File mirrorsDir = new File(service.ensureCacheDirInitialized().toFile(),
                SwiftPackageManagerService.MIRRORS_SUBDIR);
            File mirror = new File(mirrorsDir, SwiftPackageManagerService.mirrorDirName(key));
            mirror.getParentFile().mkdirs();
            run(rootDir, null, "git", "clone", "--mirror", "--quiet", repo.getAbsolutePath(), mirror.getAbsolutePath());
            Files.write(new File(mirrorsDir, mirror.getName() + SwiftPackageManagerService.FETCH_MARKER_SUFFIX)
                .toPath(), new byte[0]);

            File manifestDir = new File(rootDir, "job/upload/spmext/ios");
            manifestDir.mkdirs();
            File manifest = new File(manifestDir, "SwiftPackages.json");
            Files.writeString(manifest.toPath(),
                "{ \"platform\": \"ios\", \"minVersion\": \"15.0\", \"packages\": [" +
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
            buildState.selectedPlatform = PodUtils.Platform.IPHONEOS;
            buildState.buildArch = "arm64";
            new File(buildState.packageDir, "Sources/" + SpmServiceBuildState.AGGREGATOR_NAME).mkdirs();
            new File(buildState.wrapperDir, "Sources").mkdirs();
            buildState.derivedDataDir.mkdirs();
            buildState.moduleCacheDir.mkdirs();
            buildState.clonedSourcePackagesDir.mkdirs();

            ResolvedPackages resolved = service.resolveDependencies(
                List.of(manifest), buildState, Map.of("env.IOS_VERSION_MIN", "15.0"), true);

            assertNotNull(resolved);
            File wrapper = new File(buildState.getProductsDir(), "SpmWrapper.framework/SpmWrapper");
            List<String> members = List.of(run(rootDir, null, "/usr/bin/ar", "-t", wrapper.getAbsolutePath())
                .strip().split("\n"));
            assertEquals(1, members.stream().filter((name + ".o")::equals).count(), members.toString());
        } finally {
            ProcessSandbox.install(ProcessSandbox.disabled());
        }
    }

    /** A package whose only product is a binary target: an xcframework holding lib<name>.a. */
    private static void makeStaticLibraryPackage(File repo, String name, String developerDir)
            throws IOException, InterruptedException {
        File build = new File(repo.getParentFile(), name + "-build");
        build.mkdirs();
        Files.writeString(new File(build, name + ".c").toPath(), "int " + name + "_value(void) { return 1; }\n");
        String sdk = run(build, developerDir, "/usr/bin/xcrun", "--sdk", "iphoneos", "--show-sdk-path").strip();
        run(build, developerDir, "/usr/bin/xcrun", "clang", "-target", "arm64-apple-ios15.0", "-isysroot", sdk,
            "-c", name + ".c", "-o", name + ".o");
        run(build, developerDir, "/usr/bin/xcrun", "libtool", "-static", "-o", "lib" + name + ".a", name + ".o");
        repo.mkdirs();
        run(build, developerDir, "/usr/bin/xcrun", "xcodebuild", "-create-xcframework",
            "-library", new File(build, "lib" + name + ".a").getAbsolutePath(),
            "-output", new File(repo, name + ".xcframework").getAbsolutePath());
        Files.writeString(new File(repo, "Package.swift").toPath(),
            "// swift-tools-version:5.9\n"
            + "import PackageDescription\n"
            + "let package = Package(\n"
            + "    name: \"" + name + "\",\n"
            + "    platforms: [.iOS(\"15.0\")],\n"
            + "    products: [.library(name: \"" + name + "\", targets: [\"" + name + "\"])],\n"
            + "    targets: [.binaryTarget(name: \"" + name + "\", path: \"" + name + ".xcframework\")]\n"
            + ")\n");
        run(repo, null, "git", "init", "--quiet");
        run(repo, null, "git", "add", "-A");
        run(repo, null, "git", "-c", "user.email=e@example.com", "-c", "user.name=E", "commit", "--quiet", "-m", "init");
        run(repo, null, "git", "tag", "1.0.0");
    }

    private static String run(File cwd, String developerDir, String... command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd).redirectErrorStream(true);
        if (developerDir != null) {
            builder.environment().put("DEVELOPER_DIR", developerDir);
        }
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor() == 0, String.join(" ", command) + ":\n" + output);
        return output;
    }
}
