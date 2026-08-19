package com.defold.extender;

import com.defold.extender.client.*;
import com.defold.extender.process.ProcessExecutor;
import com.google.common.collect.Lists;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.jar.JarOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

@Tag("integration")
@Execution(ExecutionMode.SAME_THREAD)
public class IntegrationTest {
    private static final int EXTENDER_PORT = 9000;

    static {
        LoggingSystem.get(ClassLoader.getSystemClassLoader()).setLogLevel(Logger.ROOT_LOGGER_NAME, LogLevel.INFO);
    }

    private static class Version
    {
        public int major;
        public int middle;
        public int minor;

        public Version(int major, int middle, int minor)
        {
            this.major = major;
            this.middle = middle;
            this.minor = minor;
        }

        @Override
        public String toString() {
            return String.format("%d.%d.%03d", major, middle, minor);
        }

        // How a version is written on the command line, e.g. -PdefoldVersions=1.12.3
        public String toShortString() {
            return String.format("%d.%d.%d", major, middle, minor);
        }

        boolean isVersion(int major, int middle, int minor) {
            return this.major == major && this.middle == middle && this.minor == minor;
        }

        boolean isGreaterThan(int major, int middle, int minor) {
            return this.major > major || this.middle > middle || this.minor > minor;
        }
    }

    private static class DefoldVersion
    {
        public String sha1;
        public Version version;
        public String[] platforms;

        public DefoldVersion(String sha1, Version version, String[] platforms)
        {
            this.sha1 = sha1;
            this.version = version;
            this.platforms = platforms;
        }

        @Override
        public String toString() {
            return version.toString();
        }
    }

    private static class TestConfiguration {
        public DefoldVersion version;
        public String platform = "";

        public TestConfiguration(DefoldVersion version, String platform) {
            this.version = version;
            this.platform = platform;
        }

        @Override
        public String toString() {
            return String.format("%s sha1(%s) %s", version.version.toString(), version.sha1, platform);
        }
    }

    public static List<TestConfiguration> data() {

        boolean ciBuild = System.getenv("GITHUB_WORKSPACE") != null;

        ArrayList<TestConfiguration> data = new ArrayList<>();

        DefoldVersion[] versions = {
                // "a" is a made up sdk where we can more easily test build.yml fixes
                // new DefoldVersion("a", new Version(0, 0, 0), new String[] {"armv7-android", "x86_64-android", "x86_64-win32"} ),
                // NOTE: x86_64-android can be added to the released SDKs below once a Defold
                // release ships the platform in its extender/build.yml.
                // // https://github.com/defold/defold/releases/tag/1.11.1
                new DefoldVersion("758dfc0ea71dca26d169fddd0c5a1bc6dd0be4b3", new Version(1, 11, 1), new String[] {"armv7-android", "arm64-android", "x86_64-linux", "x86_64-win32", "js-web", "wasm-web"}),
                // // https://github.com/defold/defold/releases/tag/1.11.2
                new DefoldVersion("cddb6eb43c32e4930257fcbbb30f19cf28deb081", new Version(1, 11, 2), new String[] {"armv7-android", "arm64-android", "x86_64-linux", "x86_64-win32", "js-web", "wasm-web"}),
                // // https://github.com/defold/defold/releases/tag/1.12.0
                new DefoldVersion("3206f699aaff89f357c9d549050b8453e080c5d2", new Version(1, 12, 0), new String[] {"armv7-android", "arm64-android", "x86_64-linux", "x86_64-win32", "js-web", "wasm-web"}),
                // // https://github.com/defold/defold/releases/tag/1.12.1
                new DefoldVersion("16c6fd602f32de4814660672c38ce3ccbbc1fb59", new Version(1, 12, 1), new String[] {"armv7-android", "arm64-android", "x86_64-linux", "x86_64-win32", "js-web", "wasm-web"}),
                // // https://github.com/defold/defold/releases/tag/1.12.2
                new DefoldVersion("e43be333aa7a4fc319ab62adc8d405c8e98bf92f", new Version(1, 12, 2), new String[] {"armv7-android", "arm64-android", "x86_64-linux", "x86_64-win32", "js-web", "wasm-web"}),
                // // https://github.com/defold/defold/releases/tag/1.12.3
                new DefoldVersion("0ad9c86fa0a9f7ac19bc468b1a67ee06bb2578b5", new Version(1, 12, 3), new String[] {"armv7-android", "arm64-android", "x86_64-linux", "x86_64-win32", "js-web", "wasm-web"})
                // Use test-data/createdebugsdk.sh to package your preferred platform sdk and it ends up in the sdk/debugsdk folder
                // Then you can write your tests without waiting for the next release
                //new DefoldVersion("debugsdk", new Version(1, 2, 104), new String[] {"js-web"}),
        };

        DefoldVersion[] ciVersions = {
            versions[0],
            versions[versions.length-1],
        };

        if (ciBuild) {
            versions = ciVersions;
        }

        // Opt-in narrowing for local runs. Both properties are absent in CI, so this is a no-op there.
        String versionFilter = System.getProperty(TestUtils.PROP_DEFOLD_VERSIONS);
        if (versionFilter != null && !versionFilter.isBlank()) {
            versions = filterVersions(versions, ciVersions, versionFilter.trim());
        }
        Set<String> platformFilter = TestUtils.selectedPlatforms();

        for( int i = 0; i < versions.length; ++i )
        {
            for (String platform : versions[i].platforms) {
                if (!platformFilter.isEmpty() && !platformFilter.contains(platform)) {
                    continue;
                }
                data.add(new TestConfiguration(versions[i], platform));
            }
        }

        if (data.isEmpty()) {
            // An empty @MethodSource fails every test with an opaque error, so say what went wrong.
            throw new IllegalArgumentException(String.format(
                "-PtargetPlatforms=%s selected no target platform. Known platforms: %s",
                System.getProperty(TestUtils.PROP_TARGET_PLATFORMS),
                String.join(", ", versions[0].platforms)));
        }

        return data;
    }

    // -PdefoldVersions=all|ci|latest|1.12.3,1.11.1
    private static DefoldVersion[] filterVersions(DefoldVersion[] versions, DefoldVersion[] ciVersions, String filter) {
        switch (filter) {
            case "all":
                return versions;
            case "ci":
                return ciVersions;
            case "latest":
                return new DefoldVersion[] { versions[versions.length - 1] };
            default:
                break;
        }

        Set<String> wanted = new LinkedHashSet<>(Arrays.asList(filter.split("\\s*,\\s*")));
        List<DefoldVersion> selected = new ArrayList<>();
        for (DefoldVersion version : versions) {
            if (wanted.contains(version.version.toShortString())) {
                selected.add(version);
            }
        }
        if (selected.isEmpty()) {
            List<String> known = new ArrayList<>();
            for (DefoldVersion version : versions) {
                known.add(version.version.toShortString());
            }
            throw new IllegalArgumentException(String.format(
                "-PdefoldVersions=%s matched no Defold version. Use 'all', 'ci', 'latest', or any of: %s",
                filter, String.join(", ", known)));
        }
        return selected.toArray(new DefoldVersion[0]);
    }

    public IntegrationTest() { }

    @BeforeAll
    public static void beforeClass() throws IOException, InterruptedException {
        ProcessExecutor processExecutor = new ProcessExecutor();
        // Boot only the builders the selected target platforms need; "test" (everything) by default.
        processExecutor.putEnv("COMPOSE_PROFILE", TestUtils.composeProfiles(TestUtils.selectedPlatforms()));
        processExecutor.putEnv("APPLICATION", "extender-test");
        processExecutor.putEnv("PORT", String.valueOf(EXTENDER_PORT));
        if (TestUtils.reuseStack()) {
            processExecutor.putEnv("EXTENDER_KEEP_STACK", "1");
        }
        processExecutor.execute(TestUtils.shellScriptArgs("scripts/start-test-server.sh"));
        System.out.println(processExecutor.getOutput());

        long startTime = System.currentTimeMillis();

        // Wait for server to start in container.
        File cacheDir = Files.createTempDirectory("health_check").toFile();
        cacheDir.deleteOnExit();
        ExtenderClient extenderClient = new ExtenderClient("http://localhost:" + EXTENDER_PORT, cacheDir);

        int count = 100;
        for (int i  = 0; i < count; i++) {

            try {
                if (extenderClient.health()) {
                    System.out.println(String.format("Server started after %f seconds!", (System.currentTimeMillis() - startTime) / 1000.f));
                    break;
                }
            } catch (IOException e) {
                if (i == count-1) {
                    e.printStackTrace();
                }
            }
            System.out.println("Waiting for server to start...");
            Thread.sleep(2000);
        }

    }

    @AfterAll
    public static void afterClass() throws IOException, InterruptedException {
        ProcessExecutor processExecutor = new ProcessExecutor();
        processExecutor.putEnv("APPLICATION", "extender-test");
        if (TestUtils.reuseStack()) {
            processExecutor.putEnv("EXTENDER_KEEP_STACK", "1");
        }
        processExecutor.execute(TestUtils.shellScriptArgs("scripts/stop-test-server.sh"));
        System.out.println(processExecutor.getOutput());
    }

    private String[] getEngineNames(String platform) {
        if (platform.endsWith("android")) {
            return new String[]{"libdmengine.so"};
        }
        else if (platform.equals("js-web")) {
            return new String[]{"dmengine.js"};
        }
        else if (platform.equals("wasm-web")) {
            return new String[]{"dmengine.js", "dmengine.wasm"};
        }
        else if (platform.equals("wasm_pthread-web")) {
            return new String[]{"dmengine.js", "dmengine_pthread.wasm"};
        }
        else if (platform.endsWith("win32")) {
            return new String[]{"dmengine.exe"};
        }
        return new String[]{"dmengine"};
    }

    private String getLibName(String platform, String lib) {
        if (platform.endsWith("win32")) {
            return String.format("%s.lib", lib);
        }
        return String.format("lib%s.a", lib);
    }

        private String getDynamicLibName(String platform, String lib) {
        if (platform.endsWith("win32")) {
            return String.format("%s.dll", lib);
        } else if (ExtenderUtil.isAppleTarget(platform)) {
            return String.format("%s.dylib", lib);
        }
        return String.format("lib%s.so", lib);
    }

    private File doBuild(List<ExtenderResource> sourceFiles, TestConfiguration configuration) throws IOException, ExtenderClientException {
        File cacheDir = Files.createTempDirectory(String.format("%s-%s", configuration.platform, configuration.version.toString())).toFile();
        cacheDir.deleteOnExit();
        ExtenderClient extenderClient = new ExtenderClient("http://localhost:" + EXTENDER_PORT, cacheDir);
        File destination = Files.createTempFile("dmengine", ".zip").toFile();
        File log = Files.createTempFile("dmengine", ".log").toFile();

        String platform = configuration.platform;
        String sdkVersion = configuration.version.sha1;

        try {
            extenderClient.build(
                    platform,
                    sdkVersion,
                    sourceFiles,
                    destination,
                    log
            );
        } catch (ExtenderClientException e) {
            System.out.println("ERROR LOG:");
            System.out.println(new String(Files.readAllBytes(log.toPath())));
            throw e;
        }

        assertTrue(destination.length() > 0, "Resulting engine should be of a size greater than zero.");
        assertEquals(0, log.length(), "Log should be of size zero if successful.");

        ExtenderClientCache cache = new ExtenderClientCache(cacheDir);
        assertTrue(cache.getCachedBuildFile(configuration.platform).exists());

        try (ZipFile zipFile = new ZipFile(destination)) {
            String[] expectedEngineNames = getEngineNames(configuration.platform);
            for (String engineName : expectedEngineNames) {
                assertNotEquals(null, zipFile.getEntry( engineName ) );
            }

            if (configuration.platform.endsWith("android")) {
                // Add this when we've made sure that all android builds create a classes.dex
                assertNotEquals(null, zipFile.getEntry("classes.dex"));
            }
        }

        return destination;
    }

    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildEngine(TestConfiguration configuration) throws IOException, ExtenderClientException {
        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
                new FileExtenderResource("test-data/ext2/ext.manifest"),
                new FileExtenderResource("test-data/ext2/src/test_ext.cpp"),
                new FileExtenderResource(String.format("test-data/ext2/lib/%s/%s", configuration.platform, getLibName(configuration.platform, "alib"))),
                new FileExtenderResource(String.format("test-data/ext2/lib/%s/%s", configuration.platform, getLibName(configuration.platform, "blib")))
        );

        doBuild(sourceFiles, configuration);
    }

    private static class ProgressEvent {
        final String stage;
        final int percent;
        final int currentFile;
        final int totalFiles;

        ProgressEvent(String stage, int percent, int currentFile, int totalFiles) {
            this.stage = stage;
            this.percent = percent;
            this.currentFile = currentFile;
            this.totalFiles = totalFiles;
        }
    }

    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildEngineWithProgress(TestConfiguration configuration) throws IOException, ExtenderClientException, InterruptedException {
        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
                new FileExtenderResource("test-data/ext2/ext.manifest"),
                new FileExtenderResource("test-data/ext2/src/test_ext.cpp"),
                new FileExtenderResource(String.format("test-data/ext2/lib/%s/%s", configuration.platform, getLibName(configuration.platform, "alib"))),
                new FileExtenderResource(String.format("test-data/ext2/lib/%s/%s", configuration.platform, getLibName(configuration.platform, "blib")))
        );

        File cacheDir = Files.createTempDirectory(String.format("progress-%s-%s", configuration.platform, configuration.version.toString())).toFile();
        cacheDir.deleteOnExit();
        ExtenderClient extenderClient = new ExtenderClient("http://localhost:" + EXTENDER_PORT, cacheDir);
        File destination = Files.createTempFile("dmengine", ".zip").toFile();
        File log = Files.createTempFile("dmengine", ".log").toFile();

        List<ProgressEvent> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch terminalSeen = new CountDownLatch(1);
        ExtenderProgressListener listener = (stage, detail, percent, currentFile, totalFiles) -> {
            events.add(new ProgressEvent(stage, percent, currentFile, totalFiles));
            if ("SUCCESS".equals(stage) || "ERROR".equals(stage)) {
                terminalSeen.countDown();
            }
        };

        try {
            extenderClient.build(
                    configuration.platform,
                    configuration.version.sha1,
                    sourceFiles,
                    destination,
                    log,
                    listener
            );
        } catch (ExtenderClientException e) {
            System.out.println("ERROR LOG:");
            System.out.println(new String(Files.readAllBytes(log.toPath())));
            throw e;
        }

        assertTrue(destination.length() > 0, "Resulting engine should be of a size greater than zero.");

        // the terminal event is pushed before /job_status flips, so it should
        // already be here (or arrive momentarily)
        assertTrue(terminalSeen.await(10, TimeUnit.SECONDS), "Progress listener never saw a terminal event");
        List<ProgressEvent> snapshot = new ArrayList<>(events);
        assertTrue(snapshot.size() >= 2, "Expected multiple progress events, got " + snapshot.size());
        assertEquals("SUCCESS", snapshot.get(snapshot.size() - 1).stage);
        assertEquals(100, snapshot.get(snapshot.size() - 1).percent);

        // percent must never decrease
        int lastPercent = 0;
        for (ProgressEvent event : snapshot) {
            assertTrue(event.percent >= lastPercent,
                    String.format("Percent regressed from %d to %d at stage %s", lastPercent, event.percent, event.stage));
            lastPercent = event.percent;
        }

        // per-file compile progress: some extension reported file counts and finished them
        boolean sawFileCounts = snapshot.stream().anyMatch(e -> "COMPILING".equals(e.stage) && e.totalFiles > 0);
        boolean sawCompleted = snapshot.stream().anyMatch(e -> "COMPILING".equals(e.stage) && e.totalFiles > 0 && e.currentFile == e.totalFiles);
        assertTrue(sawFileCounts, "Expected COMPILING events with file counts");
        assertTrue(sawCompleted, "Expected a COMPILING event with all files completed");

        // stages appear in pipeline order for the ones we saw
        List<String> stageOrder = Arrays.asList("RECEIVED", "QUEUED", "SDK", "DEPENDENCIES", "MANIFESTS", "PLATFORM", "COMPILING", "LINKING", "PACKAGING");
        int lastIndex = -1;
        for (ProgressEvent event : snapshot) {
            int index = stageOrder.indexOf(event.stage);
            if (index >= 0 && !"COMPILING".equals(event.stage)) {
                assertTrue(index >= lastIndex,
                        String.format("Stage %s arrived after a later stage", event.stage));
                lastIndex = Math.max(lastIndex, index);
            }
        }
    }

    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildExtensionStdLib(TestConfiguration configuration) throws IOException, ExtenderClientException {
        assumeTrue(!configuration.version.version.isVersion(0, 0, 0), "Only use with real sdk's");
        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/ext_std/ext.manifest"),
                new FileExtenderResource("test-data/ext_std/include/std.h"),
                new FileExtenderResource("test-data/ext_std/src/test_ext.cpp"),
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
                new FileExtenderResource(String.format("test-data/ext_std/lib/%s/%s", configuration.platform, getLibName(configuration.platform, "std")))
        );
        doBuild(sourceFiles, configuration);
    }

    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildEngineWithBaseExtension(TestConfiguration configuration) throws IOException, ExtenderClientException {
        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/ext/ext.manifest"),
                new FileExtenderResource("test-data/ext/include/ext.h"),
                new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
                new FileExtenderResource(String.format("test-data/ext/lib/%s/%s", configuration.platform, getLibName(configuration.platform, "alib"))),
                new FileExtenderResource("test-data/ext_use_base_extension/ext.manifest"),
                new FileExtenderResource("test-data/ext_use_base_extension/src/test_ext.cpp"),
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml")
        );

        doBuild(sourceFiles, configuration);
    }

    private Set<String> getClassesDexClasses(File buildZip) throws IOException {
        Set<String> dexClasses = new HashSet<>();

        try (ZipFile zipFile = new ZipFile(buildZip)) {
            final Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                if (!name.endsWith(".dex"))
                    continue;

                InputStream in = zipFile.getInputStream(entry);
                Path tmpClassesDexPath = Files.createTempFile("classes", "dex");
                Files.copy(in, tmpClassesDexPath, StandardCopyOption.REPLACE_EXISTING);

                // Verify that classes.dex contains our Dummy class
                DexFile dexFile = DexFileFactory.loadDexFile(tmpClassesDexPath.toFile().getAbsolutePath(), Opcodes.forApi(19));
                for (ClassDef classDef: dexFile.getClasses()) {
                    dexClasses.add(classDef.getType());
                }
            }
        }

        return dexClasses;
    }

    private boolean checkClassesDexClasses(File buildZip, List<String> classes) throws IOException {
        Set<String> dexClasses = getClassesDexClasses(buildZip);

        for (String cls : classes) {
            if (!dexClasses.contains(cls)) {
                System.err.println(String.format("Missing class %s", cls));
                return false;
            }
        }
        return true;
    }

    private Path createGradleHandoffClassifierJar(Path fixtureDirectory) throws IOException {
        Path source = fixtureDirectory.resolve("android/support/annotation/Nullable.java");
        Path classes = fixtureDirectory.resolve("classifier-classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classes);
        Files.writeString(
                source,
                "package android.support.annotation;\n" +
                "public @interface Nullable {}\n",
                StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "The integration test requires a JDK");
        assertEquals(
                0,
                compiler.run(
                        null,
                        null,
                        null,
                        "--release",
                        "8",
                        "-d",
                        classes.toString(),
                        source.toString()));

        Path outputJar = fixtureDirectory.resolve("handoff-1.0-extras.jar");
        try (ZipFile sourceJar = new ZipFile("test-data/ext/lib/android/JarDep.jar");
             OutputStream fileOutput = Files.newOutputStream(outputJar);
             JarOutputStream jarOutput = new JarOutputStream(fileOutput)) {
            ZipEntry jarDependency = sourceJar.getEntry("com/defold/JarDep.class");
            assertNotNull(jarDependency);
            jarOutput.putNextEntry(new ZipEntry(jarDependency.getName()));
            try (InputStream input = sourceJar.getInputStream(jarDependency)) {
                input.transferTo(jarOutput);
            }
            jarOutput.closeEntry();

            Path nullableClass = classes.resolve("android/support/annotation/Nullable.class");
            jarOutput.putNextEntry(new ZipEntry("android/support/annotation/Nullable.class"));
            Files.copy(nullableClass, jarOutput);
            jarOutput.closeEntry();
        }
        return outputJar;
    }

    private TestConfiguration latestAndroidConfiguration() {
        return data().stream()
                .filter(configuration -> configuration.platform.endsWith("-android"))
                .max(Comparator
                        .comparingInt((TestConfiguration configuration) -> configuration.version.version.major)
                        .thenComparingInt(configuration -> configuration.version.version.middle)
                        .thenComparingInt(configuration -> configuration.version.version.minor)
                        .thenComparing(configuration -> configuration.platform))
                .orElseThrow(() -> new IllegalArgumentException("No Android integration configuration selected"));
    }

    private List<ExtenderResource> gradleArtifactHandoffResources(
            Path fixtureDirectory,
            TestConfiguration configuration,
            boolean useJetifier) throws IOException {
        Path repositoryVersion = fixtureDirectory.resolve("repo/com/defold/test/handoff/1.0");
        Files.createDirectories(repositoryVersion);
        Path aar = repositoryVersion.resolve("handoff-1.0.aar");
        Path classifierJar = repositoryVersion.resolve("handoff-1.0-extras.jar");
        Path pom = repositoryVersion.resolve("handoff-1.0.pom");
        Files.copy(
                Path.of("test-data/ext/lib/android/LocalAar.aar"),
                aar,
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(
                createGradleHandoffClassifierJar(fixtureDirectory),
                classifierJar,
                StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(
                pom,
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>com.defold.test</groupId>\n" +
                "  <artifactId>handoff</artifactId>\n" +
                "  <version>1.0</version>\n" +
                "  <packaging>aar</packaging>\n" +
                "</project>\n",
                StandardCharsets.UTF_8);

        Path buildGradle = fixtureDirectory.resolve("build.gradle");
        Files.writeString(
                buildGradle,
                "repositories {\n" +
                "    maven { url uri(\"$rootDir/upload/ext/manifests/android/repo\") }\n" +
                "}\n" +
                "dependencies {\n" +
                "    implementation 'com.defold.test:handoff:1.0@aar'\n" +
                "    implementation 'com.defold.test:handoff:1.0:extras@jar'\n" +
                "}\n",
                StandardCharsets.UTF_8);
        Path appManifest = fixtureDirectory.resolve("app.manifest");
        Files.writeString(
                appManifest,
                "platforms:\n" +
                "    android:\n" +
                "        context:\n" +
                "            jetifier: " + useJetifier + "\n",
                StandardCharsets.UTF_8);

        String repositoryZipRoot = "ext/manifests/android/repo/com/defold/test/handoff/1.0/";
        return Lists.newArrayList(
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
                new FileExtenderResource("test-data/ext/ext.manifest"),
                new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
                new FileExtenderResource("test-data/ext/src/TestGradleHandoff.java"),
                new FileExtenderResource(
                        String.format("test-data/ext/lib/%s/libalib.a", configuration.platform)),
                new FileExtenderResource(buildGradle.toString(), "ext/manifests/android/build.gradle"),
                new FileExtenderResource(appManifest.toString(), "_app/app.manifest"),
                new FileExtenderResource(aar.toString(), repositoryZipRoot + aar.getFileName()),
                new FileExtenderResource(
                        classifierJar.toString(),
                        repositoryZipRoot + classifierJar.getFileName()),
                new FileExtenderResource(pom.toString(), repositoryZipRoot + pom.getFileName()));
    }

    @Test
    public void buildAndroidGradleArtifactHandoff(@org.junit.jupiter.api.io.TempDir Path fixtureDirectory)
            throws IOException, ExtenderClientException {
        Set<String> selectedPlatforms = TestUtils.selectedPlatforms();
        assumeTrue(
                selectedPlatforms.isEmpty()
                        || selectedPlatforms.stream().anyMatch(platform -> platform.endsWith("-android")),
                "This test is only run when an Android target is selected");
        TestConfiguration configuration = latestAndroidConfiguration();

        for (boolean useJetifier : List.of(true, false)) {
            Path buildFixture = Files.createDirectory(
                    fixtureDirectory.resolve(useJetifier ? "jetifier-on" : "jetifier-off"));
            File destination = doBuild(
                    gradleArtifactHandoffResources(buildFixture, configuration, useJetifier),
                    configuration);

            Set<String> dexClasses = getClassesDexClasses(destination);
            assertTrue(dexClasses.containsAll(List.of(
                    "Lcom/defold/GradleHandoffTest;",
                    "Lcom/defold/JarDep;",
                    "Lcom/defold/localaar/InnerJar;",
                    "Lcom/defold/localaar/LocalAar;",
                    "Lcom/defold/localaar/R;")));
            String expectedAnnotation = useJetifier
                    ? "Landroidx/annotation/Nullable;"
                    : "Landroid/support/annotation/Nullable;";
            String unexpectedAnnotation = useJetifier
                    ? "Landroid/support/annotation/Nullable;"
                    : "Landroidx/annotation/Nullable;";
            assertTrue(dexClasses.contains(expectedAnnotation));
            assertFalse(dexClasses.contains(unexpectedAnnotation));
            try (ZipFile zipFile = new ZipFile(destination)) {
                assertNotNull(zipFile.getEntry("assets/local_aar.txt"));
                assertNotNull(zipFile.getEntry(
                        "packages/com.defold.test-handoff-1.0.aar/res/values/strings.xml"));
                assertNotNull(zipFile.getEntry("gradle.lockfile"));
                assertNotNull(zipFile.getEntry("gradle.dependencytree"));
            }
        }
    }

    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildAndroidCheckClassesDex(TestConfiguration configuration) throws IOException, ExtenderClientException {
        assumeTrue(configuration.platform.contains("android"), "This test is only run for Android");

        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
                new FileExtenderResource("test-data/ext/ext.manifest"),
                new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
                new FileExtenderResource(String.format("test-data/ext/lib/%s/libalib.a", configuration.platform)),
                new FileExtenderResource("test-data/ext/lib/android/Dummy.jar"));

        File destination = doBuild(sourceFiles, configuration);

        List<String> classes = Arrays.asList(new String[]{"Lcom/defold/dummy/Dummy;"});
        assertTrue(checkClassesDexClasses(destination, classes));
    }

    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildAndroidCheckClassesMultiDex(TestConfiguration configuration) throws IOException, ExtenderClientException {
        assumeTrue(configuration.platform.contains("android"), "This test is only run for Android");

        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
                new FileExtenderResource("test-data/ext/ext.manifest"),
                new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
                new FileExtenderResource(String.format("test-data/ext/lib/%s/libalib.a", configuration.platform)),
                new FileExtenderResource("test-data/ext/lib/android/Dummy.jar"),
                new FileExtenderResource("test-data/ext/lib/android/VeryLarge1.jar"),
                new FileExtenderResource("test-data/ext/lib/android/VeryLarge2.jar"));

        File destination = doBuild(sourceFiles, configuration);

        List<String> classes = Arrays.asList(new String[]{"Lcom/defold/dummy/Dummy;"});
        assertTrue(checkClassesDexClasses(destination, classes));
    }

    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildAndroidCheckCompiledJava(TestConfiguration configuration) throws IOException, ExtenderClientException {
        assumeTrue(configuration.platform.contains("android"), "This test is only run for Android");

        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
                new FileExtenderResource("test-data/ext/ext.manifest"),
                new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
                new FileExtenderResource("test-data/ext/src/Test.java"),
                new FileExtenderResource(String.format("test-data/ext/lib/%s/libalib.a", configuration.platform)),
                new FileExtenderResource("test-data/ext/lib/android/Dummy.jar"));

        File destination = doBuild(sourceFiles, configuration);

        List<String> classes = Arrays.asList(new String[]{"Lcom/defold/dummy/Dummy;", "Lcom/defold/Test;"});
        assertTrue(checkClassesDexClasses(destination, classes));
    }

    /*
     * Test if a Java source can import classes specified in a supplied Jar file.
     */
    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildAndroidJavaJarDependency(TestConfiguration configuration) throws IOException, ExtenderClientException {
        assumeTrue(configuration.platform.contains("android"), "This test is only run for Android");

        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
                new FileExtenderResource("test-data/ext/ext.manifest"),
                new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
                new FileExtenderResource("test-data/ext/src/TestJar.java"),
                new FileExtenderResource(String.format("test-data/ext/lib/%s/libalib.a", configuration.platform)),
                new FileExtenderResource("test-data/ext/lib/android/JarDep.jar"));

        File destination = doBuild(sourceFiles, configuration);

        List<String> classes = Arrays.asList(new String[]{"Lcom/defold/JarDep;", "Lcom/defold/Test;"});
        assertTrue(checkClassesDexClasses(destination, classes));
    }

    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildAndroidJarWithMetaInf(TestConfiguration configuration) throws IOException, ExtenderClientException {
        assumeTrue(configuration.platform.contains("android"), "This test is only run for Android");

        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
                new FileExtenderResource("test-data/ext/ext.manifest"),
                new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
                new FileExtenderResource("test-data/ext/src/TestJar.java"),
                new FileExtenderResource(String.format("test-data/ext/lib/%s/libalib.a", configuration.platform)),
                new FileExtenderResource("test-data/ext/lib/android/JarDep.jar"),
                new FileExtenderResource("test-data/ext/lib/android/meta-inf.jar"));

        File destination = doBuild(sourceFiles, configuration);
        List<String> metaInfFiles = new ArrayList<>();
        try (ZipFile zipFile = new ZipFile(destination)) {
            final Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                final String entryName = entry.getName();
                if (!entry.isDirectory() && entryName.startsWith("META-INF")) {
                    metaInfFiles.add(entryName);
                }
            }
        }
        List<String> expected = List.of("META-INF/inner.folder/com.inner", "META-INF/inner.folder/io.foo.service.HTTPClient");
        assertTrue(expected.containsAll(metaInfFiles) &&  metaInfFiles.containsAll(expected));
    }

    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildAndroidRJar(TestConfiguration configuration) throws IOException, ExtenderClientException {
        assumeTrue(configuration.platform.contains("android") && configuration.version.version.isGreaterThan(1, 2, 174),
            "Defold version does not support Android resources compilation test."
        );

        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
                new FileExtenderResource("test-data/ext/ext.manifest"),
                new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
                new FileExtenderResource(String.format("test-data/ext/lib/%s/libalib.a", configuration.platform)));

        File destination = doBuild(sourceFiles, configuration);

        List<String> classes = Arrays.asList(new String[]{"Lcom/defold/extendertest/R;"});
        assertTrue(checkClassesDexClasses(destination, classes));
    }

    /*
     * Test that an .aar file shipped inside an extension (lib/android/*.aar) is unpacked and that
     * all of its parts are used: the classes of classes.jar and libs/*.jar end up in the dex and on
     * the javac classpath, the resources are compiled and returned in packages/, the package of the
     * AndroidManifest is passed to aapt2 as an extra package (which gives us its R class) and the
     * assets are returned to the client.
     */
    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildAndroidLocalAar(TestConfiguration configuration) throws IOException, ExtenderClientException {
        assumeTrue(configuration.platform.contains("android") && configuration.version.version.isGreaterThan(1, 2, 174),
            "Defold version does not support Android resources compilation test."
        );

        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
                new FileExtenderResource("test-data/ext/ext.manifest"),
                new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
                new FileExtenderResource("test-data/ext/src/TestAar.java"),
                new FileExtenderResource(String.format("test-data/ext/lib/%s/libalib.a", configuration.platform)),
                new FileExtenderResource("test-data/ext/lib/android/LocalAar.aar"));

        File destination = doBuild(sourceFiles, configuration);

        List<String> classes = Arrays.asList(new String[]{
            "Lcom/defold/localaar/LocalAar;",   // from classes.jar
            "Lcom/defold/localaar/InnerJar;",   // from libs/InnerJar.jar
            "Lcom/defold/localaar/R;",          // from the aapt2 extra package
            "Lcom/defold/Test;"});              // the extension source importing the two classes above
        assertTrue(checkClassesDexClasses(destination, classes));

        // The unpacked .aar is named "<extension>-<file>.aar", which also names its resource package
        try (ZipFile zipFile = new ZipFile(destination)) {
            assertNotEquals(null, zipFile.getEntry("packages/ext-LocalAar.aar/res/values/strings.xml"));
            assertNotEquals(null, zipFile.getEntry("assets/local_aar.txt"));
        }
    }

    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildEngineAppManifest(TestConfiguration configuration) throws IOException, ExtenderClientException {
        // Testing that using an app.manifest helps resolve issues with duplicate symbols
        // E.g. removing libs, symbols and jar files

        boolean isAndroid = configuration.platform.contains("android");

        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
                new FileExtenderResource("test-data/testproject_appmanifest/_app/app.manifest"),
                new FileExtenderResource("test-data/testproject_appmanifest/ext/ext.manifest"),
                new FileExtenderResource("test-data/testproject_appmanifest/ext/src/test_ext.cpp"),
                new FileExtenderResource(String.format("test-data/testproject_appmanifest/ext/lib/%s/%s", configuration.platform, getLibName(configuration.platform, "alib"))),
                new FileExtenderResource("test-data/testproject_appmanifest/ext2/ext.manifest"),
                new FileExtenderResource("test-data/testproject_appmanifest/ext2/src/test_ext.cpp"),
                new FileExtenderResource(String.format("test-data/testproject_appmanifest/ext2/lib/%s/%s", configuration.platform, getLibName(configuration.platform, "blib")))
        );

        if (isAndroid) {
            sourceFiles.add(new FileExtenderResource("test-data/testproject_appmanifest/ext2/lib/android/Dummy1.jar"));
            sourceFiles.add(new FileExtenderResource("test-data/testproject_appmanifest/ext2/lib/android/Dummy2.jar"));
        }

        doBuild(sourceFiles, configuration);
    }

    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildLinkWithoutDotLib(TestConfiguration configuration) throws IOException, ExtenderClientException {
        assumeTrue(configuration.platform.contains("win32") &&
                (configuration.version.version.isGreaterThan(1, 2, 134) || configuration.version.version.isVersion(0, 0, 0) ),
                "This test was written to test a Win32 link.exe -> clang transition");

        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/ext3/ext.manifest"),
                new FileExtenderResource("test-data/ext3/src/extension.cpp")
        );

        doBuild(sourceFiles, configuration);
    }

    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildEngineAppManifestVariant(TestConfiguration configuration) throws IOException, ExtenderClientException {
        // Testing that the variant parameter can be parse and processed properly.
        // This test requires that we have a debug.appmanifest present in the SDK and only
        // our test data SDK currently has that, so we can only test it on that version

        if (!configuration.platform.equals("a")) {
            return;
        }

        List<ExtenderResource> sourceFiles = Lists.newArrayList(
                new FileExtenderResource("test-data/testproject_appmanifest_variant/_app/app.manifest"));

        doBuild(sourceFiles, configuration);
    }

    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildEngineWithError(TestConfiguration configuration) throws IOException, ExtenderClientException {
        List<ExtenderResource> sourceFiles = Lists.newArrayList(
            new FileExtenderResource("test-data/ext/ext.manifest"),
            new FileExtenderResource("test-data/ext/include/ext.h"),
            new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
            new FileExtenderResource(String.format("test-data/ext/lib/%s/%s", configuration.platform, getLibName(configuration.platform, "alib"))),
            new FileExtenderResource("test-data/ext_error_extension/ext.manifest"),
            new FileExtenderResource("test-data/ext_error_extension/src/test_error_ext.cpp"),
            new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml")
        );

        assertThrows(ExtenderClientException.class, () -> {
            doBuild(sourceFiles, configuration);
        });
    }

    @ParameterizedTest(name = "[{index}] {displayName} {arguments}")
    @MethodSource("data")
    public void buildEngineWithDynamicLibs(TestConfiguration configuration) throws IOException, ExtenderClientException {
        List<ExtenderResource> sourceFiles = Lists.newArrayList(
            new FileExtenderResource("test-data/ext_dyn_libs/ext.manifest"),
            new FileExtenderResource("test-data/ext_dyn_libs/src/test_ext.cpp"),
            new FileExtenderResource(String.format("test-data/ext_dyn_libs/lib/%s/%s", configuration.platform, getDynamicLibName(configuration.platform, "dynamic_specific1"))),
            new FileExtenderResource("test-data/ext_dyn_libs2/ext.manifest"),
            new FileExtenderResource("test-data/ext_dyn_libs2/src/extension.cpp"),
            new FileExtenderResource(String.format("test-data/ext_dyn_libs2/lib/%s/%s", configuration.platform, getDynamicLibName(configuration.platform, "dynamic_specific2"))),
            new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml")
        );

        File destination = doBuild(sourceFiles, configuration);

        try (ZipFile zipFile = new ZipFile(destination)) {
            assertNotNull(zipFile.getEntry(getDynamicLibName(configuration.platform, "dynamic_specific1")));
            assertNotNull(zipFile.getEntry(getDynamicLibName(configuration.platform, "dynamic_specific2")));
        }
    }

    @Test
    public void testUnsupportedVersion() throws IOException, ExtenderClientException {
        TestConfiguration configuration = new TestConfiguration(new DefoldVersion("non-exist", new Version(2, 10, 1), new String[]{ "x86_64-linux" }) , "x86_64-linux");
        List<ExtenderResource> sourceFiles = Lists.newArrayList(
            new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
            new FileExtenderResource("test-data/ext2/ext.manifest"),
            new FileExtenderResource("test-data/ext2/src/test_ext.cpp"),
            new FileExtenderResource(String.format("test-data/ext2/lib/%s/%s", configuration.platform, getLibName(configuration.platform, "alib"))),
            new FileExtenderResource(String.format("test-data/ext2/lib/%s/%s", configuration.platform, getLibName(configuration.platform, "blib")))
        );

        ExtenderClientException exc = assertThrows(ExtenderClientException.class, () -> doBuild(sourceFiles, configuration));
        assertTrue(exc.getMessage().contains("Engine version 'non-exist' is not supported on the current server"));
    }

    @Test
    public void testUnsupportedPlatform() throws IOException, ExtenderClientException {
        TestConfiguration configuration = new TestConfiguration(new DefoldVersion("1aafd0a262ff40214ed7f51302d92fa587c607ef", new Version(1, 10, 4), new String[]{ "x86_64-platform" }) , "x86_64-platform");
        List<ExtenderResource> sourceFiles = Lists.newArrayList(
            new FileExtenderResource("test-data/AndroidManifest.xml", "AndroidManifest.xml"),
            new FileExtenderResource("test-data/ext2/ext.manifest"),
            new FileExtenderResource("test-data/ext2/src/test_ext.cpp")
        );

        ExtenderClientException exc = assertThrows(ExtenderClientException.class, () -> doBuild(sourceFiles, configuration));
        assertTrue(exc.getMessage().contains("Platform 'x86_64-platform' is not supported on the current server"));
    }
}
