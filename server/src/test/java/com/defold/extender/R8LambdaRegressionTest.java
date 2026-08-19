package com.defold.extender;

import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.OutputMode;
import com.android.tools.r8.R8;
import com.android.tools.r8.R8Command;
import com.android.tools.r8.Version;
import com.android.tools.r8.origin.Origin;

import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
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
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class R8LambdaRegressionTest {
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
        for (Path source : sources) {
            arguments.add(source.toString());
        }

        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        int result = compiler.run(null, diagnostics, diagnostics, arguments.toArray(String[]::new));
        assertEquals(0, result, diagnostics.toString(StandardCharsets.UTF_8));
    }

    private static void createJar(Path classesDir, Path jar) throws IOException {
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
        }
    }

    // Verifies that pinned R8 8.13.19 desugars a real Java 8 lambda without requiring LambdaMetafactory or suppression rules.
    @Test
    void pinnedR8CompilesJava8LambdaWithoutLambdaMetafactorySuppression(@TempDir Path tempDir)
            throws Exception {
        assertEquals(8, Version.getMajorVersion());
        assertEquals(13, Version.getMinorVersion());
        assertEquals(19, Version.getPatchVersion());

        Path programSources = tempDir.resolve("program-sources");
        Path programClasses = tempDir.resolve("program-classes");
        Path programSource = writeSource(
                programSources,
                "com/defold/r8test/LambdaProgram.java",
                """
                package com.defold.r8test;

                import java.util.function.Function;

                public final class LambdaProgram {
                    public static String run(String value) {
                        Function<String, String> prefix = input -> "lambda:" + input;
                        return prefix.apply(value);
                    }
                }
                """);
        compileJava8(programClasses, List.of(programSource));

        Path lambdaClass = programClasses.resolve("com/defold/r8test/LambdaProgram.class");
        String classFileConstants = new String(Files.readAllBytes(lambdaClass), StandardCharsets.ISO_8859_1);
        assertTrue(classFileConstants.contains("java/lang/invoke/LambdaMetafactory"),
                "The regression input must contain a real Java 8 invokedynamic lambda");

        Path librarySources = tempDir.resolve("library-sources");
        Path libraryClasses = tempDir.resolve("library-classes");
        List<Path> librarySourceFiles = List.of(
                writeSource(librarySources, "java/lang/Object.java", """
                        package java.lang;
                        public class Object {
                            public Object() {}
                        }
                        """),
                writeSource(librarySources, "java/lang/String.java", """
                        package java.lang;
                        public final class String extends Object {}
                        """),
                writeSource(librarySources, "java/lang/StringBuilder.java", """
                        package java.lang;
                        public final class StringBuilder extends Object {
                            public StringBuilder() {}
                            public StringBuilder append(String value) { return this; }
                            public String toString() { return null; }
                        }
                        """),
                writeSource(librarySources, "java/util/function/Function.java", """
                        package java.util.function;
                        public interface Function<T, R> {
                            R apply(T value);
                        }
                        """));
        compileJava8(libraryClasses, librarySourceFiles);

        Path programJar = tempDir.resolve("program.jar");
        Path libraryJar = tempDir.resolve("android-minimal.jar");
        createJar(programClasses, programJar);
        createJar(libraryClasses, libraryJar);
        assertFalse(Files.exists(libraryClasses.resolve("java/lang/invoke/LambdaMetafactory.class")),
                "The Android-like library must not provide LambdaMetafactory");

        List<String> keepRules = List.of("-keep class com.defold.r8test.LambdaProgram { *; }");
        assertFalse(keepRules.stream().anyMatch(rule -> rule.contains("dontwarn")));
        assertFalse(keepRules.stream().anyMatch(rule -> rule.contains("LambdaMetafactory")));

        Path outputDir = tempDir.resolve("r8-output");
        Path mapping = tempDir.resolve("mapping.txt");
        Files.createDirectories(outputDir);
        R8Command command = R8Command.builder()
                .addProgramFiles(programJar)
                .addLibraryFiles(libraryJar)
                .setMode(CompilationMode.RELEASE)
                .setMinApiLevel(21)
                .setOutput(outputDir, OutputMode.DexIndexed)
                .addProguardConfiguration(keepRules, Origin.unknown())
                .setProguardMapOutputPath(mapping)
                .build();
        R8.run(command);

        Path classesDex = outputDir.resolve("classes.dex");
        assertTrue(Files.size(classesDex) > 0);
        assertTrue(Files.isRegularFile(mapping));

        DexFile dexFile = DexFileFactory.loadDexFile(classesDex.toFile().getAbsolutePath(), Opcodes.forApi(21));
        assertTrue(dexFile.getClasses().stream()
                .map(ClassDef::getType)
                .anyMatch("Lcom/defold/r8test/LambdaProgram;"::equals));
    }
}
