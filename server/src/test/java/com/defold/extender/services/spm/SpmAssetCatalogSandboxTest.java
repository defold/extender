package com.defold.extender.services.spm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.core.io.FileSystemResource;

import com.defold.extender.ExtenderException;
import com.defold.extender.process.DarwinSandboxPaths;
import com.defold.extender.process.ProcessSandbox;
import com.defold.extender.process.SandboxConfiguration;
import com.defold.extender.services.cocoapods.PodUtils;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * A package with an asset catalog, built for iOS under the process sandbox. actool and the
 * ibtoold daemon behind it write their scratch files into the darwin user temp dir whatever
 * TMPDIR says, and the sandbox grants no entry there they could use: the build only gets
 * through when the service hands them an entry of the job's own, and grants the asset catalog
 * agent, which that entry never reaches, its randomly named entries. Before Xcode 27 actool
 * needs the iOS Simulator for it, which the sandbox does not grant, and the build is refused
 * with a message naming the package.
 *
 * Installs the process sandbox, which every {@link com.defold.extender.process.ProcessExecutor}
 * in the JVM picks up, so the class runs on its own.
 */
@EnabledOnOs({OS.MAC})
@EnabledIfSystemProperty(named = "extender.test.spmE2e", matches = "true")
@Isolated
public class SpmAssetCatalogSandboxTest {

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
    public void assetCatalogsCompileWithScratchFilesInTheJob(@TempDir File rootDir) throws Exception {
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
        Set<String> userTempBefore = userTempLinks();
        try {
            SwiftPackageManagerService service = new SwiftPackageManagerService(
                new FileSystemResource("src/main/resources/template.package-swift"),
                new FileSystemResource("src/main/resources/template.project-yml"),
                new SpmServiceConfiguration(), new SimpleMeterRegistry());
            service.swiftVersion = "6.0";
            service.wrapperMachOType = "staticlib";
            service.defaultDeveloperDir = System.getenv().getOrDefault("DEVELOPER_DIR", "/Applications/Xcode.app/Contents/Developer");
            service.xcodegenPath = new File("/opt/homebrew/bin/xcodegen").exists()
                ? "/opt/homebrew/bin/xcodegen" : "xcodegen";
            service.mirrorRefreshIntervalMillis = 600_000;
            service.homeDirPrefix = new File(rootDir, "spm-cache").getAbsolutePath();
            service.runAfterStartup();

            // SwiftPM keys its fingerprint store by <identity, version> in the real home, so a
            // repository built fresh here needs a name no earlier run has recorded
            String name = "Assets" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
            File repo = new File(rootDir, "repos/" + name);
            makeAssetCatalogPackage(repo, name);

            String key = SpmManifestParser.canonicalKey("https://github.com/acme/" + name + ".git");
            File mirrorsDir = new File(service.ensureCacheDirInitialized().toFile(),
                SwiftPackageManagerService.MIRRORS_SUBDIR);
            File mirror = new File(mirrorsDir, SwiftPackageManagerService.mirrorDirName(key));
            mirror.getParentFile().mkdirs();
            git(rootDir, "git", "clone", "--mirror", "--quiet", repo.getAbsolutePath(), mirror.getAbsolutePath());
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

            String xcodeVersion = SwiftPackageManagerService.xcodeVersion(service.defaultDeveloperDir);
            assertNotNull(xcodeVersion, "no Xcode version for " + service.defaultDeveloperDir);
            if (Integer.parseInt(xcodeVersion.split("\\.")[0]) < SwiftPackageManagerService.MIN_IOS_ASSET_CATALOG_XCODE_MAJOR) {
                ExtenderException e = assertThrows(ExtenderException.class, () -> service.resolveDependencies(
                    List.of(manifest), buildState, Map.of("env.IOS_VERSION_MIN", "15.0"), true));
                assertTrue(e.getMessage().startsWith("Swift packages with asset catalogs (" + name + ") need Xcode 27"),
                    e.getMessage());
            } else {
                ResolvedPackages resolved = service.resolveDependencies(
                    List.of(manifest), buildState, Map.of("env.IOS_VERSION_MIN", "15.0"), true);

                assertNotNull(resolved);
                File bundle = new File(buildState.getProductsDir(), name + "_" + name + ".bundle");
                assertTrue(new File(bundle, "Assets.car").isFile(), "no compiled asset catalog in " + bundle);
                try (Stream<Path> entries = Files.list(new File(buildState.getWrapperDir(), "tmp").toPath())) {
                    assertTrue(entries.findAny().isPresent(), "nothing was written to the job's temp dir");
                }
            }
        } finally {
            ProcessSandbox.install(ProcessSandbox.disabled());
        }
        assertEquals(userTempBefore, userTempLinks(), "the user temp dir entry outlived the build");
    }

    private static Set<String> userTempLinks() throws IOException {
        Path root = DarwinSandboxPaths.userTempDir().getParent();
        Set<String> links = new HashSet<>();
        for (String dir : List.of("T", "C", "0")) {
            try (Stream<Path> entries = Files.list(root.resolve(dir))) {
                links.addAll(entries.map(p -> dir + "/" + p.getFileName())
                    .filter(n -> n.contains("/" + SwiftPackageManagerService.USER_DIR_LINK_PREFIX))
                    .collect(Collectors.toSet()));
            }
        }
        return links;
    }

    /** A library whose only resource is an asset catalog holding one PNG, committed and tagged 1.0.0. */
    private static void makeAssetCatalogPackage(File repo, String name) throws IOException, InterruptedException {
        File sources = new File(repo, "Sources/" + name);
        File imageSet = new File(sources, "Media.xcassets/dot.imageset");
        imageSet.mkdirs();
        Files.writeString(new File(repo, "Package.swift").toPath(),
            "// swift-tools-version:5.9\n"
            + "import PackageDescription\n"
            + "let package = Package(\n"
            + "    name: \"" + name + "\",\n"
            + "    platforms: [.iOS(\"15.0\")],\n"
            + "    products: [.library(name: \"" + name + "\", targets: [\"" + name + "\"])],\n"
            + "    targets: [.target(name: \"" + name + "\", resources: [.process(\"Media.xcassets\")])]\n"
            + ")\n");
        Files.writeString(new File(sources, name + ".swift").toPath(), "public let x = 1\n");
        Files.writeString(new File(sources, "Media.xcassets/Contents.json").toPath(),
            "{ \"info\" : { \"author\" : \"xcode\", \"version\" : 1 } }\n");
        Files.writeString(new File(imageSet, "Contents.json").toPath(),
            "{ \"images\" : [ { \"filename\" : \"dot.png\", \"idiom\" : \"universal\" } ],"
            + " \"info\" : { \"author\" : \"xcode\", \"version\" : 1 } }\n");
        Files.write(new File(imageSet, "dot.png").toPath(), redSquarePng(4));
        git(repo, "git", "init", "--quiet");
        git(repo, "git", "add", "-A");
        git(repo, "git", "-c", "user.email=e@example.com", "-c", "user.name=E", "commit", "--quiet", "-m", "init");
        git(repo, "git", "tag", "1.0.0");
    }

    private static byte[] redSquarePng(int size) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (int y = 0; y < size; y++) {
            raw.write(0);
            for (int x = 0; x < size; x++) {
                raw.write(new byte[] {(byte) 0xff, 0, 0, (byte) 0xff});
            }
        }
        Deflater deflater = new Deflater();
        deflater.setInput(raw.toByteArray());
        deflater.finish();
        byte[] buffer = new byte[1024];
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        while (!deflater.finished()) {
            compressed.write(buffer, 0, deflater.deflate(buffer));
        }
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        DataOutputStream ihdr = new DataOutputStream(header);
        ihdr.writeInt(size);
        ihdr.writeInt(size);
        ihdr.write(new byte[] {8, 6, 0, 0, 0});

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        writeChunk(png, "IHDR", header.toByteArray());
        writeChunk(png, "IDAT", compressed.toByteArray());
        writeChunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream png, String type, byte[] data) throws IOException {
        DataOutputStream out = new DataOutputStream(png);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeInt(data.length);
        out.write(typeBytes);
        out.write(data);
        out.writeInt((int) crc.getValue());
    }

    private static void git(File cwd, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(cwd).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor() == 0, String.join(" ", command) + ":\n" + output);
    }
}
