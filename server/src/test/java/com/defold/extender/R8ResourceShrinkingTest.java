package com.defold.extender;

import com.android.tools.r8.R8;
import com.android.tools.r8.R8Command;
import com.android.tools.r8.origin.Origin;
import com.defold.extender.process.CommandLineTokenizer;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.iface.ClassDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class R8ResourceShrinkingTest {
    private static final Path FIXTURE = Path.of("test-data/r8-resources");
    private static final String RESOURCE_ARGUMENTS = " --android-resources \"{{{android_resources_in}}}\" \"{{{android_resources_out}}}\"";

    private static PlatformConfig configuration(boolean shrinkResources) throws Exception {
        File root = new File("test-data");
        Configuration sdk = Extender.loadYaml(root, new File(root, "sdk/a/defoldsdk/extender/build.yml"), Configuration.class);
        PlatformConfig config = ExtenderTest.mergePlatformConfig(sdk, "arm64-android");
        if (!shrinkResources) {
            // SDKs predating resource shrinking have neither the capability nor these arguments.
            config.r8ResourceShrinking = null;
            config.r8Cmd = config.r8Cmd.replace(RESOURCE_ARGUMENTS, "");
        }
        return config;
    }

    private static void compileJava8(Path sourceDir, Path outputDir, Path classpath) throws IOException {
        Files.createDirectories(outputDir);
        List<String> arguments = new ArrayList<>(List.of("--release", "8", "-d", outputDir.toString()));
        if (classpath != null) {
            arguments.addAll(List.of("-classpath", classpath.toString()));
        }
        try (Stream<Path> sources = Files.walk(sourceDir)) {
            sources.filter(path -> path.toString().endsWith(".java")).sorted().forEach(path -> arguments.add(path.toString()));
        }
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        assertNotNull(ToolProvider.getSystemJavaCompiler());
        int result = ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics, arguments.toArray(String[]::new));
        assertEquals(0, result, diagnostics.toString(StandardCharsets.UTF_8));
    }

    private static void createJar(Path classes, Path jar) throws IOException {
        try (Stream<Path> paths = Files.walk(classes);
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                output.putNextEntry(new ZipEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
    }

    private static Path createLibrary(Path temporary) throws IOException {
        Path sources = temporary.resolve("library-src");
        Map<String, String> stubs = Map.of(
                "java/lang/Object.java", "package java.lang; public class Object { public Object() {} }",
                "android/content/Context.java", "package android.content; public class Context {}",
                "android/util/AttributeSet.java", "package android.util; public interface AttributeSet {}",
                "android/view/View.java", "package android.view; public class View { public View(android.content.Context c, android.util.AttributeSet a) {} }");
        for (Map.Entry<String, String> stub : stubs.entrySet()) {
            Path file = sources.resolve(stub.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, stub.getValue());
        }
        Path classes = temporary.resolve("library-classes");
        compileJava8(sources, classes, null);
        Path library = temporary.resolve("android-minimal.jar");
        createJar(classes, library);
        return library;
    }

    private static R8Builder builder(Path temporary, PlatformConfig config, Path library, R8Builder.CommandExecutor executor) throws IOException {
        Path upload = temporary.resolve("upload");
        Files.createDirectories(upload.resolve("_app"));
        Files.copy(FIXTURE.resolve("app.keep"), upload.resolve("_app/app.keep"));
        Path build = temporary.resolve("build");
        Files.createDirectories(build);
        Map<String, Object> context = new HashMap<>();
        context.put("env.R8", "in-process-r8.jar");
        context.put("env.R8_VERSION", "8.13.19");
        context.put("env.LIBRARYJAR", library.toString());
        return new R8Builder(upload.toFile(), build.toFile(), config, List.of(), context, 21,
                new R8Configuration(), new TemplateExecutor(), executor);
    }

    // Verifies the real pinned R8 shrinks resources using code/XML reachability and tools:keep, while older SDKs retain their archive.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void optimizesResourcesAndPreservesLegacySdkBehavior(boolean shrinkResources, @TempDir Path temporary) throws Exception {
        Path library = createLibrary(temporary);
        Path classes = temporary.resolve("program-classes");
        compileJava8(FIXTURE.resolve("java"), classes, library);
        Path program = temporary.resolve("program.jar");
        createJar(classes, program);
        R8Builder builder = builder(temporary, configuration(shrinkResources), library, (command, context) -> {
            try {
                List<String> arguments = CommandLineTokenizer.parse(command);
                arguments = arguments.subList(arguments.indexOf("com.android.tools.r8.R8") + 1, arguments.size());
                R8.run(R8Command.parse(arguments.toArray(String[]::new), Origin.unknown()).build());
            } catch (Exception e) {
                throw new ExtenderException(e, "Failed to run resource regression fixture");
            }
        });
        Path resources = temporary.resolve("build/compiledresources.apk");
        Files.copy(FIXTURE.resolve("resources.ap_"), resources);
        byte[] original = Files.readAllBytes(resources);
        File aaptRules = shrinkResources ? null : FIXTURE.resolve("aapt-generated.keep").toFile();

        R8Builder.BuildOutput output = builder.build(List.of(program.toString()), Map.of(), aaptRules, resources.toFile());

        assertEquals(resources.toFile(), output.compiledResources);
        assertTrue(output.mappingFile.isFile());
        Set<String> dexClasses = DexFileFactory.loadDexFile(output.dexFiles[0], Opcodes.forApi(21)).getClasses().stream()
                .map(ClassDef::getType).collect(Collectors.toSet());
        assertTrue(dexClasses.contains("Lcom/defold/r8resources/XmlView;"));
        assertFalse(dexClasses.contains("Lcom/defold/r8resources/DeadCode;"));
        assertEquals(!shrinkResources, dexClasses.contains("Lcom/defold/r8resources/UnusedView;"));
        try (ZipFile archive = new ZipFile(output.compiledResources)) {
            for (String name : List.of("AndroidManifest.xml", "resources.pb", "res/layout/used_layout.xml", "res/raw/code_kept.bin", "res/raw/dynamic_kept.bin")) {
                assertNotNull(archive.getEntry(name), name);
            }
            for (String name : List.of("res/layout/unused_layout.xml", "res/raw/unused.bin", "res/raw/dead_code.bin")) {
                assertEquals(!shrinkResources, archive.getEntry(name) != null, name);
            }
            String table = new String(archive.getInputStream(archive.getEntry("resources.pb")).readAllBytes(), StandardCharsets.ISO_8859_1);
            for (String name : List.of("manifest_kept", "xml_kept", "code_kept")) {
                assertTrue(table.contains(name), name);
            }
            for (String name : List.of("unused_string", "dead_code_string")) {
                assertEquals(!shrinkResources, table.contains(name), name);
            }
        }
        if (shrinkResources) {
            assertTrue(Files.size(resources) < original.length / 2, "The unused payloads must be removed, not just the dex optimized");
        } else {
            assertArrayEquals(original, Files.readAllBytes(resources));
        }
    }

    // Verifies new SDKs report a missing linked archive before invoking R8 and do not require obsolete AAPT rules.
    @Test
    void rejectsMissingResourceInput(@TempDir Path temporary) throws Exception {
        R8Builder builder = builder(temporary, configuration(true), temporary.resolve("unused.jar"), (command, context) -> fail("R8 must not execute"));
        ExtenderException error = assertThrows(ExtenderException.class,
                () -> builder.build(List.of(), Map.of(), null, null));
        assertTrue(error.getMessage().contains("aapt2 did not produce compiledresources.apk"));
    }

    // Verifies failed or malformed resource output cannot silently publish the original unoptimized archive.
    @ParameterizedTest
    @ValueSource(strings = {"missing", "invalid-zip", "missing-table"})
    void rejectsMissingOrInvalidResourceOutput(String failure, @TempDir Path temporary) throws Exception {
        R8Builder builder = builder(temporary, configuration(true), temporary.resolve("unused.jar"), (command, context) -> {
            try {
                Files.writeString(Path.of((String) context.get("mapping")), "mapping");
                Files.writeString(Path.of((String) context.get("classes_dex_dir"), "classes.dex"), "dex");
                Path archive = Path.of((String) context.get("android_resources_out"));
                if (failure.equals("invalid-zip")) {
                    Files.writeString(archive, "invalid");
                } else if (failure.equals("missing-table")) {
                    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                        zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));
                        zip.write(1);
                        zip.closeEntry();
                    }
                }
            } catch (IOException e) {
                throw new ExtenderException(e, "Failed to create test output");
            }
        });
        Path resources = temporary.resolve("build/compiledresources.apk");
        Files.copy(FIXTURE.resolve("resources.ap_"), resources);
        byte[] original = Files.readAllBytes(resources);

        assertThrows(ExtenderException.class, () -> builder.build(List.of(), Map.of(), null, resources.toFile()));
        assertArrayEquals(original, Files.readAllBytes(resources));
    }
}
