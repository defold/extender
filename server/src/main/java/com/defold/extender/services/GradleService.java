package com.defold.extender.services;

import com.defold.extender.ExtenderException;
import com.defold.extender.ExtenderUtil;
import com.defold.extender.ProcessExecutor;
import com.defold.extender.TemplateExecutor;
import com.defold.extender.ZipUtils;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.metrics.CounterService;
import org.springframework.boot.actuate.metrics.GaugeService;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Service
public class GradleService {
    private static final Logger LOGGER = LoggerFactory.getLogger(GradleService.class);
    private static final String TEMPLATE_PATH = System.getenv("EXTENSION_GRADLE_TEMPLATE");
    private static final String GRADLE_USER_HOME = System.getenv("GRADLE_USER_HOME");

    private final TemplateExecutor templateExecutor = new TemplateExecutor();

    private final int cacheSize;

    private final File baseDirectory;
    private final CounterService counterService;
    private final GaugeService gaugeService;
    private final String templateContents;

    @Autowired
    GradleService(@Value("${extender.gradle.cache-size}") int cacheSize,
                     CounterService counterService,
                     GaugeService gaugeService) throws IOException {
        this.cacheSize = cacheSize;
        this.counterService = counterService;
        this.gaugeService = gaugeService;

        System.out.println(String.format("GradleService"));

        this.baseDirectory = new File(GRADLE_USER_HOME, "unpacked");
        if (!this.baseDirectory.exists()) {
            Files.createDirectories(this.baseDirectory.toPath());
        }

        this.templateContents = new String( Files.readAllBytes( Paths.get(TEMPLATE_PATH) ) );

        LOGGER.info("GRADLE service using directory {} with cache size {}", GradleService.GRADLE_USER_HOME, cacheSize);
    }

    // Resolve dependencies, download them, extract to
    public List<File> resolveDependencies(File cwd) throws IOException, ExtenderException {
        File mainGradleFile = new File(cwd, "build.gradle");

        List<File> gradleFiles = ExtenderUtil.listFilesMatchingRecursive(cwd, "build\\.gradle");

        // This file might exist when testing and debugging the extender using a debug job folder
        gradleFiles.remove(mainGradleFile);

        createBuildGradleFile(mainGradleFile, gradleFiles);

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

    private void createBuildGradleFile(File mainGradleFile, List<File> gradleFiles) throws IOException {
        List<String> values = new ArrayList<>();
        for (File file : gradleFiles) {
            values.add(file.getAbsolutePath());
        }
        HashMap<String, Object> envContext = new HashMap<>();
        envContext.put("gradle-files", values);
        String contents = templateExecutor.execute(templateContents, envContext);

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

        pe.putEnv("GRADLE_USER_HOME", GRADLE_USER_HOME);

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

    static public List<String> parseDependencies(String log) {
        // The output comes from template.build.gradle
        Pattern p = Pattern.compile("(?:.*)\\sFILE:\\s*([\\w-.\\/]*)");

        List<String> dependencies = new ArrayList<>();
        String[] lines = log.split(System.getProperty("line.separator"));
        for (String line : lines) {
            Matcher m = p.matcher(line);
            if (m.matches()) {
                dependencies.add(m.group(1));
            }
        }

        return dependencies;
    }

    private File resolveDependencyAAR(File dependency) throws IOException {
        File unpackedTarget = new File(baseDirectory, dependency.getName());
        if (unpackedTarget.exists()) {
            return unpackedTarget;
        }

        File unpackedTmp = new File(baseDirectory, dependency.getName() + ".tmp");
        ZipUtils.unzip(new FileInputStream(dependency), unpackedTmp.toPath());
        Move(unpackedTmp.toPath(), unpackedTarget.toPath());
        return unpackedTarget;
    }

    private File resolveDependencyJAR(File dependency) throws IOException {
        File targetFile = new File(baseDirectory, dependency.getName());
        if (targetFile.exists()) {
            return targetFile;
        }

        File tmpFile = new File(baseDirectory, dependency.getName() + ".tmp");
        FileUtils.copyFile(dependency, tmpFile);
        Move(tmpFile.toPath(), targetFile.toPath());
        return targetFile;
    }

    private List<File> unpackDependencies(List<String> dependencies) throws IOException {
        List<File> resolvedDependencies = new ArrayList<>();
        for (String dependency : dependencies) {
            File file = new File(dependency);
            if (!file.exists()) {
                throw new IOException("File does not exist: %s" + dependency);
            }
            if (dependency.endsWith(".aar"))
                resolvedDependencies.add(resolveDependencyAAR(file));
            else if (dependency.endsWith(".jar"))
                resolvedDependencies.add(resolveDependencyJAR(file));
            else
                resolvedDependencies.add(file); // .jar file
        }
        return resolvedDependencies;
    }

    private List<File> downloadDependencies(File cwd) throws IOException, ExtenderException {
        long methodStart = System.currentTimeMillis();
        LOGGER.info("Resolving dependencies");

        String log = execCommand("gradle downloadDependencies --warning-mode all", cwd);

        // Put it in the log for the end user to see
        LOGGER.info("\n" + log);

        List<String> dependencies = parseDependencies(log);

        List<File> unpackedDependencies = unpackDependencies(dependencies);

        gaugeService.submit("gauge.service.gradle.get", System.currentTimeMillis() - methodStart);
        return unpackedDependencies;
    }

}
