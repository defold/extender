package com.defold.extender.services;

import com.defold.extender.ExtenderBuildState;
import com.defold.extender.ExtenderException;
import com.defold.extender.ExtenderUtil;
import com.defold.extender.TemplateExecutor;
import com.defold.extender.Timer;
import com.defold.extender.ZipUtils;
import com.defold.extender.metrics.MetricsWriter;
import com.defold.extender.process.ProcessUtils;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(name = "extender.gradle.enabled", havingValue = "true")
public class RealGradleService implements GradleServiceInterface {
    private static final Logger LOGGER = LoggerFactory.getLogger(RealGradleService.class);
    private static final String PROCESSED_DEPENDENCY_CACHE_VERSION = "processed-v1";

    private static final List<String> GRADLE_BLOCKLIST = List.of(
        "buildscript", "apply plugin:", "apply from:",
        "Runtime.getRuntime", "ProcessBuilder", "System.exit",
        "ClassLoader", "Class.forName", "URLClassLoader"
    );
    private static final String GRADLE_USER_HOME = System.getenv("GRADLE_USER_HOME");
    private static final String GRADLE_PLUGIN_VERSION = System.getenv("GRADLE_PLUGIN_VERSION");

    private final TemplateExecutor templateExecutor = new TemplateExecutor();

    private final String gradleHome;

    private final File baseDirectory;
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
            if (!f.exists()) {
                f.mkdirs();
            }
            this.gradleHome = f.getAbsolutePath();
        }

        this.meterRegistry = meterRegistry;

        this.baseDirectory = new File(this.gradleHome, "unpacked");
        if (!this.baseDirectory.exists()) {
            Files.createDirectories(this.baseDirectory.toPath());
        }

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
        createBuildGradleFile(mainGradleFile, gradleFiles, jobEnvContext);

        // create gradle.properties
        File gradlePropertiesFile = new File(workDir, "gradle.properties");
        createGradlePropertiesFile(gradlePropertiesFile, buildState.isUsedJetifier());

        // create local.properties
        File localPropertiesFile = new File(workDir, "local.properties");
        createLocalPropertiesFile(localPropertiesFile, jobEnvContext);

        // download, parse and unpack dependencies
        List<File> unpackedDependencies = downloadDependencies(workDir, buildState.isUsedJetifier());
        // add gradle lockfile to outputs
        // configured in template.build.gradle
        outputFiles.add(new File(buildDir, "gradle.lockfile"));

        // write dependency tree and add to outputs
        File dependencyTreeFile = new File(buildDir, "gradle.dependencytree");
        writeDependencyTree(dependencyTreeFile, workDir);
        outputFiles.add(dependencyTreeFile);

        return unpackedDependencies;
    }

    @Override
    public long getCacheSize() throws IOException {
        Path folder = Paths.get(GRADLE_USER_HOME);
        return Files.walk(folder)
          .filter(p -> p.toFile().isFile())
          .mapToLong(p -> p.toFile().length())
          .sum();
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

    private void createBuildGradleFile(File mainGradleFile, List<File> gradleFiles, Map<String, Object> jobEnvContext) throws IOException, ExtenderException {
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
    }

    private Map<String, String> parseDependencies(String log) {
        // The output comes from template.build.gradle
        Pattern p = Pattern.compile("PATH:\\s*([\\w-.\\/]*)\\sEXTENSION:\\s*([\\w-.\\/]*)\\sTYPE:\\s*([\\w-.\\/]*)\\sMODULE_GROUP:\\s*([\\w-.\\/]*)\\sMODULE_NAME:\\s*([\\w-.\\/]*)\\sMODULE_VERSION:\\s*([\\w-.\\/]*)");

        Map<String, String> dependencies = new HashMap<>();
        String[] lines = log.split(System.getProperty("line.separator"));
        for (String line : lines) {
            Matcher m = p.matcher(line);
            if (m.matches()) {
                String path = m.group(1);
                String extension = m.group(2);
                String group = m.group(4);
                String name = m.group(5);
                String version = m.group(6);

                // Map the new name to the original file path
                dependencies.put(String.format("%s-%s-%s.%s", group, name, version, extension), path);
            }
        }

        return dependencies;
    }

    static String getDependencyCacheNamespace(String gradlePluginVersion, boolean useJetifier) {
        String safePluginVersion = gradlePluginVersion == null
                ? "unknown"
                : gradlePluginVersion.replaceAll("[^A-Za-z0-9_-]", "_");
        return String.format(
                "%s-agp-%s-%s",
                PROCESSED_DEPENDENCY_CACHE_VERSION,
                safePluginVersion,
                useJetifier ? "jetified" : "plain");
    }

    private File getDependencyCacheDirectory(boolean useJetifier) throws IOException {
        File directory = new File(
                baseDirectory,
                getDependencyCacheNamespace(GRADLE_PLUGIN_VERSION, useJetifier));
        Files.createDirectories(directory.toPath());
        return directory;
    }

    static String getDependencyCacheName(File dependency, String extension) throws IOException {
        String contentHash;
        try (InputStream input = new FileInputStream(dependency)) {
            contentHash = ExtenderUtil.calculateSHA256(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
        return contentHash + extension;
    }

    private static void deleteTemporaryPath(Path temporary) throws IOException {
        if (Files.isDirectory(temporary)) {
            FileUtils.deleteDirectory(temporary.toFile());
        } else {
            Files.deleteIfExists(temporary);
        }
    }

    static void publishCacheEntry(Path temporary, Path target) throws IOException {
        publishCacheEntry(temporary, target, () -> {});
    }

    static void publishCacheEntry(
            Path temporary,
            Path target,
            Runnable beforeMove) throws IOException {
        IOException failure = null;
        if (!Files.exists(target)) {
            try {
                beforeMove.run();
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, target);
                }
            } catch (FileAlreadyExistsException e) {
                if (!Files.exists(target)) {
                    failure = e;
                }
            } catch (IOException e) {
                // A concurrent writer of the same content-addressed entry may win
                // with a platform-specific exception (for example, Linux reports
                // Directory not empty for an atomic directory move).
                if (!Files.exists(target)) {
                    failure = e;
                }
            }
        }

        if (Files.exists(temporary)) {
            try {
                deleteTemporaryPath(temporary);
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        if (!Files.exists(target)) {
            throw new IOException("Failed to publish Gradle dependency cache entry: " + target);
        }
    }

    private File resolveDependencyAAR(
            File dependency,
            File cacheDirectory) throws IOException {
        File unpackedTarget = new File(
                cacheDirectory,
                getDependencyCacheName(dependency, ".aar"));
        if (unpackedTarget.exists()) {
            return unpackedTarget;
        }

        Path unpackedTmp = Files.createTempDirectory(cacheDirectory.toPath(), ".aar-");
        try {
            try (InputStream fis = new FileInputStream(dependency)) {
                ZipUtils.unzip(fis, unpackedTmp);
            }
            publishCacheEntry(unpackedTmp, unpackedTarget.toPath());
        } finally {
            if (Files.exists(unpackedTmp)) {
                deleteTemporaryPath(unpackedTmp);
            }
        }
        return unpackedTarget;
    }

    private File resolveDependencyJAR(
            File dependency,
            File cacheDirectory) throws IOException {
        File targetFile = new File(
                cacheDirectory,
                getDependencyCacheName(dependency, ".jar"));
        if (targetFile.exists()) {
            return targetFile;
        }

        Path tmpFile = Files.createTempFile(cacheDirectory.toPath(), ".jar-", ".tmp");
        try {
            Files.copy(dependency.toPath(), tmpFile, StandardCopyOption.REPLACE_EXISTING);
            publishCacheEntry(tmpFile, targetFile.toPath());
        } finally {
            if (Files.exists(tmpFile)) {
                deleteTemporaryPath(tmpFile);
            }
        }
        return targetFile;
    }

    private List<File> unpackDependencies(
            Map<String, String> dependencies,
            boolean useJetifier) throws IOException {
        List<File> resolvedDependencies = new ArrayList<>();
        File cacheDirectory = getDependencyCacheDirectory(useJetifier);
        Timer timer = new Timer();
        timer.start();
        for (String newName : dependencies.keySet()) {
            String dependency = dependencies.get(newName);

            File file = new File(dependency);
            if (!file.exists()) {
                throw new IOException("File does not exist: %s" + dependency);
            }
            if (dependency.endsWith(".aar")) {
                resolvedDependencies.add(resolveDependencyAAR(file, cacheDirectory));
            } else if (dependency.endsWith(".jar")) {
                resolvedDependencies.add(resolveDependencyJAR(file, cacheDirectory));
            } else {
                resolvedDependencies.add(file);
            }
        }
        long duration = timer.start();
        MetricsWriter.metricsTimer(meterRegistry, "extender.service.gradle.unpack", duration);
        return resolvedDependencies;
    }

    private List<File> downloadDependencies(File cwd, boolean useJetifier) throws IOException, ExtenderException {
        long methodStart = System.currentTimeMillis();
        LOGGER.info("Resolving dependencies");

        // add --info for additional logging
        String log = ProcessUtils.execCommand(List.of(
                "gradle",
                "downloadDependencies",
                "--write-locks",
                "--stacktrace",
                "--warning-mode",
                "all",
                "--no-daemon"
            ), cwd,
            Map.of("GRADLE_USER_HOME", this.gradleHome));
        LOGGER.debug("\n" + log);

        Map<String, String> dependencies = parseDependencies(log);

        List<File> unpackedDependencies = unpackDependencies(dependencies, useJetifier);

        MetricsWriter.metricsTimer(meterRegistry, "extender.service.gradle.get", System.currentTimeMillis() - methodStart);
        return unpackedDependencies;
    }

    private void writeDependencyTree(File out, File cwd) throws IOException, ExtenderException {
        long methodStart = System.currentTimeMillis();
        LOGGER.info("Writing dependency tree");

        String treelog = ProcessUtils.execCommand(List.of(
                "gradle",
                "dependencies",
                "--configuration",
                "releaseCompileClasspath",
                "--no-daemon"
            ), cwd, Map.of("GRADLE_USER_HOME", this.gradleHome));
        LOGGER.debug("\n" + treelog);

        Files.write(out.toPath(), treelog.getBytes());

        MetricsWriter.metricsTimer(meterRegistry, "extender.service.gradle.dependencytree", System.currentTimeMillis() - methodStart);
    }

}
