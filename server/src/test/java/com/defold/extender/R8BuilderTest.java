package com.defold.extender;

import com.defold.extender.process.CommandLineTokenizer;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class R8BuilderTest {
    private static final String KEEP_RULE_REGEX = "(?i).+(\\.keep)$";

    private static File createJar(File jar, Map<String, String> entries) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(jar))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return jar;
    }

    private static File createJarWithBytes(File jar, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return jar;
    }

    private static File createJarWithDuplicateEntries(
            File jar,
            String entryName,
            String firstContents,
            String secondContents) throws IOException {
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(jar)) {
            for (String contents : List.of(firstContents, secondContents)) {
                byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
                ZipArchiveEntry entry = new ZipArchiveEntry(entryName);
                output.putArchiveEntry(entry);
                output.write(bytes);
                output.closeArchiveEntry();
            }
        }
        return jar;
    }

    private static void writeZipEntry(
            ZipOutputStream output,
            String name,
            byte[] contents,
            int method) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(method);
        if (method == ZipEntry.STORED) {
            CRC32 crc = new CRC32();
            crc.update(contents);
            entry.setSize(contents.length);
            entry.setCompressedSize(contents.length);
            entry.setCrc(crc.getValue());
        }
        output.putNextEntry(entry);
        output.write(contents);
        output.closeEntry();
    }

    private static List<String> zipEntryNames(File jar) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zipFile = new ZipFile(jar)) {
            zipFile.entries().asIterator().forEachRemaining(entry -> names.add(entry.getName()));
        }
        return names;
    }

    private static byte[] createMinimalClassFile(String internalName) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0xCAFEBABE);
            output.writeShort(0); // minor_version
            output.writeShort(52); // major_version (Java 8)
            output.writeShort(5); // constant_pool_count
            output.writeByte(1); // CONSTANT_Utf8
            output.writeUTF(internalName);
            output.writeByte(7); // CONSTANT_Class
            output.writeShort(1);
            output.writeByte(1); // CONSTANT_Utf8
            output.writeUTF("java/lang/Object");
            output.writeByte(7); // CONSTANT_Class
            output.writeShort(3);
            output.writeShort(0x0021); // public, super
            output.writeShort(2); // this_class
            output.writeShort(4); // super_class
            output.writeShort(0); // interfaces_count
            output.writeShort(0); // fields_count
            output.writeShort(0); // methods_count
            output.writeShort(0); // attributes_count
        }
        return bytes.toByteArray();
    }

    private static byte[] createOversizedClassFileHeader() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(R8Builder.MAX_CLASSFILE_HEADER_BYTES + 65536);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0xCAFEBABE);
            output.writeShort(0);
            output.writeShort(52);
            int utf8Count = R8Builder.MAX_CLASSFILE_HEADER_BYTES / 65538 + 2;
            output.writeShort(utf8Count + 1);
            String padding = "a".repeat(65535);
            for (int index = 0; index < utf8Count; ++index) {
                output.writeByte(1);
                output.writeUTF(padding);
            }
        }
        return bytes.toByteArray();
    }

    private static String sanitizedRuleBody(File rule) throws IOException {
        String contents = Files.readString(rule.toPath());
        assertTrue(contents.startsWith("-basedirectory "));
        int firstLineEnd = contents.indexOf('\n');
        assertTrue(firstLineEnd >= 0);
        return contents.substring(firstLineEnd + 1);
    }

    private static File sanitizedRuleBase(File rule) throws IOException {
        String firstLine = Files.readString(rule.toPath()).lines().findFirst().orElseThrow();
        int quoteStart = firstLine.indexOf('"');
        char quote = '"';
        if (quoteStart < 0) {
            quoteStart = firstLine.indexOf('\'');
            quote = '\'';
        }
        assertTrue(quoteStart >= 0);
        int quoteEnd = firstLine.indexOf(quote, quoteStart + 1);
        assertTrue(quoteEnd > quoteStart);
        return new File(firstLine.substring(quoteStart + 1, quoteEnd));
    }

    // Verifies that only _app/app.keep opts into R8; app.pro and extension consumer rules alone do not.
    @Test
    public void testOnlyAppKeepRequestsR8(@TempDir File uploadDir) throws Exception {
        File appDir = new File(uploadDir, "_app");
        assertTrue(appDir.mkdirs());
        Files.writeString(new File(appDir, "app.pro").toPath(), "-keep class Legacy");
        assertFalse(R8Builder.isRequested(uploadDir));

        File extensionRulesDir = new File(uploadDir, "extension/manifests/android");
        assertTrue(extensionRulesDir.mkdirs());
        Files.writeString(
                new File(extensionRulesDir, "consumer.keep").toPath(),
                "-keep class ExtensionConsumer");
        assertFalse(R8Builder.isRequested(uploadDir));

        Files.writeString(new File(appDir, "app.keep").toPath(), "-keep class Current");
        assertTrue(R8Builder.isRequested(uploadDir));
    }

    // Verifies that a build without _app/app.keep skips R8 before requiring aapt rules or executing its command.
    @Test
    public void testNoAppKeepSkipsR8WithoutAaptRules(@TempDir File tempDir) throws Exception {
        File uploadDir = new File(tempDir, "upload");
        File buildDir = new File(tempDir, "build");
        assertTrue(uploadDir.mkdirs());
        assertTrue(buildDir.mkdirs());
        PlatformConfig config = new PlatformConfig();

        R8Builder builder = new R8Builder(
                uploadDir,
                buildDir,
                config,
                List.of(),
                new HashMap<>(),
                21,
                new TemplateExecutor(),
                (command, context) -> {
                    throw new AssertionError("R8 command must not execute without app.keep");
                });

        assertNull(builder.build(List.of(), Map.of(), null));
    }

    // Verifies that R8/aapt commands preserve exact quoting and literals, conditional rule flags, and data resources.
    @Test
    public void testConfiguredCommandPreservesQuotedPathSpecialCharacters() throws Exception {
        File root = new File("test-data");
        File sdk = new File(root, "sdk/a/defoldsdk");
        Configuration config = Extender.loadYaml(
                root,
                new File(sdk, "extender/build.yml"),
                Configuration.class);
        PlatformConfig android = ExtenderTest.mergePlatformConfig(config, "armv7-android");

        String r8Jar = "/tmp/R8 tools/{{literal}}/r8\\\"tool.jar";
        String androidJar = "/tmp/Android SDK/android\\lib.jar";
        String mapping = "/tmp/Output dir/mapping\\symbols.txt";
        String dexDir = "/tmp/Output dir/dex {{literal}} & files";
        String appRules = "/tmp/Rules dir/app\"rules.keep";
        String aaptRules = "/tmp/Rules dir/aapt&generated.keep";
        String programJar = "/tmp/Program jars/game\\code.jar";

        Map<String, Object> context = new HashMap<>();
        context.put("env.R8", r8Jar);
        context.put("env.LIBRARYJAR", androidJar);
        context.put("minAndroidSdkVersion", 21);
        context.put("mapping", mapping);
        context.put("classes_dex_dir", dexDir);
        context.put("rules", List.of(appRules, aaptRules));
        context.put("jars", List.of(programJar));

        String rendered = new TemplateExecutor().executeOnceWithoutLogging(
                android.r8Cmd,
                R8Builder.createR8CommandContext(context));
        List<String> arguments = CommandLineTokenizer.parse(rendered);

        assertFalse(rendered.contains("&amp;"));
        assertFalse(arguments.contains("--no-data-resources"));
        assertTrue(rendered.contains("{{literal}}"));
        assertTrue(arguments.contains(r8Jar));
        assertTrue(arguments.contains(androidJar));
        assertTrue(arguments.contains(mapping));
        assertTrue(arguments.contains(dexDir));
        assertTrue(arguments.contains(appRules));
        assertTrue(arguments.contains(aaptRules));
        assertTrue(arguments.contains(programJar));

        context.put("env.ANDROID_BUILD_TOOLS_PATH", "/tmp/Android SDK/build tools");
        context.put("manifestFile", "/tmp/Manifest dir/AndroidManifest.xml");
        context.put("outJavaDirectory", "/tmp/Generated Java");
        context.put("outApkFile", "/tmp/Compiled resources/resources.apk");
        context.put("resourceIdsFile", "/tmp/Compiled resources/resource ids.txt");
        context.put("aaptKeepRules", aaptRules);
        context.put("resourceListFile", "/tmp/Compiled resources/list.txt");
        context.put("extraPackages", "");
        context.put("useR8", false);
        String d8AaptCommand = new TemplateExecutor().execute(android.aapt2linkCmd, context);
        assertFalse(d8AaptCommand.contains("--proguard"));

        context.put("useR8", true);
        String r8AaptCommand = new TemplateExecutor().execute(android.aapt2linkCmd, context);
        assertTrue(r8AaptCommand.contains("--proguard"));
        assertTrue(CommandLineTokenizer.parse(r8AaptCommand).contains(aaptRules));
    }

    // Verifies that requested R8 builds fail before command execution when aapt-generated keep rules are missing.
    @Test
    public void testMissingAaptGeneratedRulesIsAnError(@TempDir File tempDir) throws Exception {
        File uploadDir = new File(tempDir, "upload");
        File appDir = new File(uploadDir, "_app");
        File buildDir = new File(tempDir, "build");
        assertTrue(appDir.mkdirs());
        assertTrue(buildDir.mkdirs());
        Files.writeString(new File(appDir, "app.keep").toPath(), "-keep class App");
        PlatformConfig config = new PlatformConfig();
        config.r8Cmd = "r8-command";
        config.r8Version = "8.13.19";
        boolean[] commandExecuted = {false};
        R8Builder builder = new R8Builder(
                uploadDir,
                buildDir,
                config,
                List.of(),
                new HashMap<>(),
                21,
                new TemplateExecutor(),
                (command, context) -> commandExecuted[0] = true);

        ExtenderException exception = assertThrows(
                ExtenderException.class,
                () -> builder.build(
                        List.of(),
                        Map.of(),
                        new File(buildDir, "missing-aapt-generated.keep")));
        assertTrue(exception.getMessage().contains("aapt-generated.keep"));
        assertFalse(commandExecuted[0]);
    }

    // Verifies that aapt-generated keep rules pass the filesystem-directive policy before R8 can execute.
    @Test
    public void testAaptGeneratedRulesUseFilesystemPolicy(@TempDir File tempDir) throws Exception {
        File uploadDir = new File(tempDir, "upload");
        File appDir = new File(uploadDir, "_app");
        File buildDir = new File(tempDir, "build");
        assertTrue(appDir.mkdirs());
        assertTrue(buildDir.mkdirs());
        Files.writeString(new File(appDir, "app.keep").toPath(), "-keep class App");
        File aaptRules = new File(buildDir, "aapt-generated.keep");
        Files.writeString(aaptRules.toPath(), "}-include /etc/hosts");
        PlatformConfig config = new PlatformConfig();
        config.r8Cmd = "r8-command";
        config.r8Version = "8.13.19";
        boolean[] commandExecuted = {false};
        R8Builder builder = new R8Builder(
                uploadDir,
                buildDir,
                config,
                List.of(),
                new HashMap<>(),
                24,
                new TemplateExecutor(),
                (command, context) -> commandExecuted[0] = true);

        ExtenderException exception = assertThrows(
                ExtenderException.class,
                () -> builder.build(List.of(), Map.of(), aaptRules));

        assertTrue(exception.getMessage().contains("forbidden filesystem directive"));
        assertFalse(commandExecuted[0]);
    }

    // Verifies that missing SDK R8 configuration is reported before secondary missing-rule validation.
    @Test
    public void testMissingR8ConfigurationIsReportedBeforeMissingAaptRules(@TempDir File tempDir) throws Exception {
        File uploadDir = new File(tempDir, "upload");
        File appDir = new File(uploadDir, "_app");
        File buildDir = new File(tempDir, "build");
        assertTrue(appDir.mkdirs());
        assertTrue(buildDir.mkdirs());
        Files.writeString(new File(appDir, "app.keep").toPath(), "-keep class App");
        R8Builder builder = new R8Builder(
                uploadDir,
                buildDir,
                new PlatformConfig(),
                List.of(),
                new HashMap<>(),
                21,
                new TemplateExecutor(),
                (command, context) -> {
                    throw new AssertionError("R8 command must not execute with missing configuration");
                });

        ExtenderException exception = assertThrows(
                ExtenderException.class,
                () -> builder.build(List.of(), Map.of(), null));
        assertTrue(exception.getMessage().contains("does not provide r8Cmd and r8Version"));
        assertFalse(exception.getMessage().contains("aapt-generated.keep"));
    }

    // Verifies that a blank resolved R8 path produces a clear configuration error without invoking R8.
    @Test
    public void testBlankResolvedR8EnvironmentProducesClearConfigurationError(@TempDir File tempDir) throws Exception {
        File uploadDir = new File(tempDir, "upload");
        File appDir = new File(uploadDir, "_app");
        File buildDir = new File(tempDir, "build");
        assertTrue(appDir.mkdirs());
        assertTrue(buildDir.mkdirs());
        Files.writeString(new File(appDir, "app.keep").toPath(), "-keep class App");
        File aaptRules = new File(buildDir, "aapt-generated.keep");
        Files.writeString(aaptRules.toPath(), "-keep class AaptGenerated");
        PlatformConfig config = new PlatformConfig();
        config.env.put("R8", "{{env.ANDROID_R8}}");
        config.r8Cmd = "java -cp \"{{{env.R8}}}\" com.android.tools.r8.R8";
        config.r8Version = "8.13.19";
        Map<String, Object> commandContext = new HashMap<>();
        commandContext.put("env.R8", " ");

        R8Builder builder = new R8Builder(
                uploadDir,
                buildDir,
                config,
                List.of(),
                commandContext,
                21,
                new TemplateExecutor(),
                (command, context) -> {
                    throw new AssertionError("R8 command must not execute with unresolved configuration");
                });

        ExtenderException exception = assertThrows(
                ExtenderException.class,
                () -> builder.build(List.of(), Map.of(), aaptRules));
        assertTrue(exception.getOutput().contains("does not provide r8Cmd and r8Version"));
    }

    // Verifies that absent R8 command fields and malformed pinned versions are rejected with distinct errors.
    @Test
    public void testMissingConfigurationIsAnError() {
        ExtenderException missing = assertThrows(
                ExtenderException.class,
                () -> R8Builder.validateConfiguration(null, "8.13.19"));
        assertTrue(missing.getMessage().contains("does not provide r8Cmd and r8Version"));

        ExtenderException invalid = assertThrows(
                ExtenderException.class,
                () -> R8Builder.validateConfiguration("r8", "8.13-dev"));
        assertTrue(invalid.getMessage().contains("Invalid r8Version"));
    }

    // Verifies that supported R8 rule-directory ranges and suffixes use inclusive from and exclusive upto matching.
    @Test
    public void testRuleVersionRanges() {
        assertTrue(R8Builder.isApplicableRuleDirectory("r8", "8.13.19"));
        assertTrue(R8Builder.isApplicableRuleDirectory("r8-from-8.13.19", "8.13.19"));
        assertTrue(R8Builder.isApplicableRuleDirectory("r8-from-8.13.18-dev", "8.13.19"));
        assertTrue(R8Builder.isApplicableRuleDirectory("r8-from-0.0.0-arbitrary", "8.13.19"));
        assertTrue(R8Builder.isApplicableRuleDirectory("r8-upto-8.13.20", "8.13.19"));
        assertTrue(R8Builder.isApplicableRuleDirectory("r8-from-8.0.0-upto-9.0.0", "8.13.19"));
        assertFalse(R8Builder.isApplicableRuleDirectory("r8-upto-8.13.19", "8.13.19"));
        assertFalse(R8Builder.isApplicableRuleDirectory("r8-from-8.13.20", "8.13.19"));
        assertFalse(R8Builder.isApplicableRuleDirectory("r8-from-invalid", "8.13.19"));
        assertFalse(R8Builder.isApplicableRuleDirectory("r8foo", "8.13.19"));
    }

    // Verifies that targeted-rule selection is deterministic and falls back to legacy META-INF/proguard rules when needed.
    @Test
    public void testSelectTargetedRulesWithLegacyFallback(@TempDir File tempDir) throws Exception {
        Map<String, String> entries = new HashMap<>();
        entries.put("META-INF/proguard/legacy.pro", "-keep class Legacy");
        entries.put("META-INF/com.android.tools/r8-upto-8.0.0/old.keep", "-keep class Old");
        entries.put("META-INF/com.android.tools/r8-from-8.0.0/z.keep", "-keep class Z");
        entries.put("META-INF/com.android.tools/r8-from-8.0.0/a.keep", "-keep class A");
        File targetedJar = createJar(new File(tempDir, "targeted.jar"), entries);

        assertEquals(
                List.of(
                        "META-INF/com.android.tools/r8-from-8.0.0/a.keep",
                        "META-INF/com.android.tools/r8-from-8.0.0/z.keep"),
                R8Builder.selectEmbeddedRuleEntries(targetedJar, "8.13.19"));

        entries.remove("META-INF/com.android.tools/r8-from-8.0.0/a.keep");
        entries.remove("META-INF/com.android.tools/r8-from-8.0.0/z.keep");
        File legacyJar = createJar(new File(tempDir, "legacy.jar"), entries);
        assertEquals(
                List.of("META-INF/proguard/legacy.pro"),
                R8Builder.selectEmbeddedRuleEntries(legacyJar, "8.13.19"));
    }

    // Verifies that pinned R8 handles prerelease, arbitrary, future, and lookalike rule-directory suffixes correctly.
    @Test
    public void testSelectTargetedRulesMatchesPinnedR8SuffixHandling(@TempDir File tempDir)
            throws Exception {
        File jar = createJar(
                new File(tempDir, "suffixes.jar"),
                Map.of(
                        "META-INF/com.android.tools/r8-from-8.13.18-dev/prerelease.keep",
                        "-keep class Prerelease",
                        "META-INF/com.android.tools/r8-from-0.0.0-arbitrary/malformed.keep",
                        "-keep class MalformedSuffix",
                        "META-INF/com.android.tools/r8-from-9.0.0-future/future.keep",
                        "-keep class Future",
                        "META-INF/com.android.tools/r8foo/not-a-rule.txt",
                        "metadata"));

        assertEquals(
                List.of(
                        "META-INF/com.android.tools/r8-from-0.0.0-arbitrary/malformed.keep",
                        "META-INF/com.android.tools/r8-from-8.13.18-dev/prerelease.keep"),
                R8Builder.selectEmbeddedRuleEntries(jar, "8.13.19"));
    }

    // Verifies that duplicate embedded consumer-rule entry names are rejected instead of being applied ambiguously.
    @Test
    public void testDuplicateEmbeddedRuleNamesAreRejected(@TempDir File tempDir) throws Exception {
        File jar = createJarWithDuplicateEntries(
                new File(tempDir, "duplicate-rules.jar"),
                "META-INF/proguard/rules.pro",
                "-keep class First",
                "-keep class Second");

        ExtenderException exception = assertThrows(
                ExtenderException.class,
                () -> R8Builder.selectEmbeddedRuleEntries(jar, "8.13.19"));

        assertTrue(exception.getMessage().contains("Duplicate embedded R8 rule entry"));
    }

    // Verifies that stripping consumer rules removes every supported rule namespace while preserving all other JAR entries.
    @Test
    public void testStripEmbeddedRulesRetainsAllOtherRawJarEntries(@TempDir File tempDir)
            throws Exception {
        File original = createJar(
                new File(tempDir, "original.jar"),
                new LinkedHashMap<>(Map.of(
                        "classes/example/Retained.class", "class bytes",
                        "assets/data.bin", "retained data",
                        "META-INF/proguard/legacy.pro", "-keep class Legacy",
                        "META-INF/com.android.tools/r8/rules.keep", "-keep class R8",
                        "META-INF/com.android.tools/r8-from-0.0.0-arbitrary/hidden.pro", "-keep class Hidden",
                        "META-INF/com.android.tools/r8-upto-99.0.0/future.keep", "-keep class Future",
                        "META-INF/com.android.tools/r8foo/not-a-rule.txt", "retained r8 metadata",
                        "META-INF/com.android.tools/lint/model.xml", "retained lint metadata",
                        "META-INF/proguarded/not-a-rule.txt", "retained proguard metadata")));
        File requestedOutput = new File(tempDir, "stripped/program-0000.jar");

        File stripped = R8Builder.stripEmbeddedRuleEntries(original, requestedOutput);

        assertEquals(requestedOutput.getCanonicalFile(), stripped.getCanonicalFile());
        assertEquals(
                List.of(
                        "META-INF/com.android.tools/lint/model.xml",
                        "META-INF/com.android.tools/r8foo/not-a-rule.txt",
                        "META-INF/proguarded/not-a-rule.txt",
                        "assets/data.bin",
                        "classes/example/Retained.class"),
                zipEntryNames(stripped).stream().sorted().toList());
        try (ZipFile originalZip = new ZipFile(original);
             ZipFile strippedZip = new ZipFile(stripped)) {
            ZipEntry originalClass = originalZip.getEntry("classes/example/Retained.class");
            ZipEntry strippedClass = strippedZip.getEntry("classes/example/Retained.class");
            assertEquals(originalClass.getMethod(), strippedClass.getMethod());
            assertEquals(originalClass.getCrc(), strippedClass.getCrc());
            assertEquals(originalClass.getCompressedSize(), strippedClass.getCompressedSize());
            assertEquals(
                    "class bytes",
                    new String(strippedZip.getInputStream(strippedClass).readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    // Verifies that a JAR without embedded consumer rules is returned unchanged and no replacement JAR is written.
    @Test
    public void testStripEmbeddedRulesReturnsOriginalWhenNoRulesExist(@TempDir File tempDir)
            throws Exception {
        File original = createJar(
                new File(tempDir, "original.jar"),
                Map.of(
                        "classes/example/Retained.class", "class bytes",
                        "META-INF/com.android.tools/r8foo/not-a-rule.txt", "metadata"));
        File requestedOutput = new File(tempDir, "stripped/program-0000.jar");

        File result = R8Builder.stripEmbeddedRuleEntries(original, requestedOutput);

        assertEquals(original.getCanonicalFile(), result.getCanonicalFile());
        assertFalse(requestedOutput.exists());
    }

    // Verifies that rule stripping preserves directory entries, compression methods, and retained entry contents.
    @Test
    public void testStripEmbeddedRulesPreservesStoredDeflatedAndDirectoryEntries(@TempDir File tempDir)
            throws Exception {
        File original = new File(tempDir, "mixed.jar");
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(original))) {
            writeZipEntry(output, "assets/", new byte[0], ZipEntry.STORED);
            writeZipEntry(
                    output,
                    "assets/stored.bin",
                    "stored bytes".getBytes(StandardCharsets.UTF_8),
                    ZipEntry.STORED);
            writeZipEntry(
                    output,
                    "assets/deflated.bin",
                    "deflated bytes".getBytes(StandardCharsets.UTF_8),
                    ZipEntry.DEFLATED);
            writeZipEntry(output, "META-INF/proguard/", new byte[0], ZipEntry.STORED);
            writeZipEntry(
                    output,
                    "META-INF/proguard/rules.pro",
                    "-keep class Removed".getBytes(StandardCharsets.UTF_8),
                    ZipEntry.DEFLATED);
        }

        File stripped = R8Builder.stripEmbeddedRuleEntries(
                original,
                new File(tempDir, "stripped/mixed.jar"));

        assertEquals(
                List.of(
                        "META-INF/proguard/",
                        "assets/",
                        "assets/deflated.bin",
                        "assets/stored.bin"),
                zipEntryNames(stripped).stream().sorted().toList());
        try (ZipFile zipFile = new ZipFile(stripped)) {
            ZipEntry stored = zipFile.getEntry("assets/stored.bin");
            ZipEntry deflated = zipFile.getEntry("assets/deflated.bin");
            assertEquals(ZipEntry.STORED, stored.getMethod());
            assertEquals(ZipEntry.DEFLATED, deflated.getMethod());
            assertEquals(
                    "stored bytes",
                    new String(zipFile.getInputStream(stored).readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(
                    "deflated bytes",
                    new String(zipFile.getInputStream(deflated).readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    // Verifies that JAR discovery supports both locally unpacked AARs and AGP exploded-AAR directory layouts.
    @Test
    public void testAndroidPackageJarsSupportLocalAndAgpExplodedLayouts(@TempDir File tempDir)
            throws Exception {
        File localAar = new File(tempDir, "local.aar");
        assertTrue(new File(localAar, "libs").mkdirs());
        File localClasses = new File(localAar, "classes.jar");
        File localLibrary = new File(localAar, "libs/local-library.jar");
        Files.writeString(localClasses.toPath(), "classes");
        Files.writeString(localLibrary.toPath(), "library");

        File explodedAar = new File(tempDir, "jetified-library");
        assertTrue(new File(explodedAar, "jars/libs").mkdirs());
        File explodedClasses = new File(explodedAar, "jars/classes.jar");
        File explodedLibrary = new File(explodedAar, "jars/libs/embedded-library.jar");
        Files.writeString(explodedClasses.toPath(), "classes");
        Files.writeString(explodedLibrary.toPath(), "library");

        assertEquals(localClasses, R8Builder.getAndroidPackageClassesJar(localAar));
        assertEquals(explodedClasses, R8Builder.getAndroidPackageClassesJar(explodedAar));
        assertEquals(
                List.of(localClasses.getAbsolutePath(), localLibrary.getAbsolutePath()),
                R8Builder.getAndroidPackageJars(localAar));
        assertEquals(
                List.of(explodedClasses.getAbsolutePath(), explodedLibrary.getAbsolutePath()),
                R8Builder.getAndroidPackageJars(explodedAar));
    }

    // Verifies that JAR/AAR consumer rules have deterministic ordering, targeted precedence, and proguard.txt fallback.
    @Test
    public void testCollectJarAndAarConsumerRulesDeterministically(@TempDir File tempDir) throws Exception {
        File standaloneJar = createJar(
                new File(tempDir, "standalone.jar"),
                Map.of(
                        "META-INF/proguard/legacy.pro", "-keep class JarLegacy",
                        "META-INF/com.android.tools/r8-from-8.0.0/rules.keep", "-keep class JarTarget"));

        File targetedAar = new File(tempDir, "jetified-targeted");
        assertTrue(new File(targetedAar, "jars").mkdirs());
        File targetedClasses = createJar(
                new File(targetedAar, "jars/classes.jar"),
                Map.of("META-INF/com.android.tools/r8/rules.keep", "-keep class AarTarget"));
        Files.writeString(new File(targetedAar, "proguard.txt").toPath(), "-keep class AarRootIgnored");

        File legacyAar = new File(tempDir, "legacy.aar");
        assertTrue(legacyAar.mkdirs());
        File legacyClasses = createJar(new File(legacyAar, "classes.jar"), Map.of("example/Legacy.class", "class"));
        Files.writeString(new File(legacyAar, "proguard.txt").toPath(), "-keep class AarLegacy");

        List<String> rules = R8Builder.collectConsumerRules(
                List.of(targetedClasses.getAbsolutePath(), standaloneJar.getAbsolutePath(), legacyClasses.getAbsolutePath()),
                List.of(targetedAar, legacyAar),
                "8.13.19",
                new File(tempDir, "rules"));
        List<String> contents = new ArrayList<>();
        for (String rule : rules) {
            contents.add(sanitizedRuleBody(new File(rule)));
        }

        assertEquals(
                List.of("-keep class AarTarget", "-keep class JarTarget", "-keep class AarLegacy"),
                contents);
    }

    // Verifies that valid bytecode names generate protection rules across Unicode, package-info, and module edge cases.
    @Test
    public void testGeneratedRulesProtectValidJarClasses(@TempDir File tempDir) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("com/example/Outer.class", createMinimalClassFile("com/example/Outer"));
        entries.put("com/example/Outer$Inner.class", createMinimalClassFile("com/example/Outer$Inner"));
        entries.put("com/example/Foo-Bar.class", createMinimalClassFile("com/example/Foo-Bar"));
        entries.put("com/example/9Patch.class", createMinimalClassFile("com/example/9Patch"));
        entries.put("com/example/Über.class", createMinimalClassFile("com/example/Über"));
        entries.put("com/example/Supplementary.class", createMinimalClassFile("com/example/𐐀Name"));
        entries.put("com/example/NonJavaIdentifier.class", createMinimalClassFile("com/example/¡Name"));
        entries.put("com/example/Combining.class", createMinimalClassFile("com/example/éName"));
        entries.put("com/example/UnicodeSpace.class", createMinimalClassFile("com/example/ Name"));
        entries.put("com/example/UnsupportedSpace.class", createMinimalClassFile("com/example/ Name"));
        entries.put("META-INF/versions/9/com/example/Versioned.class", "not parsed".getBytes(StandardCharsets.UTF_8));
        entries.put("decoy/NotModule.class", createMinimalClassFile("module-info"));
        entries.put("module-info.class", createMinimalClassFile("com/example/ActuallyClass"));
        entries.put("com/example/package-info.class", createMinimalClassFile("com/example/package-info"));
        File jar = createJarWithBytes(new File(tempDir, "extension.jar"), entries);
        File rules = R8Builder.writeProtectedJarKeepRules(
                List.of(jar.getAbsolutePath()),
                new File(tempDir, "generated.keep"));
        String contents = Files.readString(rules.toPath());

        assertTrue(contents.startsWith(
                "-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,MethodParameters,Exceptions"));
        assertTrue(contents.contains("-keep class com.example.Outer { *; }"));
        assertTrue(contents.contains("-keep class com.example.Outer$Inner { *; }"));
        assertTrue(contents.contains("-keep class com.example.Foo-Bar { *; }"));
        assertTrue(contents.contains("-keep class com.example.9Patch { *; }"));
        assertTrue(contents.contains("-keep class com.example.Über { *; }"));
        assertTrue(contents.contains("-keep class com.example.𐐀Name { *; }"));
        assertTrue(contents.contains("-keep class com.example.¡Name { *; }"));
        assertTrue(contents.contains("-keep class com.example.éName { *; }"));
        assertTrue(contents.contains("-keep class com.example.?Name { *; }"));
        assertFalse(contents.contains("com.example. Name"));
        assertFalse(contents.contains("com.example. Name"));
        assertTrue(contents.contains("-keep class com.example.ActuallyClass { *; }"));
        assertTrue(contents.contains("-keep class com.example.package-info { *; }"));
        assertFalse(contents.contains("Versioned"));
        assertFalse(contents.contains("module-info"));
    }

    // Verifies that generated keep rules trust the class file's internal name rather than its potentially misleading ZIP path.
    @Test
    public void testGeneratedRulesUseClassFileNameInsteadOfZipEntryName(@TempDir File tempDir)
            throws Exception {
        File jar = createJarWithBytes(
                new File(tempDir, "mismatched.jar"),
                Map.of("x/Entry.class", createMinimalClassFile("test/LambdaProbe")));

        String contents = Files.readString(R8Builder.writeProtectedJarKeepRules(
                List.of(jar.getAbsolutePath()),
                new File(tempDir, "generated.keep")).toPath());

        assertTrue(contents.contains("-keep class test.LambdaProbe { *; }"));
        assertFalse(contents.contains("x.Entry"));
    }

    // Verifies that class-name metacharacters and directive-like text are neutralized in generated keep patterns.
    @Test
    public void testGeneratedRulesNeutralizeClassNameMetacharacters(@TempDir File tempDir)
            throws Exception {
        String internalName = "safe/Bad#\"'{}()\\\n\r\t @*!?%,:=~<>!&|-include /etc/hosts";
        File jar = createJarWithBytes(
                new File(tempDir, "metacharacters.jar"),
                Map.of("safe/Decoy.class", createMinimalClassFile(internalName)));

        String contents = Files.readString(R8Builder.writeProtectedJarKeepRules(
                List.of(jar.getAbsolutePath()),
                new File(tempDir, "generated.keep")).toPath());
        List<String> lines = contents.lines().toList();
        assertEquals(2, lines.size());
        String prefix = "-keep class ";
        String suffix = " { *; }";
        assertTrue(lines.get(1).startsWith(prefix));
        assertTrue(lines.get(1).endsWith(suffix));
        String pattern = lines.get(1).substring(prefix.length(), lines.get(1).length() - suffix.length());

        assertTrue(pattern.startsWith("safe.Bad"));
        assertTrue(pattern.endsWith(".etc.hosts"));
        assertTrue(pattern.contains("?"));
        assertFalse(pattern.contains("#"));
        assertFalse(pattern.contains("\""));
        assertFalse(pattern.contains("'"));
        assertFalse(pattern.contains("{"));
        assertFalse(pattern.contains("}"));
        assertFalse(pattern.contains("\\"));
        assertFalse(pattern.contains("/"));
        assertFalse(pattern.codePoints().anyMatch(Character::isWhitespace));
    }

    // Verifies that a malicious ZIP entry name cannot inject directives when the bytecode contains a safe class name.
    @Test
    public void testGeneratedRulesIgnoreMaliciousZipEntryName(@TempDir File tempDir)
            throws Exception {
        File jar = createJarWithBytes(
                new File(tempDir, "malicious-entry.jar"),
                Map.of(
                        "decoy/Entry.class\n-include /etc/hosts.class",
                        createMinimalClassFile("safe/Actual")));

        String contents = Files.readString(R8Builder.writeProtectedJarKeepRules(
                List.of(jar.getAbsolutePath()),
                new File(tempDir, "generated.keep")).toPath());

        assertTrue(contents.contains("-keep class safe.Actual { *; }"));
        assertFalse(contents.contains("-include"));
        assertFalse(contents.contains("/etc/hosts"));
        assertEquals(2, contents.lines().count());
    }

    // Verifies that malformed JVM internal class names are rejected without leaving a partial generated rules file.
    @ParameterizedTest
    @ValueSource(strings = {"p//Foo", "p/Foo.Bar", "p/Foo;Bar", "p/Foo[Bar", "/Foo", "Foo/"})
    public void testGeneratedRulesRejectMalformedInternalClassNames(
            String internalName,
            @TempDir File tempDir) throws Exception {
        File jar = createJarWithBytes(
                new File(tempDir, "malformed-name.jar"),
                Map.of("p/Entry.class", createMinimalClassFile(internalName)));
        File output = new File(tempDir, "generated.keep");

        ExtenderException exception = assertThrows(
                ExtenderException.class,
                () -> R8Builder.writeProtectedJarKeepRules(List.of(jar.getAbsolutePath()), output));

        assertTrue(exception.getOutput().contains("Invalid class name"));
        assertFalse(output.exists());
    }

    // Verifies that malformed class-file bytes produce a precise error and no generated rules output.
    @Test
    public void testGeneratedRulesRejectMalformedClassFile(@TempDir File tempDir) throws Exception {
        File jar = createJar(
                new File(tempDir, "malformed-class.jar"),
                Map.of("p/Broken.class", "not a class file"));
        File output = new File(tempDir, "generated.keep");

        ExtenderException exception = assertThrows(
                ExtenderException.class,
                () -> R8Builder.writeProtectedJarKeepRules(List.of(jar.getAbsolutePath()), output));

        assertTrue(exception.getOutput().contains("Failed to read class file"));
        assertTrue(exception.getMessage().contains("Invalid class file magic"));
        assertFalse(output.exists());
    }

    // Verifies that class-file header inflation is bounded before a compressed input can exhaust memory.
    @Test
    public void testGeneratedRulesBoundClassFileHeaderDecompression(@TempDir File tempDir)
            throws Exception {
        File jar = createJarWithBytes(
                new File(tempDir, "oversized-class-header.jar"),
                Map.of("p/Oversized.class", createOversizedClassFileHeader()));
        File output = new File(tempDir, "generated.keep");

        ExtenderException exception = assertThrows(
                ExtenderException.class,
                () -> R8Builder.writeProtectedJarKeepRules(List.of(jar.getAbsolutePath()), output));

        assertTrue(exception.getMessage().contains("Class file header exceeds"));
        assertFalse(output.exists());
    }

    // Verifies that generated extension rules enforce the maximum number of enumerated classes.
    @Test
    public void testGeneratedRuleClassCountIsBoundedDuringJarEnumeration(@TempDir File tempDir)
            throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        byte[] classFile = createMinimalClassFile("p/Repeated");
        for (int index = 0; index <= R8Builder.MAX_GENERATED_EXTENSION_CLASSES; ++index) {
            entries.put(String.format("p/C%05d.class", index), classFile);
        }
        File jar = createJarWithBytes(new File(tempDir, "too-many-classes.jar"), entries);
        File output = new File(tempDir, "generated.keep");

        ExtenderException exception = assertThrows(
                ExtenderException.class,
                () -> R8Builder.writeProtectedJarKeepRules(List.of(jar.getAbsolutePath()), output));

        assertTrue(exception.getMessage().contains("Too many classes"));
        assertFalse(output.exists());
    }

    // Verifies that generated extension rules enforce a total output-size budget during JAR enumeration.
    @Test
    public void testGeneratedRuleBytesAreBoundedDuringJarEnumeration(@TempDir File tempDir)
            throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        String longName = "a".repeat(6000);
        for (int index = 0; index < 200; ++index) {
            String internalName = String.format("p/C%03d%s", index, longName);
            entries.put(internalName + ".class", createMinimalClassFile(internalName));
        }
        File jar = createJarWithBytes(new File(tempDir, "oversized-generated-rules.jar"), entries);
        File output = new File(tempDir, "generated.keep");

        ExtenderException exception = assertThrows(
                ExtenderException.class,
                () -> R8Builder.writeProtectedJarKeepRules(List.of(jar.getAbsolutePath()), output));

        assertTrue(exception.getMessage().contains("Generated R8 keep rules are too large"));
        assertFalse(output.exists());
    }

    // Verifies that .keep discovery is recursive only under manifests/android and explicit rules disable automatic JAR protection.
    @Test
    public void testExtensionRuleDiscoveryIsRecursiveAndFolderScoped(@TempDir File tempDir) throws Exception {
        File extensionDir = new File(tempDir, "extension");
        File manifestsDir = new File(extensionDir, "manifests/android");
        File nestedDir = new File(manifestsDir, "nested");
        assertTrue(nestedDir.mkdirs());

        File rootKeep = new File(manifestsDir, "root.keep");
        File nestedKeep = new File(nestedDir, "nested.keep");
        Files.writeString(rootKeep.toPath(), "-keep class Root");
        Files.writeString(nestedKeep.toPath(), "-keep class Nested");
        Files.writeString(new File(manifestsDir, "legacy.pro").toPath(), "-keep class Legacy");
        Files.writeString(new File(extensionDir, "outside.keep").toPath(), "-keep class Outside");

        R8Builder.ExtensionContext context = R8Builder.createExtensionContext(
                extensionDir,
                List.of("extension.jar"),
                KEEP_RULE_REGEX);

        assertEquals(
                List.of(nestedKeep.getAbsolutePath(), rootKeep.getAbsolutePath()),
                context.ruleFiles);
        assertTrue(context.protectedJars.isEmpty());
    }

    // Verifies that a rules-only extension placeholder is never forwarded to R8 as a program JAR.
    @Test
    public void testRulesOnlyPlaceholderNeverBecomesAProgramJar() {
        R8Builder.ExtensionContext context = new R8Builder.ExtensionContext();
        Map<String, R8Builder.ExtensionContext> extensionJars = new HashMap<>();
        extensionJars.put("/tmp/real.jar", context);
        extensionJars.put("/tmp/" + R8Builder.RULES_WITHOUT_JAR, context);

        assertEquals(List.of("/tmp/real.jar"), R8Builder.getCompiledJars(extensionJars));
    }

    // Verifies that a full build assembles and sanitizes rules, strips consumer entries, and returns dex and mapping outputs.
    @Test
    public void testBuildAssemblesExtensionAndAarRulesAndReturnsMapping(@TempDir File tempDir) throws Exception {
        File uploadDir = new File(tempDir, "upload");
        File appDir = new File(uploadDir, "_app");
        File buildDir = new File(tempDir, "build");
        assertTrue(appDir.mkdirs());
        assertTrue(buildDir.mkdirs());
        File appRules = new File(appDir, "app.keep");
        Files.writeString(appRules.toPath(), "-keep class App");
        File unselectedEngineRules = new File(appDir, "dmengine.keep");
        Files.writeString(unselectedEngineRules.toPath(), "-keep class UnselectedEngine");
        File aaptRules = new File(buildDir, "aapt-generated.keep");
        Files.writeString(aaptRules.toPath(), "-keep class AaptGenerated");

        File extensionDir = new File(uploadDir, "extension");
        File extensionRulesDir = new File(extensionDir, "manifests/android");
        assertTrue(extensionRulesDir.mkdirs());
        File extensionRules = new File(extensionRulesDir, "extension.keep");
        Files.writeString(extensionRules.toPath(), "-keep class Extension");
        Files.writeString(new File(extensionRulesDir, "ignored.pro").toPath(), "-keep class Ignored");
        File extensionJar = createJar(
                new File(tempDir, "extension.jar"),
                Map.of("com/example/Extension.class", "class"));
        R8Builder.ExtensionContext extensionContext = R8Builder.createExtensionContext(
                extensionDir,
                List.of(extensionJar.getAbsolutePath()),
                KEEP_RULE_REGEX);

        File dependencyAar = new File(tempDir, "dependency.aar");
        assertTrue(dependencyAar.mkdirs());
        File dependencyJar = createJar(
                new File(dependencyAar, "classes.jar"),
                Map.of(
                        "com/example/Dependency.class", "class",
                        "META-INF/com.android.tools/r8-from-0.0.0-arbitrary/dependency.keep",
                        "-keep class Dependency"));
        Files.writeString(new File(dependencyAar, "proguard.txt").toPath(), "-keep class Dependency");

        PlatformConfig config = new PlatformConfig();
        config.r8Cmd = "r8-command {{#jars}}\"{{{.}}}\" {{/jars}}";
        config.r8Version = "8.13.19";

        Map<String, Object> executedContext = new HashMap<>();
        Map<String, String> executedCommand = new HashMap<>();
        R8Builder builder = new R8Builder(
                uploadDir,
                buildDir,
                config,
                List.of(dependencyAar),
                new HashMap<>(),
                24,
                new TemplateExecutor(),
                (command, context) -> {
                    assertTrue(command.startsWith("r8-command "));
                    executedCommand.put("command", command);
                    executedContext.putAll(context);
                    try {
                        Files.writeString(
                                new File((String) context.get("classes_dex_dir"), "classes.dex").toPath(),
                                "dex");
                        Files.writeString(new File((String) context.get("mapping")).toPath(), "mapping");
                    } catch (IOException e) {
                        throw new ExtenderException(e, "Failed to create fake R8 outputs");
                    }
                });

        R8Builder.BuildOutput output = builder.build(
                List.of(extensionJar.getAbsolutePath(), dependencyJar.getAbsolutePath()),
                Map.of(extensionJar.getAbsolutePath(), extensionContext),
                aaptRules);

        assertNotNull(output);
        assertEquals(List.of("classes.dex"), Arrays.stream(output.dexFiles).map(File::getName).toList());
        assertEquals("mapping.txt", output.mappingFile.getName());
        assertEquals(0, output.metaInformationFiles.length);
        assertEquals(output.mappingFile.getAbsolutePath(), executedContext.get("mapping"));

        @SuppressWarnings("unchecked")
        List<String> programJars = (List<String>) executedContext.get("jars");
        assertTrue(programJars.contains(extensionJar.getAbsolutePath()));
        assertFalse(programJars.contains(dependencyJar.getAbsolutePath()));
        String strippedDependencyJar = programJars.stream()
                .filter(path -> path.contains("r8-program-jars"))
                .findFirst()
                .orElseThrow();
        assertTrue(new File(strippedDependencyJar).isFile());
        assertTrue(zipEntryNames(new File(strippedDependencyJar)).contains("com/example/Dependency.class"));
        assertFalse(zipEntryNames(new File(strippedDependencyJar)).stream()
                .anyMatch(R8Builder::isEmbeddedRuleEntryName));
        assertTrue(executedCommand.get("command").contains(strippedDependencyJar));
        assertFalse(executedCommand.get("command").contains(dependencyJar.getAbsolutePath()));

        @SuppressWarnings("unchecked")
        List<String> assembledRules = (List<String>) executedContext.get("rules");
        assertFalse(assembledRules.contains(appRules.getAbsolutePath()));
        assertFalse(assembledRules.contains(aaptRules.getAbsolutePath()));
        assertFalse(assembledRules.contains(unselectedEngineRules.getAbsolutePath()));
        assertFalse(assembledRules.contains(extensionRules.getAbsolutePath()));
        assertFalse(assembledRules.stream().anyMatch(path -> path.endsWith("ignored.pro")));
        assertFalse(executedContext.containsKey("mainDexRules"));
        List<File> sanitizedRules = assembledRules.stream()
                .map(File::new)
                .filter(rule -> {
                    try {
                        return Files.readString(rule.toPath()).startsWith("-basedirectory ");
                    } catch (IOException e) {
                        return false;
                    }
                })
                .toList();
        assertEquals(4, sanitizedRules.size());
        assertTrue(sanitizedRules.stream().anyMatch(rule -> {
            try {
                return sanitizedRuleBody(rule).contains("-keep class App");
            } catch (IOException e) {
                return false;
            }
        }));
        assertTrue(sanitizedRules.stream().anyMatch(rule -> {
            try {
                return sanitizedRuleBody(rule).contains("-keep class Extension");
            } catch (IOException e) {
                return false;
            }
        }));
        assertTrue(sanitizedRules.stream().anyMatch(rule -> {
            try {
                return sanitizedRuleBody(rule).contains("-keep class AaptGenerated");
            } catch (IOException e) {
                return false;
            }
        }));
        assertTrue(sanitizedRules.stream().anyMatch(rule -> {
            try {
                return sanitizedRuleBody(rule).contains("-keep class Dependency");
            } catch (IOException e) {
                return false;
            }
        }));
        File ruleBase = sanitizedRuleBase(sanitizedRules.get(0));
        assertTrue(ruleBase.isDirectory());
        assertEquals(0, ruleBase.listFiles().length);
        for (File sanitizedRule : sanitizedRules) {
            assertEquals(ruleBase.getCanonicalFile(), sanitizedRuleBase(sanitizedRule).getCanonicalFile());
            assertFalse(sanitizedRule.toPath().startsWith(ruleBase.toPath()));
        }
    }

    // Verifies that supported annotation-based R8 class and member specifications pass rule validation.
    @Test
    public void testRulePolicyAllowsAnnotationRules() {
        String rules = String.join("\n",
                "# Annotation-based Gson and Android rules are supported",
                "-keep,allowoptimization @com.example.JsonAdapter class *",
                "-keep @com.example.Outer$Marker class *",
                "-keep,allowoptimization",
                "@com.example.MultilineAdapter class *",
                "-keep @interface androidx.annotation.Keep",
                "-keep public class * extends @com.example.BaseType ** {",
                "    @androidx.annotation.Keep <methods>;",
                "    java.lang.String source();",
                "}",
                "-maximumremovedandroidloglevel 2 @com.example.Marker class * { *; }");

        assertDoesNotThrow(() -> R8RulePolicy.validateRules(rules, "safe.keep"));
    }

    // Verifies that rule sanitization normalizes a BOM, uses an empty isolated base, and rejects a contaminated sandbox.
    @Test
    public void testSanitizedRuleUsesSeparateEmptyBaseAndNormalizesBom(@TempDir File tempDir)
            throws Exception {
        File source = new File(tempDir, "source.keep");
        Files.writeString(
                source.toPath(),
                "\uFEFF@local.keep\n-keep @com.example.Marker class *");
        File emptyBase = R8RulePolicy.createEmptyBaseDirectory(tempDir);
        File sanitized = new File(tempDir, "sanitized/rule.keep");

        R8RulePolicy.writeSanitized(
                sanitized,
                R8RulePolicy.readAndValidate(source, new R8RulePolicy.Budget()),
                emptyBase);

        assertEquals(
                "@local.keep\n-keep @com.example.Marker class *",
                sanitizedRuleBody(sanitized));
        assertEquals(emptyBase.getCanonicalFile(), sanitizedRuleBase(sanitized).getCanonicalFile());
        assertEquals(0, emptyBase.listFiles().length);
        assertFalse(sanitized.toPath().startsWith(emptyBase.toPath()));

        Files.writeString(new File(emptyBase, "unexpected.keep").toPath(), "-keep class Unexpected");
        ExtenderException exception = assertThrows(
                ExtenderException.class,
                () -> R8RulePolicy.writeSanitized(
                        new File(tempDir, "second.keep"),
                        "-keep class Second".getBytes(StandardCharsets.UTF_8),
                        emptyBase));
        assertTrue(exception.getMessage().contains("sandbox is not empty"));
    }

    // Verifies that the pinned R8-only class-spec options accepted by dependencies pass policy validation.
    @ParameterizedTest
    @ValueSource(strings = {
            "-alwaysinline",
            "-checkenumstringsdiscarded",
            "-assumenoexternalsideeffects",
            "-checkenumunboxed",
            "-alwaysclassinline"
    })
    public void testRulePolicyAllowsPinnedR8ClassSpecOptions(String option) {
        assertDoesNotThrow(() -> R8RulePolicy.validateRules(
                option + "\n@com.example.Marker class *",
                "class-spec.keep"));
    }

    // Verifies that direct and obfuscated filesystem directives are rejected across includes, paths, and output options.
    @ParameterizedTest
    @ValueSource(strings = {
            "@/etc/passwd",
            "@ /etc/hosts",
            "@# hidden operand\n/etc/hosts",
            "@\"/etc/hosts\"",
            "@file:///etc/hosts",
            "@../other-job/rules.keep",
            "@~/rules.keep",
            "@C:\\server\\rules.keep",
            "@${user.home}/rules.keep",
            "@<java.home>/rules.keep",
            "-keepkotlinmetadata\n@/etc/hosts",
            "-maximumremovedandroidloglevel 4\n@/etc/hosts",
            "-keepkotlinmetadata\r@/etc/hosts",
            "# -include /etc/passwd",
            "# harmless comment\r-include /etc/hosts",
            "\uFEFF-include /etc/hosts",
            "-renamesourcefileattribute \"-include /etc/hosts\"",
            "-renamesourcefileattribute \"foo\\\" -include /etc/hosts \"",
            "-renamesourcefileattribute {\n@/etc/hosts",
            "}-include /etc/hosts",
            "-keep class Safe @../other-job/rules.keep",
            "-include /etc/passwd",
            "-basedirectory /",
            "-injars /tmp/input.jar",
            "-outjars /tmp/output.jar",
            "-libraryjars /tmp/library.jar",
            "-applymapping /tmp/mapping.txt",
            "-obfuscationdictionary /tmp/words.txt",
            "-classobfuscationdictionary /tmp/classes.txt",
            "-packageobfuscationdictionary /tmp/packages.txt",
            "-printusage /tmp/usage.txt",
            "-printconfiguration /tmp/configuration.txt",
            "-printmapping /tmp/mapping.txt",
            "-printseeds /tmp/seeds.txt",
            "-dump /tmp/dump.txt"
    })
    public void testRulePolicyRejectsFilesystemDirectives(String rules) {
        ExtenderException exception = assertThrows(
                ExtenderException.class,
                () -> R8RulePolicy.validateRules(rules, "unsafe.keep"));

        assertTrue(exception.getMessage().contains("forbidden filesystem directive"));
    }

    // Verifies that the number of embedded consumer-rule files is bounded before extraction.
    @Test
    public void testEmbeddedRuleEntryCountIsBounded(@TempDir File tempDir) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        for (int index = 0; index <= R8RulePolicy.MAX_RULE_FILES; ++index) {
            entries.put(String.format("META-INF/proguard/rule-%04d.pro", index), "-keep class Example");
        }
        File jar = createJar(new File(tempDir, "too-many-rules.jar"), entries);

        ExtenderException exception = assertThrows(
                ExtenderException.class,
                () -> R8Builder.selectEmbeddedRuleEntries(jar, "8.13.19"));

        assertTrue(exception.getMessage().contains("Too many embedded R8 rule files"));
    }

    // Verifies that expanded embedded rule data is size-bounded and leaves no partial extracted files on failure.
    @Test
    public void testEmbeddedRuleExpandedSizeIsBounded(@TempDir File tempDir) throws Exception {
        String oversizedRule = " ".repeat((int) R8RulePolicy.MAX_RULE_FILE_BYTES + 1);
        File jar = createJar(
                new File(tempDir, "oversized-rule.jar"),
                Map.of("META-INF/proguard/oversized.pro", oversizedRule));
        File outputDir = new File(tempDir, "collected");

        ExtenderException exception = assertThrows(
                ExtenderException.class,
                () -> R8Builder.collectConsumerRules(
                        List.of(jar.getAbsolutePath()),
                        List.of(),
                        "8.13.19",
                        outputDir));

        assertTrue(exception.getMessage().contains("R8 rule file is too large"));
        assertEquals(0, outputDir.listFiles().length);
    }

    // Verifies that legacy embedded consumer rules are subjected to the same filesystem-directive policy as app rules.
    @Test
    public void testEmbeddedConsumerRuleUsesFilesystemPolicy(@TempDir File tempDir) throws Exception {
        File jar = createJar(
                new File(tempDir, "unsafe-consumer-rule.jar"),
                Map.of("META-INF/proguard/unsafe.pro", "-include /etc/passwd"));

        ExtenderException exception = assertThrows(
                ExtenderException.class,
                () -> R8Builder.collectConsumerRules(
                        List.of(jar.getAbsolutePath()),
                        List.of(),
                        "8.13.19",
                        new File(tempDir, "collected")));

        assertTrue(exception.getMessage().contains("forbidden filesystem directive"));
    }

    // Verifies that rules under malformed-but-applicable R8 suffix directories cannot bypass filesystem policy checks.
    @Test
    public void testMalformedR8SuffixConsumerRuleUsesFilesystemPolicy(@TempDir File tempDir)
            throws Exception {
        File jar = createJar(
                new File(tempDir, "unsafe-malformed-suffix.jar"),
                Map.of(
                        "META-INF/com.android.tools/r8-from-0.0.0-arbitrary/unsafe.pro",
                        "-include /etc/passwd"));

        ExtenderException exception = assertThrows(
                ExtenderException.class,
                () -> R8Builder.collectConsumerRules(
                        List.of(jar.getAbsolutePath()),
                        List.of(),
                        "8.13.19",
                        new File(tempDir, "collected")));

        assertTrue(exception.getMessage().contains("forbidden filesystem directive"));
    }
}
