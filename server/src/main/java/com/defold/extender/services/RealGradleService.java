package com.defold.extender.services;

import com.defold.extender.ExtenderBuildState;
import com.defold.extender.ExtenderException;
import com.defold.extender.ExtenderUtil;
import com.defold.extender.TemplateExecutor;
import com.defold.extender.metrics.MetricsWriter;
import com.defold.extender.process.ProcessUtils;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
@ConditionalOnProperty(name = "extender.gradle.enabled", havingValue = "true")
public class RealGradleService implements GradleServiceInterface {
    private static final Logger LOGGER = LoggerFactory.getLogger(RealGradleService.class);

    private static final List<String> GRADLE_BLOCKLIST = List.of(
        "buildscript", "apply plugin:", "apply from:",
        "Runtime.getRuntime", "ProcessBuilder", "System.exit",
        "ClassLoader", "Class.forName", "URLClassLoader"
    );
    private static final String GRADLE_USER_HOME = System.getenv("GRADLE_USER_HOME");
    private static final String GRADLE_PLUGIN_VERSION = System.getenv("GRADLE_PLUGIN_VERSION");

    private final TemplateExecutor templateExecutor = new TemplateExecutor();

    private final String gradleHome;

    private final MeterRegistry meterRegistry;
    private final String buildGradleTemplateContents;
    private final String gradlePropertiesTemplateContents;
    private final String localPropertiesTemplateContents;

    RealGradleService(@Value("classpath:template.build.gradle") Resource buildGradleTemplate,
        @Value("classpath:template.gradle.properties") Resource gradlePropertiesTemplate,
        @Value("classpath:template.local.properties") Resource localPropertiesTemplate,
        MeterRegistry meterRegistry) throws IOException {
        if (GRADLE_USER_HOME != null) {
            this.gradleHome = GRADLE_USER_HOME;
        } else {
            File f = new File(".gradle");
            this.gradleHome = f.getAbsolutePath();
        }
        Files.createDirectories(Paths.get(this.gradleHome));

        this.meterRegistry = meterRegistry;

        this.buildGradleTemplateContents = ExtenderUtil.readContentFromResource(buildGradleTemplate);
        this.gradlePropertiesTemplateContents = ExtenderUtil.readContentFromResource(gradlePropertiesTemplate);
        this.localPropertiesTemplateContents = ExtenderUtil.readContentFromResource(localPropertiesTemplate);

        LOGGER.info("GRADLE service using directory {}", this.gradleHome);
    }

    private Map<String, Object> createJobEnvContext(Map<String, Object> env) {
        Map<String, Object> context = new HashMap<>(env);
        context.putIfAbsent("env.ANDROID_SDK_ROOT", System.getenv("ANDROID_SDK_ROOT"));
        context.putIfAbsent("env.ANDROID_SDK_VERSION", System.getenv("ANDROID_SDK_VERSION"));
        return context;
    }

    @Override
    public List<File> resolveDependencies(ExtenderBuildState buildState, Map<String, Object> env, List<File> outputFiles) throws IOException, ExtenderException {
        // cwd -> jobDir
        File workDir = buildState.getJobDir();
        File buildDir = buildState.getBuildDir();
        Map<String, Object> jobEnvContext = createJobEnvContext(env);
        // create build.gradle
        File mainGradleFile = new File(workDir, "build.gradle");
        List<File> gradleFiles = ExtenderUtil.listFilesMatchingRecursive(workDir, "build\\.gradle");
        // This file might exist when testing and debugging the extender using a debug job folder
        gradleFiles.remove(mainGradleFile);
        boolean hasDependencies = createBuildGradleFile(mainGradleFile, gradleFiles, jobEnvContext);

        Files.createDirectories(buildDir.toPath());
        File lockFile = new File(buildDir, "gradle.lockfile");
        File dependencyTreeFile = new File(buildDir, "gradle.dependencytree");
        File artifactManifestFile = new File(buildDir, "gradle-artifacts.json");
        outputFiles.add(lockFile);
        outputFiles.add(dependencyTreeFile);

        if (!hasDependencies) {
            Files.deleteIfExists(artifactManifestFile.toPath());
            Files.writeString(lockFile.toPath(), "", StandardCharsets.UTF_8);
            Files.writeString(
                    dependencyTreeFile.toPath(),
                    "No Gradle dependencies were declared.\n",
                    StandardCharsets.UTF_8);
            return List.of();
        }

        // create gradle.properties
        File gradlePropertiesFile = new File(workDir, "gradle.properties");
        createGradlePropertiesFile(gradlePropertiesFile, buildState.isUsedJetifier());

        // create local.properties
        File localPropertiesFile = new File(workDir, "local.properties");
        createLocalPropertiesFile(localPropertiesFile, jobEnvContext);

        // Resolve AGP-processed dependencies and reuse their cache paths directly.
        return resolveGradleArtifacts(workDir, artifactManifestFile, dependencyTreeFile);
    }

    @Override
    public long getCacheSize() throws IOException {
        Path folder = Paths.get(this.gradleHome);
        try (Stream<Path> paths = Files.walk(folder)) {
            return paths
                    .filter(Files::isRegularFile)
                    .mapToLong(path -> path.toFile().length())
                    .sum();
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Private

    private void createGradlePropertiesFile(File gradlePropertiesFile, Boolean useJetifier) throws IOException {
        HashMap<String, Object> envContext = new HashMap<>();
        envContext.put("android-enable-jetifier", useJetifier.toString());
        String contents = templateExecutor.execute(gradlePropertiesTemplateContents, envContext);
        Files.write(gradlePropertiesFile.toPath(), contents.getBytes());
    }

    private void createLocalPropertiesFile(File localPropertiesFile, Map<String, Object> jobEnvContext) throws IOException {
        HashMap<String, Object> envContext = new HashMap<>();
        envContext.put("android-sdk-root", jobEnvContext.get("env.ANDROID_SDK_ROOT"));
        String contents = templateExecutor.execute(localPropertiesTemplateContents, envContext);
        Files.write(localPropertiesFile.toPath(), contents.getBytes());
    }

    private void validateGradleFile(File file) throws IOException, ExtenderException {
        String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        for (String keyword : GRADLE_BLOCKLIST) {
            if (content.contains(keyword)) {
                throw new ExtenderException("build.gradle contains disallowed content: '" + keyword + "' in file: " + file.getName());
            }
        }
    }

    private List<String> extractBlockContent(String content, String blockName) {
        List<String> lines = new ArrayList<>();
        Pattern pattern = Pattern.compile("\\b" + blockName + "\\s*\\{");
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            int braceStart = matcher.end() - 1;
            int depth = 1;
            int pos = braceStart + 1;
            while (pos < content.length() && depth > 0) {
                char c = content.charAt(pos);
                if (c == '{') depth++;
                else if (c == '}') depth--;
                pos++;
            }
            if (depth == 0) {
                String inner = content.substring(braceStart + 1, pos - 1);
                for (String line : inner.split("\n")) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("//")) {
                        lines.add(trimmed);
                    }
                }
            }
        }
        return lines;
    }

    private boolean createBuildGradleFile(File mainGradleFile, List<File> gradleFiles, Map<String, Object> jobEnvContext) throws IOException, ExtenderException {
        List<String> userDependencies = new ArrayList<>();
        List<String> userRepositories = new ArrayList<>();
        for (File file : gradleFiles) {
            validateGradleFile(file);
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            userDependencies.addAll(extractBlockContent(content, "dependencies"));
            userRepositories.addAll(extractBlockContent(content, "repositories"));
        }
        HashMap<String, Object> envContext = new HashMap<>();
        envContext.put("user-dependencies", userDependencies);
        envContext.put("user-repositories", userRepositories);
        envContext.put("compile-sdk-version", jobEnvContext.get("env.ANDROID_SDK_VERSION"));
        envContext.put("gradle-plugin-version", GRADLE_PLUGIN_VERSION);
        String contents = templateExecutor.execute(buildGradleTemplateContents, envContext);
        Files.write(mainGradleFile.toPath(), contents.getBytes());
        return !userDependencies.isEmpty();
    }

    static List<File> parseGradleArtifacts(File artifactManifest) throws IOException, ExtenderException {
        final Object parsed;
        try {
            parsed = new JSONParser().parse(Files.readString(
                    artifactManifest.toPath(),
                    StandardCharsets.UTF_8));
        } catch (ParseException e) {
            throw new ExtenderException(e, "Invalid Gradle artifact manifest: " + artifactManifest);
        }
        if (!(parsed instanceof JSONArray)) {
            throw new ExtenderException("Gradle artifact manifest must contain a JSON array: " + artifactManifest);
        }

        List<File> artifacts = new ArrayList<>();
        Set<String> seenPaths = new LinkedHashSet<>();
        for (Object value : (JSONArray) parsed) {
            if (!(value instanceof JSONObject)) {
                throw new ExtenderException("Invalid entry in Gradle artifact manifest: " + value);
            }
            JSONObject entry = (JSONObject) value;
            Object kindValue = entry.get("kind");
            Object pathValue = entry.get("path");
            if (!(kindValue instanceof String) || !(pathValue instanceof String)) {
                throw new ExtenderException("Gradle artifact entry must contain string kind and path fields: " + entry);
            }

            String kind = (String) kindValue;
            File artifact = new File((String) pathValue).getCanonicalFile();
            if ("exploded-aar".equals(kind)) {
                if (!artifact.isDirectory()) {
                    throw new ExtenderException("Gradle exploded AAR does not exist: " + artifact);
                }
            } else if ("jar".equals(kind)) {
                if (!artifact.isFile() || !artifact.getName().endsWith(".jar")) {
                    throw new ExtenderException("Gradle JAR does not exist: " + artifact);
                }
            } else {
                throw new ExtenderException("Unsupported Gradle artifact kind '" + kind + "': " + artifact);
            }

            if (seenPaths.add(artifact.getAbsolutePath())) {
                artifacts.add(artifact);
            }
        }
        return artifacts;
    }

    static List<String> getGradleResolveCommand() {
        return List.of(
                "gradle",
                "downloadDependencies",
                "dependencies",
                "--configuration",
                "releaseCompileClasspath",
                "--write-locks",
                "--stacktrace",
                "--warning-mode",
                "all",
                "--no-daemon");
    }

    private List<File> resolveGradleArtifacts(
            File cwd,
            File artifactManifest,
            File dependencyTree) throws IOException, ExtenderException {
        long methodStart = System.currentTimeMillis();
        LOGGER.info("Resolving dependencies");
        Files.deleteIfExists(artifactManifest.toPath());

        String log = ProcessUtils.execCommand(getGradleResolveCommand(), cwd,
            Map.of("GRADLE_USER_HOME", this.gradleHome));
        LOGGER.debug("\n" + log);
        Files.writeString(dependencyTree.toPath(), log, StandardCharsets.UTF_8);

        if (!artifactManifest.isFile()) {
            throw new ExtenderException("Gradle did not produce its artifact manifest: " + artifactManifest);
        }
        List<File> artifacts = parseGradleArtifacts(artifactManifest);

        MetricsWriter.metricsTimer(meterRegistry, "extender.service.gradle.get", System.currentTimeMillis() - methodStart);
        return artifacts;
    }

}
