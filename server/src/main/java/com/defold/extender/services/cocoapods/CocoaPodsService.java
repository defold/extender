package com.defold.extender.services.cocoapods;

import com.defold.extender.ExtenderException;
import com.defold.extender.ExtenderUtil;
import com.defold.extender.TemplateExecutor;
import com.defold.extender.PlatformConfig;
import com.defold.extender.metrics.MetricsWriter;
import com.defold.extender.process.ProcessUtils;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Scheduled;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.LinkedHashSet;
import java.util.Collections;


@Service
@ConditionalOnProperty(prefix = "extender", name = "cocoapods.enabled", havingValue = "true")
public class CocoaPodsService {

    static MainPodfile createMainPodfile() {
        return new MainPodfile();
    }

    private class InstalledPods {
        public Map<String, PodSpec> podsMap = new HashMap<>();
        public List<PodSpec> pods = new ArrayList<>();
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(CocoaPodsService.class);
    private static final String CURRENT_CACHE_DIR_FILE = "current_pod_cache.txt";
    private static final String OLD_CACHE_DIR_FILE = "old_pod_caches.txt";
    private final TemplateExecutor templateExecutor = new TemplateExecutor();

    private final String podfileTemplateContents;
    private final String modulemapTemplateContents;
    private final String umbrellaHeaderTemplateContents;
    private @Value("${extender.cocoapods.home-dir-prefix}") String homeDirPrefix;
    private @Value("${extender.cocoapods.cdn-concurrency:10}") int maxPodCDNConcurrency;
    private Path currentCacheDir = Path.of("");

    private final MeterRegistry meterRegistry;

    CocoaPodsService(@Value("classpath:template.podfile") Resource podfileTemplate,
            @Value("classpath:template.modulemap") Resource modulemapTemplate,
            @Value("classpath:template.umbrella.h") Resource umbrellaHeaderTemplate,
            MeterRegistry meterRegistry) throws IOException {
        this.meterRegistry = meterRegistry;
        this.podfileTemplateContents = ExtenderUtil.readContentFromResource(podfileTemplate);
        this.modulemapTemplateContents = ExtenderUtil.readContentFromResource(modulemapTemplate);
        this.umbrellaHeaderTemplateContents = ExtenderUtil.readContentFromResource(umbrellaHeaderTemplate);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void runAfterStartup() {
        // initialize cache directory
        Path currentCacheDir = readCurrentCacheDir();
        if (currentCacheDir != null && currentCacheDir.startsWith(this.homeDirPrefix)) {
            synchronized(this.currentCacheDir) {
                this.currentCacheDir = currentCacheDir;
            }
            updateSpecRepo();
        } else {
            LOGGER.info("Cocoapods has no current cache dir or prefix is changed. Created...");
            synchronized(this.currentCacheDir) {
                this.currentCacheDir = generateCacheDirPath();
                storeCurrentCacheDir(this.currentCacheDir);
            }
            initializeTrunkRepo();
        }
        cleanupOldCacheDirectories();
        LOGGER.info("Cocoapods startup task completed");
    }

    // debugging function for printing a directory structure with files and folders
    private static void dumpDir(File file, int indent) throws IOException {
        String indentString = "";
        for (int i = 0; i < indent; i++) {
            indentString += "-";
        }
        LOGGER.info(indentString + file.getName());
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            for (int i = 0; i < files.length; i++) {
                dumpDir(files[i], indent + 3);
            }
        }
    }

    // https://www.baeldung.com/java-comparing-versions#customSolution
    private static int compareVersions(String version1, String version2) {
        int result = 0;
        String[] parts1 = version1.split("\\.");
        String[] parts2 = version2.split("\\.");
        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++){
            Integer v1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            Integer v2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
            int compare = v1.compareTo(v2);
            if (compare != 0) {
                result = compare;
                break;
            }
        }
        return result;
    }

    /**
     * Create the main Podfile with a list of all dependencies for all uploaded extensions
     * @param podFiles List of podfiles to merge into the main Pofile
     * @param jobDirectory The job directory from where to search for Podfiles
     * @param workingDir The working directory where pods should be resolved
     * @param platform For which platform to resolve pods
     * @return Main pod file structure
     */
    private MainPodfile createMainPodfile(List<File> podFiles, File jobDirectory, File workingDir, String platform, Map<String, Object> jobEnvContext) throws IOException {
        MainPodfile mainPodfile = createMainPodfile();

        mainPodfile.file = new File(workingDir, "Podfile");

        // This file might exist when testing and debugging the extender using a debug job folder
        podFiles.remove(mainPodfile.file);

        mainPodfile.platformMinVersion = (platform.contains("ios") ? 
            jobEnvContext.get("env.IOS_VERSION_MIN").toString(): 
            jobEnvContext.get("env.MACOS_VERSION_MIN").toString());
        mainPodfile.platform = (platform.contains("ios") ? "ios" : "osx");


        // Load all Podfiles
        List<String> pods = parsePodfiles(mainPodfile, podFiles);

        // Create main Podfile contents
        HashMap<String, Object> envContext = new HashMap<>();
        envContext.put("PLATFORM", mainPodfile.platform);
        envContext.put("PLATFORM_VERSION", mainPodfile.platformMinVersion);
        envContext.put("PODS", pods);
        String mainPodfileContents = templateExecutor.execute(podfileTemplateContents, envContext);
        LOGGER.info("Created main Podfile:\n{}", mainPodfileContents);

        Files.write(mainPodfile.file.toPath(), mainPodfileContents.getBytes());

        return mainPodfile;
    }

    // copy the files and folders of a directory recursively
    // the function will also resolve symlinks while copying files and folders
    private void copyDirectoryRecursively(File fromDir, File toDir) throws IOException {
        toDir.mkdirs();
        File[] files = fromDir.toPath().toRealPath().toFile().listFiles();
        for (File file : files) {
            if (file.isDirectory()) {
                File sourceDir = file;
                File destDir = new File(toDir, file.getName());
                copyDirectoryRecursively(sourceDir, destDir);
            }
            else {
                // follow symlink (if one exists) and then copy file
                File sourceFile = file.toPath().toRealPath().toFile();
                File destFile = new File(toDir, sourceFile.getName());
                Files.copy(sourceFile.toPath(), destFile.toPath());
            }
        }
    }

    private void stripBitcode(File file, PlatformConfig config) throws ExtenderException {
        String command = null;

        // bitcodeStripCmd added in 1.8.1
        if (config.bitcodeStripCmd != null) {
            Map<String, Object> context = new HashMap<>(config.context);
            context.put("source", file.getAbsolutePath());
            context.put("target", file.getAbsolutePath());

            command = templateExecutor.execute(config.bitcodeStripCmd, context);
        }
        else {
            command = String.format("bitcode_strip %s -r -o %s", file.getAbsolutePath(), file.getAbsolutePath());
        }

        String log = ProcessUtils.execCommand(command);
        LOGGER.info("\n" + log);
    }

    // helper function to copy the frameworks, static libs and headers from a
    // platform architecture folder (inside an .xcframework)
    private void copyPodFrameworksFromArchitectureDir(File architectureDir, File destFrameworkDir, File destHeaderDir, PlatformConfig config) throws IOException, ExtenderException {
        File[] files = architectureDir.listFiles();
        for (File file : files) {
            String filename = file.getName();
            if (filename.endsWith(".framework")) {
                String frameworkName = filename.replace(".framework", "");
                File lib = new File(file, frameworkName);
                if (lib.exists()) {
                    stripBitcode(lib, config);
                }
                File from = file;
                File to = new File(destFrameworkDir, filename);
                copyDirectoryRecursively(from, to);
            }
            // static libs
            else if (filename.endsWith(".a")) {
                Path from = file.toPath().toRealPath();
                Path to = new File(destFrameworkDir, filename).toPath();
                Files.copy(from, to);
            }
            // headers (for the static libs)
            else if (filename.equals("Headers")) {
                File from = file;
                File to = destHeaderDir;
                copyDirectoryRecursively(from, to);
            }
        }
    }

    /**
     * Find all .xcframeworks (vendored frameworks) in a list of pods and copy any arm
     * and x86 .framworks for use later when building extensions using the pods
     * @param pods The pods to process
     * @param frameworksDir Directory where to copy frameworks and framework libs
     * @param config Platform config
     */
    private void copyPodFrameworks(List<PodSpec> pods, File frameworksDir, PlatformConfig config) throws IOException, ExtenderException {
        LOGGER.info("Copying pod frameworks");
        File libDir = new File(frameworksDir, "lib");
        File armLibDir = new File(libDir, "arm64-ios");
        File x86LibDir = new File(libDir, "x86_64-ios");
        armLibDir.mkdirs();
        x86LibDir.mkdirs();

        File headersDir = new File(frameworksDir, "headers");
        File armHeaderDir = new File(headersDir, "arm64-ios");
        File x86HeaderDir = new File(headersDir, "x86_64-ios");
        armHeaderDir.mkdirs();
        x86HeaderDir.mkdirs();

        for (PodSpec pod : pods) {
            for (String framework : pod.vendoredFrameworks) {
                File frameworkDir = new File(pod.dir, framework);
                String frameworkName = frameworkDir.getName().replace(".xcframework", "");
                
                File arm64_armv7FrameworkDir = new File(frameworkDir, "ios-arm64_armv7");
                File arm64FrameworkDir = new File(frameworkDir, "ios-arm64");
                if (arm64_armv7FrameworkDir.exists()) {
                    copyPodFrameworksFromArchitectureDir(arm64_armv7FrameworkDir, armLibDir, armHeaderDir, config);
                }
                else if (arm64FrameworkDir.exists()) {
                    copyPodFrameworksFromArchitectureDir(arm64FrameworkDir, armLibDir, armHeaderDir, config);
                }
                
                File arm64_i386_x86Framework = new File(frameworkDir, "ios-arm64_i386_x86_64-simulator");
                File arm64_x86Framework = new File(frameworkDir, "ios-arm64_x86_64-simulator");
                if (arm64_i386_x86Framework.exists()) {
                    copyPodFrameworksFromArchitectureDir(arm64_i386_x86Framework, x86LibDir, x86HeaderDir, config);
                }
                else if (arm64_x86Framework.exists()) {
                    copyPodFrameworksFromArchitectureDir(arm64_x86Framework, x86LibDir, x86HeaderDir, config);
                }
            }
        }
    }

    // 'GoogleUtilities/Environment (7.10.0)'  -> 'GoogleUtilities/Environment' -> ['GoogleUtilities', 'Environment']
    private String[] splitPodname(String pod) {
        return pod.replaceFirst(" \\(.*\\)", "").split("/");
    }

    /**
     * Get pod spec based on a pod name with optional sub pod (GoogleUtilities/Environment)
     * @param pods Map of pod names to pod specs
     * @param podname The pod to find
     * @return The pod spec
     */
    private PodSpec getPod(Map<String, PodSpec> pods, String podname) throws ExtenderException {
        // 'GoogleUtilities/Environment (7.10.0)'  -> 'GoogleUtilities/Environment' -> ['GoogleUtilities', 'Environment']
        String podnameparts[] = splitPodname(podname);
        // 'GoogleUtilities'
        String mainpodname = podnameparts[0];
        PodSpec current = pods.get(mainpodname);
        if (podnameparts.length > 1) {
            for (int i = 1; i < podnameparts.length; i++) {
                String subspecname = podnameparts[i];
                PodSpec subspec = current.getSubspec(subspecname);
                if (subspec == null) {
                    throw new ExtenderException("Unable to find subspec '" + subspecname + "' in pod '" + current.name + "'");
                }
                current = subspec;
            }
        }
        return current;
    }

    /**
     * Get a sorted set of pod specs with dependencies from a list of pod names.
     * This function will recursively go through all pods and add their
     * dependencies to the final set of pods. The pod specs will be added to the
     * set such that the dependencies of a pod are added before their pod itself.
     * @param specsMap Map with pod specs to search for pods
     * @param podnames Names of the pods to get
     * @return Set of pod specs
     */
    private LinkedHashSet<PodSpec> getSpecsAndDependencies(Map<String, PodSpec> specsMap, List<String> podnames) throws ExtenderException {
        LinkedHashSet<PodSpec> specs = new LinkedHashSet<>();
        for (String podname : podnames) {
            PodSpec spec = getPod(specsMap, podname);
            specs.addAll(getSpecsAndDependencies(specsMap, spec.dependencies));
            specs.add(spec);
        }
        return specs;
    }

    /**
     * Install pods from a podfile and create PodSpec instances for each installed pod.
     * @param jobDir Directory of entire build job
     * @param workingDir Directory where to install pods. The directory must contain a valid Podfile
     * @param jobEnvContext Job environment context which contains all the job environment variables with `env.*` keys
     * @return An InstalledPods object with installed pods
     */
    private InstalledPods installPods(File jobDir, File workingDir, File generatedDir, Map<String, Object> jobEnvContext) throws IOException, ExtenderException {
        LOGGER.info("Installing pods");
        Path cacheDir;
        // store current cache dir into local variable to use the same value for all 'pod' runs
        synchronized(currentCacheDir) {
            cacheDir = currentCacheDir;
        }
        InstalledPods installedPods = new InstalledPods();

        File podFile = new File(workingDir, "Podfile");
        if (!podFile.exists()) {
            throw new ExtenderException("Unable to find Podfile " + podFile);
        }
        File dir = podFile.getParentFile();
        String log = ProcessUtils.execCommand(List.of(
                "pod",
                "install",
                "--verbose"
            ), workingDir, Map.of("CP_HOME_DIR", cacheDir.toString(),
            "COCOAPODS_CDN_MAX_CONCURRENCY", String.valueOf(maxPodCDNConcurrency)));
        LOGGER.debug("\n" + log);

        File podFileLock = new File(workingDir, "Podfile.lock");
        if (!podFileLock.exists()) {
            throw new ExtenderException("Unable to find Podfile.lock in directory " + dir);
        }
        LOGGER.info("Podfile.lock:\n{}", FileUtils.readFileToString(podFileLock, Charset.defaultCharset()));

        /* Parse Podfile.lock and get all pods. Example Podfile.lock:

        PODS:
          - FirebaseAnalytics (8.13.0):
            - FirebaseAnalytics/AdIdSupport (= 8.13.0)
            - FirebaseCore (~> 8.0)
            - FirebaseInstallations (~> 8.0)
          - FirebaseAnalytics/AdIdSupport (8.13.0):
            - FirebaseCore (~> 8.0)
            - FirebaseInstallations (~> 8.0)
            - GoogleAppMeasurement (= 8.13.0)
        */
        List<String> lines = Files.readAllLines(podFileLock.toPath());
        Set<String> podnames = new LinkedHashSet<>();
        while (!lines.isEmpty()) if (lines.remove(0).startsWith("PODS:")) break;
        while (!lines.isEmpty()) {
            String line = lines.remove(0);
            if (line.trim().isEmpty()) break;
            if (line.startsWith("  -")) {
                // '  - "GoogleUtilities/Environment (7.10.0)":'   ->   'GoogleUtilities/Environment (7.10.0)'
                String podname = line.trim().replace("- ", "").replace(":", "").replace("\"","");
                podnames.add(podname);
            }
        }

        // get all the pod specs and store them in a map, keyed on pod name
        File podsDir = new File(workingDir, "Pods");
        for (String podname : podnames) {
            // 'GoogleUtilities/Environment (7.10.0)'  -> 'GoogleUtilities/Environment' -> ['GoogleUtilities', 'Environment']
            String podnameparts[] = splitPodname(podname);
            // 'GoogleUtilities'
            String mainpodname = PodUtils.sanitizePodName(podnameparts[0]);
            // 'GoogleUtilities/Environment (7.10.0)'  -> '7.10.0'
            String podversion = podname.replaceFirst(".*\\(", "").replace(")", "");
            if (!installedPods.podsMap.containsKey(mainpodname)) {
                String cmd = String.format("pod spec cat --regex ^%s$ --version=%s", mainpodname, podversion);
                String specJson = ProcessUtils.execCommand(cmd, null, Map.of("CP_HOME_DIR", cacheDir.toString())).replace(cmd, "");
                // find first occurence of { because in some cases pod command
                // can produce additional output before json spec
                // For example:
                // Ignoring ffi-1.15.4 because its extensions are not built. Try: gem pristine ffi --version 1.15.4
                // {
                //     "authors": "Google, Inc.",
                //     "cocoapods_version": ">= 1.9.0",
                //     "dependencies": {
                //     "GoogleAppMeasurement": [
                specJson = specJson.substring(specJson.indexOf("{", 0), specJson.length());
                PodSpecParser.CreatePodSpecArgs args = new PodSpecParser.CreatePodSpecArgs.Builder()
                    .setSpecJson(specJson)
                    .setPodsDir(podsDir)
                    .setGeneratedDir(generatedDir)
                    .setWorkingDir(jobDir)
                    .setJobContext(jobEnvContext)
                    .setUmbrellaHeaderGenerator(context -> { return templateExecutor.execute(umbrellaHeaderTemplateContents, context); } )
                    .setModuleMapGenerator(context -> { return templateExecutor.execute(modulemapTemplateContents, context); })
                    .build();
                installedPods.podsMap.put(mainpodname, PodSpecParser.createPodSpec(args));
            }
        }

        // build list of specs and their dependencies (dependencies first)
        List<String> reversePodnames = new ArrayList<>(podnames);
        Collections.reverse(reversePodnames);
        installedPods.pods.addAll(getSpecsAndDependencies(installedPods.podsMap, reversePodnames));
        LOGGER.info("Installed pods");
        for (PodSpec pod : installedPods.pods) {
            LOGGER.info("  " + pod.name);
        }
        return installedPods;
    }

    private Map<String, Object> createJobEnvContext(Map<String, Object> env) {
        Map<String, Object> context = new HashMap<>(env);
        context.putIfAbsent("env.IOS_VERSION_MIN", System.getenv("IOS_VERSION_MIN"));
        context.putIfAbsent("env.MACOS_VERSION_MIN", System.getenv("MACOS_VERSION_MIN"));
        return context;
    }

    /**
     * Entry point for Cocoapod dependency resolution.
     * @param config Platform config 
     * @param jobDir Root directory of the job to resolve
     * @param platform Which platform to resolve pods for
     * @return ResolvedPods instance with list of pods, install directory etc
     */
    public ResolvedPods resolveDependencies(PlatformConfig config, File jobDir, String platform) throws IOException, ExtenderException {
        if (!platform.contains("ios") && !platform.contains("osx")) {
            throw new ExtenderException("Unsupported platform " + platform);
        }

        Map<String, Object> jobEnvContext = createJobEnvContext(config.context);

        // find all podfiles and filter down to a list of podfiles specifically
        // for the platform we are resolving pods for
        List<File> allPodfiles = ExtenderUtil.listFilesMatchingRecursive(jobDir, "Podfile");
        List<File> platformPodfiles = new ArrayList<>();
        for (File podFile : allPodfiles) {
            String parentFolder = podFile.getParentFile().getName();
            if ((platform.contains("ios") && parentFolder.contains("ios")) ||
                (platform.contains("osx") && parentFolder.contains("osx"))) {
                platformPodfiles.add(podFile);
            }
            else {
                LOGGER.warn("Unexpected Podfile found in " + podFile);
            }
        }
        if (platformPodfiles.isEmpty()) {
            LOGGER.info("Project has no Cocoapod dependencies");
            return null;
        }

        long methodStart = System.currentTimeMillis();
        LOGGER.info("Resolving Cocoapod dependencies");

        File workingDir = new File(jobDir, "CocoaPodsService");
        File frameworksDir = new File(workingDir, "frameworks");
        File generatedDir = new File(workingDir, "generated");
        workingDir.mkdirs();
        generatedDir.mkdirs();

        MainPodfile mainPodfile = createMainPodfile(platformPodfiles, jobDir, workingDir, platform, jobEnvContext);
        InstalledPods installedPods = installPods(jobDir, workingDir, generatedDir, jobEnvContext);
        List<PodSpec> allPods = installedPods.pods;
        List<PodSpec> pods = new ArrayList<>();
        pods = allPods;
        copyPodFrameworks(pods, frameworksDir, config);

        dumpDir(jobDir, 0);

        MetricsWriter.metricsTimer(meterRegistry, "extender.service.cocoapods.get", System.currentTimeMillis() - methodStart);

        ResolvedPods resolvedPods = new ResolvedPods();
        resolvedPods.pods = pods;
        resolvedPods.platformMinVersion = mainPodfile.platformMinVersion;
        resolvedPods.podsDir = new File(workingDir, "Pods");
        resolvedPods.frameworksDir = frameworksDir;
        resolvedPods.generatedDir = generatedDir;
        resolvedPods.podFileLock = new File(workingDir, "Podfile.lock");

        LOGGER.info("Resolved Cocoapod dependencies");
        LOGGER.info(resolvedPods.toString());

        return resolvedPods;
    }

    static List<String> parsePodfiles(MainPodfile mainPodfile, List<File> podFiles) throws IOException {
        // Load all Podfiles
        Pattern podPattern = Pattern.compile("pod '([\\w|-]+)'.*");
        List<String> pods = new ArrayList<>();
        for (File podFile : podFiles) {
            // Split each file into lines and go through them one by one
            // Search for a Podfile platform and version configuration, examples:
            //   platform :ios, '9.0'
            //   platform :osx, '10.2'
            // Get the version and figure out which is the highest version defined. This
            // version will be used in the combined Podfile created by this function. 
            // Treat everything else as pods
            List<String> lines = Files.readAllLines(podFile.getAbsoluteFile().toPath());
            for (String line : lines) {
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith("platform :")) {
                    String version = line.replaceFirst("platform :ios|platform :osx", "").replace(",", "").replace("'", "").trim();
                    if (!version.isEmpty() && (compareVersions(version, mainPodfile.platformMinVersion) > 0)) {
                        mainPodfile.platformMinVersion = version;
                    }
                }
                else {
                    pods.add(line);

                    Matcher matcher = podPattern.matcher(line);
                    if (matcher.matches()) {
                        // get the pod name from the line
                        // example: pod 'KSCrash', '1.17.4' -> KSCrash
                        String podname = matcher.group(1);
                        mainPodfile.podnames.add(podname);
                    }
                }
            }
        }
        return pods;
    }

    private Path generateCacheDirPath() {
        return Path.of(this.homeDirPrefix, UUID.randomUUID().toString());
    }

    private Path readCurrentCacheDir() {
        File currentCacheDirFile = Path.of(this.homeDirPrefix, CocoaPodsService.CURRENT_CACHE_DIR_FILE).toFile();
        if (!currentCacheDirFile.exists()) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(currentCacheDirFile))) {
            String strPath = reader.readLine();
            if (strPath != null) {
                Path result = Path.of(strPath);
                return result.toFile().exists() ? result : null;
            }
        } catch(IOException io) {
            LOGGER.warn("Exception while read old cache paths file", io);
        }
        return null;
    }

    private void storeCurrentCacheDir(Path currentCacheDir) {
        try {
            Files.createDirectories(Path.of(this.homeDirPrefix));
        } catch (IOException exc) {
            LOGGER.warn("Can't create directories to store pod cache path", exc);
            return;
        }
        try (FileWriter writer = new FileWriter(new File(this.homeDirPrefix, CocoaPodsService.CURRENT_CACHE_DIR_FILE))) {
            writer.append(currentCacheDir.toAbsolutePath().toString());
            writer.close();
        } catch (IOException exc) {
            LOGGER.warn("Error while writing to current cache path file", exc);
        }
    }

    private void initializeTrunkRepo() {
        try {
            Path cacheDir;
            synchronized(currentCacheDir) {
                cacheDir = currentCacheDir;
            }
            String log = ProcessUtils.execCommand(List.of(
                    "pod",
                    "repo",
                    "add-cdn",
                    "trunk",
                    "https://cdn.cocoapods.org/",
                    "--verbose"
                ), null,
                Map.of("CP_HOME_DIR", cacheDir.toString()));
            LOGGER.debug("\n" + log);
        } catch(ExtenderException exc) {
            LOGGER.warn("Exception during repo init", exc);
        }        
    }

    @Scheduled(cron="${extender.cocoapods.cache-dir-rotate-cron}")
    public void rotatePodCacheDirectory() {
        LOGGER.info("Rotate pod cache directory");
        Path newCacheDir = generateCacheDirPath();
        Path cacheDir;
        synchronized(this.currentCacheDir) {
            cacheDir = this.currentCacheDir;
        }
        try {
            Files.createDirectories(newCacheDir);
        } catch(IOException|UnsupportedOperationException|SecurityException exc) {
            LOGGER.warn("Cannot create new pod cache directory", exc);
            return;
        }
            
        try (FileWriter writer = new FileWriter(new File(this.homeDirPrefix, CocoaPodsService.OLD_CACHE_DIR_FILE), true)) {
            writer.append(cacheDir.toAbsolutePath().toString());
            writer.append("\n");
            writer.close();
        } catch(IOException exc) {
            LOGGER.warn("Error while writing to old cache paths file", exc);
        }
        synchronized(this.currentCacheDir) {
            this.currentCacheDir = newCacheDir;
            storeCurrentCacheDir(currentCacheDir);
        }
        initializeTrunkRepo();
    }

    @Scheduled(cron="${extender.cocoapods.old-cache-clean-cron}")
    private void cleanupOldCacheDirectories() {
        LOGGER.info("Cleanup old cache directories");
        File oldDirFile = Path.of(this.homeDirPrefix, CocoaPodsService.OLD_CACHE_DIR_FILE).toFile();
        if (oldDirFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(oldDirFile))) {
                String strPath = reader.readLine();
                while (strPath != null) {
                    LOGGER.info("Remove old pod cache directory: {}", strPath);
                    Path dirPath = Path.of(strPath);
                    File path = dirPath.toFile();
                    if (path.exists()) {
                        FileUtils.deleteDirectory(path);
                    }
                    strPath = reader.readLine();
                }
            } catch(IOException io) {
                LOGGER.warn("Exception while read old cache paths file", io);
            }
            oldDirFile.delete();
        } else {
            LOGGER.warn("File with old cache paths doesn't exist. Cleanup skipped");
        }
    }

    @Scheduled(initialDelay=3600000, fixedDelayString="${extender.cocoapods.repo-update-interval:3600000}")
    public void updateSpecRepo() {
        try {
            LOGGER.info("Run pod spec update");
            Path cacheDir;
            synchronized(currentCacheDir) {
                cacheDir = currentCacheDir;
            }
            String log = ProcessUtils.execCommand(List.of(
                    "pod",
                    "repo",
                    "update",
                    "--verbose"
                ), null,
                Map.of("CP_HOME_DIR", cacheDir.toString(),
                    "COCOAPODS_CDN_MAX_CONCURRENCY", String.valueOf(maxPodCDNConcurrency)));
            LOGGER.debug("\n" + log);
        } catch(ExtenderException exc) {
            LOGGER.warn("Exception during spec repo update", exc);
        }
    }
}
