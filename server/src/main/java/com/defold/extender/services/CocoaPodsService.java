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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Service
public class CocoaPodsService {
    private static final Logger LOGGER = LoggerFactory.getLogger(CocoaPodsService.class);

    private final File baseDirectory;
    private final MeterRegistry meterRegistry;

    private String readFile(String filePath) throws IOException {
        if (filePath == null) {
            return "";
        }

        return new String( Files.readAllBytes( Paths.get(filePath) ) );
    }

    @Autowired
    CocoaPodsService(@Value("${extender.gradle.cache-size}") int cacheSize,
                     MeterRegistry meterRegistry) throws IOException {
        this.meterRegistry = meterRegistry;

        LOGGER.info("CocoaPodsService service using directory {} with cache size {}", GradleService.this.gradleHome, cacheSize);
    }


    private void parsePodFiles() {

    }
    private void createMainPodFile(File cwd, List<File> podFiles) throws IOException {
        File mainPodFile = new File(cwd, "Podfile");

        List<File> podFiles = ExtenderUtil.listFilesMatchingRecursive(cwd, "Podfile");

        // This file might exist when testing and debugging the extender using a debug job folder
        podFiles.remove(mainPodFile);
        String mainPodFileContents = "";
        for (File podFile : podFiles) {
            String pod = readFile(podFile.getAbsolutePath());
            mainPodFileContents += pod + "\n";
        }
        mainPodFileContents += "target 'Foobar' do end\n";

        Files.write(mainPodFile.toPath(), mainPodFileContents.getBytes());
    }

    // Install dependencies
    public List<File> installPods(File cwd) throws IOException, ExtenderException {


        createMainPodFile(mainPodFile, podFiles);
        createXcodeProjectFile()
        parsePodFiles(cwd);

        long methodStart = System.currentTimeMillis();
        LOGGER.info("Installing pod files");

        String log = execCommand("pod install --verbose", cwd);
        // Put it in the log for the end user to see
        LOGGER.info("\n" + log);

        // parse Podfile.lock and get list of resolved dependencies

        MetricsWriter.metricsTimer(meterRegistry, "gauge.service.cocoapods.get", System.currentTimeMillis() - methodStart);


        return downloadDependencies(cwd);
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

    static public Map<String, String> parseDependencies(String log) {
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
        LOGGER.info("Installing pod files");

        String log = execCommand("pod install --verbose", cwd);

        // Put it in the log for the end user to see
        LOGGER.info("\n" + log);

        Map<String, String> dependencies = parsePodFiles(log);

        List<File> unpackedDependencies = unpackDependencies(dependencies, cwd);

        MetricsWriter.metricsTimer(meterRegistry, "gauge.service.gradle.get", System.currentTimeMillis() - methodStart);
        return unpackedDependencies;
    }

}
