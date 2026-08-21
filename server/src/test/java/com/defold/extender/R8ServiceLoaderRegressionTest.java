package com.defold.extender;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.OutputMode;
import com.android.tools.r8.R8;
import com.android.tools.r8.R8Command;
import com.android.tools.r8.Version;
import com.android.tools.r8.origin.Origin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class R8ServiceLoaderRegressionTest {
    private static final String SERVICE_TYPE = "com.defold.r8test.Greeting";
    private static final String PROVIDER_TYPE = "com.defold.r8test.GreetingProvider";

    private static Path writeSource(Path sourceRoot, String relativePath, String source) throws IOException {
        Path sourceFile = sourceRoot.resolve(relativePath);
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source);
        return sourceFile;
    }

    private static void compileJava8(Path outputDir, List<Path> sources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests must run on a JDK, not a JRE");
        Files.createDirectories(outputDir);

        List<String> arguments = new ArrayList<>(List.of(
                "-Xlint:-options",
                "--release", "8",
                "-d", outputDir.toString()));
        sources.stream().map(Path::toString).forEach(arguments::add);

        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        int result = compiler.run(null, diagnostics, diagnostics, arguments.toArray(String[]::new));
        assertEquals(0, result, diagnostics.toString(StandardCharsets.UTF_8));
    }

    private static void createJar(Path classesDir, Path jar, Map<String, String> resources)
            throws IOException {
        List<Path> classFiles;
        try (Stream<Path> paths = Files.walk(classesDir)) {
            classFiles = paths.filter(Files::isRegularFile).sorted().toList();
        }

        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Path classFile : classFiles) {
                String entryName = classesDir.relativize(classFile).toString().replace('\\', '/');
                JarEntry entry = new JarEntry(entryName);
                entry.setTime(0L);
                output.putNextEntry(entry);
                Files.copy(classFile, output);
                output.closeEntry();
            }
            for (Map.Entry<String, String> resource : resources.entrySet()) {
                JarEntry entry = new JarEntry(resource.getKey());
                entry.setTime(0L);
                output.putNextEntry(entry);
                output.write(resource.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void runR8(Map<String, Object> context, Path libraryJar) throws ExtenderException {
        try {
            List<Path> programJars = ((List<String>) context.get("jars")).stream()
                    .map(Path::of)
                    .toList();
            List<String> rules = new ArrayList<>();
            for (String ruleFile : (List<String>) context.get("rules")) {
                rules.addAll(Files.readAllLines(Path.of(ruleFile), StandardCharsets.UTF_8));
            }

            R8Command command = R8Command.builder()
                    .addProgramFiles(programJars)
                    .addLibraryFiles(libraryJar)
                    .setMode(CompilationMode.RELEASE)
                    .setMinApiLevel((Integer) context.get("minAndroidSdkVersion"))
                    .setOutput(Path.of((String) context.get("classes_dex_dir")), OutputMode.DexIndexed)
                    .addProguardConfiguration(rules, Origin.unknown())
                    .setProguardMapOutputPath(Path.of((String) context.get("mapping")))
                    .build();
            R8.run(command);
        } catch (CompilationFailedException | IOException e) {
            throw new ExtenderException(e, "Failed to run the ServiceLoader R8 regression fixture");
        }
    }

    // Verifies that R8 output returns the renamed ServiceLoader descriptor and provider instead of stale original names.
    @Test
    void returnsR8RewrittenServiceDescriptorInsteadOfOriginalNames(@TempDir Path tempDir)
            throws Exception {
        assertEquals(8, Version.getMajorVersion());
        assertEquals(13, Version.getMinorVersion());
        assertEquals(19, Version.getPatchVersion());

        Path programSources = tempDir.resolve("program-sources");
        Path programClasses = tempDir.resolve("program-classes");
        List<Path> programSourceFiles = List.of(
                writeSource(programSources, "com/defold/r8test/Greeting.java", """
                        package com.defold.r8test;
                        public interface Greeting {
                            String greet();
                        }
                        """),
                writeSource(programSources, "com/defold/r8test/GreetingProvider.java", """
                        package com.defold.r8test;
                        public final class GreetingProvider implements Greeting {
                            public GreetingProvider() {}
                            public String greet() { return "hello"; }
                        }
                        """),
                writeSource(programSources, "com/defold/r8test/ServiceMain.java", """
                        package com.defold.r8test;
                        import java.util.ServiceLoader;
                        public final class ServiceMain {
                            public static String load() {
                                return ServiceLoader.load(Greeting.class).iterator().next().greet();
                            }
                        }
                        """));
        compileJava8(programClasses, programSourceFiles);

        Path programJar = tempDir.resolve("program.jar");
        createJar(
                programClasses,
                programJar,
                Map.of("META-INF/services/" + SERVICE_TYPE, PROVIDER_TYPE + "\n"));

        Path librarySources = tempDir.resolve("library-sources");
        Path libraryClasses = tempDir.resolve("library-classes");
        List<Path> librarySourceFiles = List.of(
                writeSource(librarySources, "java/lang/Object.java", """
                        package java.lang;
                        public class Object { public Object() {} }
                        """),
                writeSource(librarySources, "java/lang/String.java", """
                        package java.lang;
                        public final class String extends Object {}
                        """),
                writeSource(librarySources, "java/lang/Class.java", """
                        package java.lang;
                        public final class Class<T> extends Object {}
                        """),
                writeSource(librarySources, "java/util/Iterator.java", """
                        package java.util;
                        public interface Iterator<E> { E next(); }
                        """),
                writeSource(librarySources, "java/util/ServiceLoader.java", """
                        package java.util;
                        public final class ServiceLoader<S> {
                            public static <S> ServiceLoader<S> load(Class<S> service) { return null; }
                            public Iterator<S> iterator() { return null; }
                        }
                        """));
        compileJava8(libraryClasses, librarySourceFiles);
        Path libraryJar = tempDir.resolve("android-minimal.jar");
        createJar(libraryClasses, libraryJar, Map.of());

        Path uploadDir = tempDir.resolve("upload");
        Path appDir = uploadDir.resolve("_app");
        Path buildDir = tempDir.resolve("build");
        Files.createDirectories(appDir);
        Files.createDirectories(buildDir);
        Files.writeString(
                appDir.resolve("app.keep"),
                "-keep class com.defold.r8test.ServiceMain { public static java.lang.String load(); }\n");
        Path aaptRules = buildDir.resolve("aapt-generated.keep");
        Files.writeString(aaptRules, "-keep class com.defold.r8test.ServiceMain\n");

        PlatformConfig config = new PlatformConfig();
        config.r8Cmd = "in-process-r8 --output \"{{{classes_dex_dir}}}\"";
        config.r8Version = "8.13.19";
        R8Builder builder = new R8Builder(
                uploadDir.toFile(),
                buildDir.toFile(),
                config,
                List.of(),
                new HashMap<>(),
                21,
                new TemplateExecutor(),
                (command, context) -> runR8(context, libraryJar));

        R8Builder.BuildOutput output = builder.build(
                List.of(programJar.toString()),
                Map.of(),
                aaptRules.toFile());

        assertEquals(1, output.dexFiles.length);
        assertEquals(buildDir.resolve("classes.dex"), output.dexFiles[0].toPath());
        assertTrue(Files.size(output.dexFiles[0].toPath()) > 0);
        assertEquals(1, output.metaInformationFiles.length);

        String mapping = Files.readString(output.mappingFile.toPath());
        Matcher providerMapping = Pattern.compile(
                "^" + Pattern.quote(PROVIDER_TYPE) + " -> ([^:]+):$",
                Pattern.MULTILINE).matcher(mapping);
        assertTrue(providerMapping.find(), mapping);
        String renamedProvider = providerMapping.group(1);
        assertNotEquals(PROVIDER_TYPE, renamedProvider);

        Path serviceDescriptor = output.metaInformationFiles[0].toPath();
        String relativeDescriptor = buildDir.relativize(serviceDescriptor).toString().replace('\\', '/');
        assertTrue(relativeDescriptor.startsWith("META-INF/services/"), relativeDescriptor);
        assertNotEquals("META-INF/services/" + SERVICE_TYPE, relativeDescriptor);
        assertEquals(renamedProvider, Files.readString(serviceDescriptor).trim());
        assertFalse(Files.exists(buildDir.resolve("META-INF/services/" + SERVICE_TYPE)));
    }
}
