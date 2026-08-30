package com.defold.extender.services;

import com.defold.extender.ExtenderBuildState;
import com.defold.extender.ExtenderException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RealGradleServiceTest {
    private static String readResource(String path) throws Exception {
        try (InputStream input = RealGradleServiceTest.class.getResourceAsStream(path)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // Verifies that the Gradle template reuses AGP's exploded AARs and processed JARs and writes a structured artifact manifest.
    @Test
    public void testBuildTemplateReusesAgpArtifacts() throws Exception {
        String template = readResource("/template.build.gradle");

        assertTrue(template.contains("AndroidArtifacts.ArtifactType.EXPLODED_AAR.type"));
        assertTrue(template.contains("AndroidArtifacts.ArtifactType.PROCESSED_JAR.type"));
        assertFalse(template.contains("AndroidArtifacts.ArtifactType.PROCESSED_AAR.type"));
        assertTrue(template.contains("standaloneJarComponents"));
        assertTrue(template.contains("standaloneJarComponents.contains(it)"));
        assertTrue(template.contains("if (useJetifier && !standaloneJarComponents.isEmpty())"));
        assertTrue(template.contains("kind: \"exploded-aar\""));
        assertTrue(template.contains("kind: \"jar\""));
        assertTrue(template.contains("gradle-artifacts.json"));
        assertTrue(template.contains("JsonOutput.toJson(artifacts)"));
        assertFalse(template.contains("println \"PATH:"));
    }

    // Verifies that AndroidX stays enabled even when Jetifier itself is disabled.
    @Test
    public void testAndroidXIsIndependentFromJetifier() throws Exception {
        String template = readResource("/template.gradle.properties");

        assertTrue(template.contains("android.enableJetifier={{android-enable-jetifier}}"));
        assertTrue(template.contains("android.useAndroidX=true"));
        assertFalse(template.contains("android.useAndroidX={{android-enable-jetifier}}"));
    }

    // Verifies that dependency resolution, reporting, and lock generation use one deterministic Gradle invocation.
    @Test
    public void testDependencyResolutionAndReportShareOneGradleInvocation() {
        assertEquals(
                List.of(
                        "gradle",
                        "downloadDependencies",
                        "dependencies",
                        "--configuration",
                        "releaseCompileClasspath",
                        "--write-locks",
                        "--stacktrace",
                        "--warning-mode",
                        "all",
                        "--no-daemon"),
                RealGradleService.getGradleResolveCommand());
    }

    // Verifies that artifact-manifest parsing preserves canonical Gradle cache paths, metadata, ordering, and de-duplicates entries.
    @Test
    @SuppressWarnings("unchecked")
    public void testArtifactManifestReturnsGradleCachePathsDirectly(@TempDir Path temporaryDirectory)
            throws Exception {
        Path explodedAar = Files.createDirectory(temporaryDirectory.resolve("jetified-library"));
        Files.createDirectories(explodedAar.resolve("jars"));
        Files.writeString(explodedAar.resolve("jars/classes.jar"), "classes");
        Path flatDirAar = Files.createDirectory(temporaryDirectory.resolve("jetified-local-aar"));
        Files.createDirectories(flatDirAar.resolve("jars"));
        Files.writeString(flatDirAar.resolve("jars/classes.jar"), "classes");
        Path classifierAar = Files.createDirectory(temporaryDirectory.resolve("jetified-classifier-aar"));
        Files.createDirectories(classifierAar.resolve("jars"));
        Files.writeString(classifierAar.resolve("jars/classes.jar"), "classes");
        Path jar = temporaryDirectory.resolve("jetified-library.jar");
        Files.writeString(jar, "jar");

        JSONArray manifest = new JSONArray();
        JSONObject aarEntry = new JSONObject();
        aarEntry.put("component", "com.example:library:1.0");
        aarEntry.put("originalFileName", "library-1.0.aar");
        aarEntry.put("kind", "exploded-aar");
        aarEntry.put("path", explodedAar.toString());
        manifest.add(aarEntry);
        JSONObject flatDirEntry = new JSONObject();
        flatDirEntry.put("component", ":LocalAar:");
        flatDirEntry.put("originalFileName", "LocalAar.aar");
        flatDirEntry.put("kind", "exploded-aar");
        flatDirEntry.put("path", flatDirAar.toString());
        manifest.add(flatDirEntry);
        JSONObject classifierEntry = new JSONObject();
        classifierEntry.put("component", "com.example:library:1.0");
        classifierEntry.put("originalFileName", "library-1.0-debug.aar");
        classifierEntry.put("kind", "exploded-aar");
        classifierEntry.put("path", classifierAar.toString());
        manifest.add(classifierEntry);
        JSONObject jarEntry = new JSONObject();
        jarEntry.put("component", "com.example:library:1.0");
        jarEntry.put("originalFileName", "library-1.0.jar");
        jarEntry.put("kind", "jar");
        jarEntry.put("path", jar.toString());
        manifest.add(jarEntry);
        manifest.add(jarEntry);

        Path manifestFile = temporaryDirectory.resolve("gradle-artifacts.json");
        Files.writeString(manifestFile, manifest.toJSONString());

        List<GradleArtifact> artifacts = RealGradleService.parseGradleArtifacts(manifestFile.toFile());
        assertEquals(4, artifacts.size());
        assertEquals(explodedAar.toFile().getCanonicalFile(), artifacts.get(0).getFile());
        assertEquals("com.example:library:1.0", artifacts.get(0).getComponent());
        assertEquals("library-1.0.aar", artifacts.get(0).getOriginalFileName());
        assertEquals(GradleArtifact.Kind.EXPLODED_AAR, artifacts.get(0).getKind());
        assertEquals("com.example-library-1.0.aar", artifacts.get(0).getResourcePackageName());
        assertEquals(flatDirAar.toFile().getCanonicalFile(), artifacts.get(1).getFile());
        assertEquals("LocalAar.aar", artifacts.get(1).getResourcePackageName());
        assertEquals(classifierAar.toFile().getCanonicalFile(), artifacts.get(2).getFile());
        assertEquals("com.example-library-1.0.aar", artifacts.get(2).getResourcePackageName());
        assertEquals(jar.toFile().getCanonicalFile(), artifacts.get(3).getFile());
        assertEquals(GradleArtifact.Kind.JAR, artifacts.get(3).getKind());
        assertNull(artifacts.get(3).getResourcePackageName());
    }

    // Verifies that missing artifact paths and malformed artifact-manifest JSON are rejected with Extender errors.
    @Test
    @SuppressWarnings("unchecked")
    public void testArtifactManifestRejectsInvalidEntries(@TempDir Path temporaryDirectory)
            throws Exception {
        Path missing = temporaryDirectory.resolve("missing.jar");
        JSONObject entry = new JSONObject();
        entry.put("kind", "jar");
        entry.put("path", missing.toString());
        JSONArray manifest = new JSONArray();
        manifest.add(entry);

        Path manifestFile = temporaryDirectory.resolve("gradle-artifacts.json");
        Files.writeString(manifestFile, manifest.toJSONString());
        assertThrows(
                ExtenderException.class,
                () -> RealGradleService.parseGradleArtifacts(manifestFile.toFile()));

        Files.writeString(manifestFile, "not-json");
        assertThrows(
                ExtenderException.class,
                () -> RealGradleService.parseGradleArtifacts(manifestFile.toFile()));
    }

    // Verifies that an empty dependency set skips Gradle while still emitting empty lock and explanatory dependency reports.
    @Test
    public void testNoDependenciesSkipsGradle(@TempDir Path temporaryDirectory) throws Exception {
        Path jobDirectory = Files.createDirectory(temporaryDirectory.resolve("job"));
        Path buildDirectory = Files.createDirectory(jobDirectory.resolve("build"));
        ExtenderBuildState buildState = mock(ExtenderBuildState.class);
        when(buildState.getJobDir()).thenReturn(jobDirectory.toFile());
        when(buildState.getBuildDir()).thenReturn(buildDirectory.toFile());
        when(buildState.isUsedJetifier()).thenReturn(true);

        RealGradleService service = new RealGradleService(
                new ByteArrayResource((
                        "dependencies {\n" +
                        "{{#user-dependencies}}{{{.}}}{{/user-dependencies}}\n" +
                        "}\n").getBytes(StandardCharsets.UTF_8)),
                new ClassPathResource("template.gradle.properties"),
                new ClassPathResource("template.local.properties"),
                new SimpleMeterRegistry());
        List<File> outputFiles = new ArrayList<>();

        List<GradleArtifact> artifacts = service.resolveDependencies(
                buildState,
                Map.of(
                        "env.ANDROID_SDK_ROOT", "/unused/android-sdk",
                        "env.ANDROID_SDK_VERSION", "36"),
                outputFiles);

        assertTrue(artifacts.isEmpty());
        assertEquals(
                List.of(
                        buildDirectory.resolve("gradle.lockfile").toFile(),
                        buildDirectory.resolve("gradle.dependencytree").toFile()),
                outputFiles);
        assertEquals("", Files.readString(buildDirectory.resolve("gradle.lockfile")));
        assertEquals(
                "No Gradle dependencies were declared.\n",
                Files.readString(buildDirectory.resolve("gradle.dependencytree")));
        assertFalse(Files.exists(jobDirectory.resolve("gradle.properties")));
        assertFalse(Files.exists(jobDirectory.resolve("local.properties")));
        assertFalse(Files.exists(buildDirectory.resolve("gradle-artifacts.json")));
    }
}
