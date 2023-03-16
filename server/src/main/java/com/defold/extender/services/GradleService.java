package com.defold.extender.services;

import com.defold.extender.ExtenderException;
import com.defold.extender.ExtenderUtil;
import com.defold.extender.ProcessExecutor;
import com.defold.extender.TemplateExecutor;
import com.defold.extender.ZipUtils;
import com.defold.extender.metrics.MetricsWriter;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
//import org.springframework.boot.actuate.metrics.CounterService;
//import org.springframework.boot.actuate.metrics.GaugeService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Service
public class GradleService {
    private static final Logger LOGGER = LoggerFactory.getLogger(GradleService.class);
    private static final String ANDROID_SDK_ROOT = System.getenv("ANDROID_SDK_ROOT");
    private static final String ANDROID_SDK_VERSION = System.getenv("ANDROID_SDK_VERSION");
    private static final String BUILD_GRADLE_TEMPLATE_PATH = System.getenv("EXTENSION_BUILD_GRADLE_TEMPLATE");
    private static final String GRADLE_PROPERTIES_TEMPLATE_PATH = System.getenv("EXTENSION_GRADLE_PROPERTIES_TEMPLATE");
    private static final String LOCAL_PROPERTIES_TEMPLATE_PATH = System.getenv("EXTENSION_LOCAL_PROPERTIES_TEMPLATE");
    private static final String GRADLE_USER_HOME = System.getenv("GRADLE_USER_HOME");
    private static final String GRADLE_PLUGIN_VERSION = System.getenv("GRADLE_PLUGIN_VERSION");

    private final TemplateExecutor templateExecutor = new TemplateExecutor();

    private final int cacheSize;
    private final String gradleHome;

    private final File baseDirectory;
    private final MeterRegistry meterRegistry;
    private final String buildGradleTemplateContents;
    private final String gradlePropertiesTemplateContents;
    private final String localPropertiesTemplateContents;

    private String readFile(String filePath) throws IOException {
        if (filePath == null) {
            return "";
        }

        return new String( Files.readAllBytes( Paths.get(filePath) ) );
    }

    @Autowired
    GradleService(@Value("${extender.gradle.cache-size}") int cacheSize,
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

        this.cacheSize = cacheSize;
        this.meterRegistry = meterRegistry;

        // System.out.println(String.format("GradleService"));

        this.baseDirectory = new File(this.gradleHome, "unpacked");
        if (!this.baseDirectory.exists()) {
            Files.createDirectories(this.baseDirectory.toPath());
        }

        this.buildGradleTemplateContents = readFile(BUILD_GRADLE_TEMPLATE_PATH);
        this.gradlePropertiesTemplateContents = readFile(GRADLE_PROPERTIES_TEMPLATE_PATH);
        this.localPropertiesTemplateContents = readFile(LOCAL_PROPERTIES_TEMPLATE_PATH);

        LOGGER.info("GRADLE service using directory {} with cache size {}", GradleService.this.gradleHome, cacheSize);
    }

    // Resolve dependencies, download them, extract to
    public List<File> resolveDependencies(File cwd, Boolean useJetifier) throws IOException, ExtenderException {
        // create build.gradle
        File mainGradleFile = new File(cwd, "build.gradle");
        List<File> gradleFiles = ExtenderUtil.listFilesMatchingRecursive(cwd, "build\\.gradle");
        // This file might exist when testing and debugging the extender using a debug job folder
        gradleFiles.remove(mainGradleFile);
        createBuildGradleFile(mainGradleFile, gradleFiles);

        // create gradle.properties
        File gradlePropertiesFile = new File(cwd, "gradle.properties");
        createGradlePropertiesFile(gradlePropertiesFile, useJetifier);

        // create local.properties
        File localPropertiesFile = new File(cwd, "local.properties");
        createLocalPropertiesFile(localPropertiesFile);

        return downloadDependencies(cwd);
    }

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

    private void createLocalPropertiesFile(File localPropertiesFile) throws IOException {
        HashMap<String, Object> envContext = new HashMap<>();
        envContext.put("android-sdk-root", ANDROID_SDK_ROOT);
        String contents = templateExecutor.execute(localPropertiesTemplateContents, envContext);
        Files.write(localPropertiesFile.toPath(), contents.getBytes());
    }

    private void createBuildGradleFile(File mainGradleFile, List<File> gradleFiles) throws IOException {
        List<String> values = new ArrayList<>();
        for (File file : gradleFiles) {
            values.add(file.getAbsolutePath());
        }
        HashMap<String, Object> envContext = new HashMap<>();
        envContext.put("gradle-files", values);
        envContext.put("compile-sdk-version", ANDROID_SDK_VERSION);
        envContext.put("gradle-plugin-version", GRADLE_PLUGIN_VERSION);
        String contents = templateExecutor.execute(buildGradleTemplateContents, envContext);
        Files.write(mainGradleFile.toPath(), contents.getBytes());
    }

    // Helper function to move files/directories
    private static void Move(Path source, Path target) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // If the target path suddenly exists, and the source path still exists,
            // then we failed with the atomic move, and we assume another job succeeded with the download
            if (Files.exists(source) && Files.exists(target)) {
                LOGGER.info("Gradle package {} was downloaded by another job in the meantime", source.toString());
                try {
                    FileUtils.deleteDirectory(source.toFile());
                } catch (IOException e2) {
                    LOGGER.error("Failed to delete temp directory {}: {}", source.toString(), e2.getMessage());
                }
            }
        }
    }

    private String execCommand(String command, File cwd) throws ExtenderException {
        ProcessExecutor pe = new ProcessExecutor();

        pe.putEnv("GRADLE_USER_HOME", this.gradleHome);

        if (cwd != null) {
            pe.setCwd(cwd);
        }

        try {
            if (pe.execute(command) != 0) {
                throw new ExtenderException(pe.getOutput());
            }
        } catch (IOException | InterruptedException e) {
            throw new ExtenderException(e, pe.getOutput());
        }

        return pe.getOutput();
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
                String type = m.group(3);
                String group = m.group(4);
                String name = m.group(5);
                String version = m.group(6);

                // Map the new name to the original file path
                dependencies.put(String.format("%s-%s-%s.%s", group, name, version, extension), path);
            }
        }

        return dependencies;
    }

    private File resolveDependencyAAR(File dependency, String name, File jobDir) throws IOException {
        File unpackedTarget = new File(baseDirectory, name);
        if (unpackedTarget.exists()) {
            return unpackedTarget;
        }

        // use job folder as tmp location
        File unpackedTmp = new File(jobDir, dependency.getName() + ".tmp");
        ZipUtils.unzip(new FileInputStream(dependency), unpackedTmp.toPath());
        Move(unpackedTmp.toPath(), unpackedTarget.toPath());
        return unpackedTarget;
    }

    private File resolveDependencyJAR(File dependency, String name, File jobDir) throws IOException {
        File targetFile = new File(baseDirectory, name);
        if (targetFile.exists()) {
            return targetFile;
        }

        // use job folder as tmp location
        File tmpFile = new File(jobDir, dependency.getName() + ".tmp");
        FileUtils.copyFile(dependency, tmpFile);
        Move(tmpFile.toPath(), targetFile.toPath());
        return targetFile;
    }

    private List<File> unpackDependencies(Map<String, String> dependencies, File jobDir) throws IOException {
        List<File> resolvedDependencies = new ArrayList<>();
        for (String newName : dependencies.keySet()) {
            String dependency = dependencies.get(newName);

            File file = new File(dependency);
            if (!file.exists()) {
                throw new IOException("File does not exist: %s" + dependency);
            }
            if (dependency.endsWith(".aar"))
                resolvedDependencies.add(resolveDependencyAAR(file, newName, jobDir));
            else if (dependency.endsWith(".jar"))
                resolvedDependencies.add(resolveDependencyJAR(file, newName, jobDir));
            else
                resolvedDependencies.add(file);
        }
        return resolvedDependencies;
    }

    private List<File> downloadDependencies(File cwd) throws IOException, ExtenderException {
        long methodStart = System.currentTimeMillis();
        LOGGER.info("Resolving dependencies");

        String log = execCommand("gradle downloadDependencies --stacktrace --info --warning-mode all", cwd);

        // Put it in the log for the end user to see
        LOGGER.info("\n" + log);

        Map<String, String> dependencies = parseDependencies(log);

        List<File> unpackedDependencies = unpackDependencies(dependencies, cwd);

        MetricsWriter.metricsTimer(meterRegistry, "gauge.service.gradle.get", System.currentTimeMillis() - methodStart);
        return unpackedDependencies;
    }

}
