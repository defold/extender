package com.defold.extender.services.cocoapods;

import com.defold.extender.ExtenderBuildState;
import com.defold.extender.ExtenderException;
import com.defold.extender.ExtenderUtil;
import com.defold.extender.TemplateExecutor;
import com.defold.extender.PlatformConfig;
import com.defold.extender.metrics.MetricsWriter;
import com.defold.extender.process.ProcessUtils;

import org.apache.commons.io.FileUtils;
import org.json.simple.JSONObject;
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
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
@ConditionalOnProperty(prefix = "extender", name = "cocoapods.enabled", havingValue = "true")
public class CocoaPodsService {

    static MainPodfile mainPodfileFromParseResult(PodfileParser.ParseResult parseResult, File workingDir) {
        MainPodfile res = new MainPodfile();
        res.file = new File(workingDir, "Podfile");
        res.platform = parseResult.platform;
        res.platformMinVersion = parseResult.minVersion;
        res.podDefinitions = parseResult.podDefinitions;
        res.useFrameworks = parseResult.useFrameworks;
        return res;
    }

    private static class InstalledPods {
        public Map<String, PodSpec> podsMap = new HashMap<>();
        // set of pod's specs to present build order
        public Set<String> pods = new LinkedHashSet<>();
        public File podfileLock;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(CocoaPodsService.class);
    private static final String CURRENT_CACHE_DIR_FILE = "current_pod_cache.txt";
    private static final String OLD_CACHE_DIR_FILE = "old_pod_caches.txt";
    private final Object syncLock = new Object();
    // Guards the contents of the CocoaPods home directory (CP_HOME_DIR).
    // Pod installations take the read lock and run concurrently with each other, while anything
    // that mutates the shared spec repo or download cache (repo update, cache rotation, cleanup)
    // takes the write lock. Without this a 'pod repo update' or a cache rotation can run while a
    // build is doing 'pod install', which may resolve against half-written CDN metadata or copy a
    // partially extracted pod out of the shared download cache. A fair lock is used so that a
    // pending repo update is not starved by a continuous stream of builds.
    private final ReentrantReadWriteLock cacheLock = new ReentrantReadWriteLock(true);
    private final TemplateExecutor templateExecutor = new TemplateExecutor();

    private final String podfileTemplateContents;
    private final String modulemapTemplateContents;
    private @Value("${extender.cocoapods.home-dir-prefix}") String homeDirPrefix;
    private @Value("${extender.cocoapods.cdn-concurrency:10}") int maxPodCDNConcurrency;
    private Path currentCacheDir = Path.of("");

    private final MeterRegistry meterRegistry;

    CocoaPodsService(@Value("classpath:template.podfile") Resource podfileTemplate,
            @Value("classpath:template.modulemap") Resource modulemapTemplate,
            MeterRegistry meterRegistry) throws IOException {
        this.meterRegistry = meterRegistry;
        this.podfileTemplateContents = ExtenderUtil.readContentFromResource(podfileTemplate);
        this.modulemapTemplateContents = ExtenderUtil.readContentFromResource(modulemapTemplate);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void runAfterStartup() {
        // initialize cache directory
        Path currentCacheDir = readCurrentCacheDir();
        if (currentCacheDir != null && currentCacheDir.startsWith(this.homeDirPrefix)) {
            synchronized(this.syncLock) {
                this.currentCacheDir = currentCacheDir;
            }
            updateSpecRepo();
        } else {
            LOGGER.info("Cocoapods has no current cache dir or prefix is changed. Created...");
            Path newCacheDir = generateCacheDirPath();
            try {
                Files.createDirectories(newCacheDir);
            } catch(IOException|UnsupportedOperationException|SecurityException exc) {
                LOGGER.warn("Cannot create pod cache directory {}", newCacheDir, exc);
            }
            synchronized(this.syncLock) {
                this.currentCacheDir = newCacheDir;
                storeCurrentCacheDir(newCacheDir);
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
        LOGGER.debug(indentString + file.getName());
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            for (int i = 0; i < files.length; i++) {
                dumpDir(files[i], indent + 3);
            }
        }
    }

    /**
     * Create the main Podfile with a list of all dependencies for all uploaded extensions
     * @param buildState Extender's build state
     * @param cocoapodsBuildState Cocoapods build state
     * @param jobEnvContext Map of environemnt variables defined for the current build
     * @return Main pod file structure
     * @throws PodfileParsingException
     * @throws IOException 
     */
    private MainPodfile createMainPodfile(ExtenderBuildState buildState, CocoaPodsServiceBuildState cocoapodsBuildState, List<File> podFiles, Map<String, Object> jobEnvContext) throws IOException, PodfileParsingException {
        boolean isIOS = ExtenderUtil.isIOSTarget(buildState.getBuildPlatform());
        String podPlatform = isIOS ? "ios" : "osx";
        String defaultMinVersion = (isIOS ? 
            jobEnvContext.get("env.IOS_VERSION_MIN").toString(): 
            jobEnvContext.get("env.MACOS_VERSION_MIN").toString());

        // Load all Podfiles
        PodfileParser.ParseResult podParseResult = parsePodfiles(podFiles, podPlatform, defaultMinVersion);
        MainPodfile mainPodfile = mainPodfileFromParseResult(podParseResult, cocoapodsBuildState.getWorkingDir());

        // Create main Podfile contents
        HashMap<String, Object> envContext = new HashMap<>();
        envContext.put("PLATFORM", mainPodfile.platform);
        envContext.put("PLATFORM_VERSION", mainPodfile.platformMinVersion);
        envContext.put("PODS", mainPodfile.podDefinitions);
        envContext.put("USE_FRAMEWORKS", mainPodfile.useFrameworks);
        String mainPodfileContents = templateExecutor.execute(podfileTemplateContents, envContext);
        LOGGER.info("Created main Podfile:\n{}", mainPodfileContents);

        Files.write(mainPodfile.file.toPath(), mainPodfileContents.getBytes());

        return mainPodfile;
    }

    private void unpackXCFrameworks(CocoaPodsServiceBuildState cocoapodsBuildState, List<PodBuildSpec> pods) throws IOException, ExtenderException {
        LOGGER.info("Unpack xcframeworks");

        Set<String> handledPods = new HashSet<>();
        for (PodBuildSpec spec : pods) {
            String podName = spec.name;
            if (handledPods.contains(podName)) {
                continue;
            }
            handledPods.add(podName);
            File unpackScript = Path.of(cocoapodsBuildState.getTargetSupportFilesDir().toString(), podName, String.format("%s-xcframeworks.sh", podName)).toFile();
            if (unpackScript.exists()) {
                String log = ProcessUtils.execCommand(List.of(
                    unpackScript.getAbsolutePath()
                ), null, spec.parsedXCConfig);
                LOGGER.info("Unpacked xcframeworks for {}:\n{}", podName, log);
                String failure = findUnpackFailure(log);
                if (failure != null) {
                    throw new ExtenderException(String.format(
                        "Unable to unpack xcframework for pod '%s' (ARCHS=%s, PLATFORM_NAME=%s): %s",
                        podName,
                        spec.parsedXCConfig.get("ARCHS"),
                        spec.parsedXCConfig.get("PLATFORM_NAME"),
                        failure));
                }
            } else if (hasVendoredXCFramework(spec)) {
                // the pod ships an .xcframework but Cocoapods generated no script to unpack it,
                // so the framework search paths in the xcconfig will point at an empty directory
                LOGGER.warn("Pod {} has vendored xcframeworks {} but no unpack script {}", podName, spec.vendoredFrameworks, unpackScript);
            } else {
                LOGGER.debug("No xcframework unpack script for {}", podName);
            }
        }
    }

    static boolean hasVendoredXCFramework(PodBuildSpec spec) {
        return spec.vendoredFrameworks.stream().anyMatch(f -> f.contains(".xcframework"));
    }

    /**
     * Scan the output of a Cocoapods generated '<pod>-xcframeworks.sh' script for a failure to
     * select a slice. The script prints a warning and exits with code 0 in that case, so the
     * process exit code alone does not tell us that nothing was unpacked.
     * @param scriptOutput Combined stdout/stderr of the unpack script
     * @return The offending line, or null if the output contains no such warning
     */
    static String findUnpackFailure(String scriptOutput) {
        if (scriptOutput == null) {
            return null;
        }
        for (String line : scriptOutput.split("\\R")) {
            // "warning: [CP] Unable to find matching .xcframework slice in '...' for the current build architectures (...)."
            if (line.contains("[CP]") && line.contains("Unable to find matching")) {
                return line.trim();
            }
        }
        return null;
    }

    /**
     * Verify that every include/framework search path pointing into the XCFrameworkIntermediates
     * directory was actually populated by unpackXCFrameworks(). Cocoapods writes those paths into
     * the generated xcconfig whether or not the .xcframework was ever unpacked, so without this
     * check a skipped or failed unpack only surfaces later as a confusing compiler error such as
     * "'SomeHeader.h' file not found".
     * @param unpackedFrameworksDir The XCFrameworkIntermediates directory for this build
     * @param pods The resolved pod build specs
     */
    static void validateUnpackedFrameworks(File unpackedFrameworksDir, List<PodBuildSpec> pods) throws ExtenderException {
        Path unpackedRoot = unpackedFrameworksDir.toPath().toAbsolutePath().normalize();
        List<String> errors = new ArrayList<>();
        for (PodBuildSpec spec : pods) {
            Set<File> searchPaths = new LinkedHashSet<>(spec.includePaths);
            if (spec.frameworkSearchPaths != null) {
                searchPaths.addAll(spec.frameworkSearchPaths);
            }
            for (File searchPath : searchPaths) {
                Path path = Path.of(unescapeWhitespace(searchPath.toString())).toAbsolutePath().normalize();
                if (!path.startsWith(unpackedRoot) || path.equals(unpackedRoot)) {
                    continue;
                }
                String[] entries = path.toFile().list();
                if (entries == null || entries.length == 0) {
                    errors.add(String.format("pod '%s' expects unpacked xcframework content in '%s' but that directory is %s",
                        spec.name, path, entries == null ? "missing" : "empty"));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ExtenderException("Cocoapods xcframework unpacking did not produce the expected output:\n  "
                + String.join("\n  ", errors));
        }
    }

    // search paths parsed from an xcconfig keep whitespace escaped (see CommandLineTokenizer.escapeWhitespace)
    static String unescapeWhitespace(String path) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < path.length(); ++i) {
            char c = path.charAt(i);
            if (c == '\\' && i + 1 < path.length() && Character.isWhitespace(path.charAt(i + 1))) {
                continue;
            }
            result.append(c);
        }
        return result.toString();
    }

    void generateSwiftCompatabilityModule(List<PodBuildSpec> pods) {
        for (PodBuildSpec spec : pods) {
            if (spec.swiftSourceFiles.isEmpty()) {
                continue;
            }

            LOGGER.debug("Generate Swift compatability header and modulemap for {}", spec.moduleName);

            // generate swift modulemap content
            HashMap<String, Object> context = new HashMap<>();
            context.put("MODULE_ID", spec.moduleName);
            context.put("HEADER", spec.swiftModuleHeader.toString());
            spec.swiftModuleDefinition = templateExecutor.execute(modulemapTemplateContents, context);
        }
    }

    private Set<String> getPodDeps(Map<String, List<String>> specDepsMap, List<String> specNames) throws ExtenderException {
        if (specNames == null) {
            return Set.of();
        }
        Set<String> sortedPodSpecs = new LinkedHashSet<>();
        for (String specName : specNames) {
            sortedPodSpecs.addAll(getPodDeps(specDepsMap, specDepsMap.getOrDefault(specName, null)));
            // String podName = PodUtils.getPodName(specName);
            // sortedPodSpecs.add(podName);
            sortedPodSpecs.add(specName);
        }
        return sortedPodSpecs;
    }

    /**
     * Install pods from a podfile and create PodSpec instances for each installed pod.
     * @param cocoapodsBuildState Cocoapod's service build state
     * @return An InstalledPods object with installed pods
     */
    private InstalledPods installPods(CocoaPodsServiceBuildState cocoapodsBuildState) throws IOException, ExtenderException {
        // hold the shared lock for the whole installation so that the cache dir cannot be rotated,
        // updated or deleted underneath us between the 'pod install' and the 'pod spec cat' calls
        cacheLock.readLock().lock();
        try {
            return installPodsLocked(cocoapodsBuildState);
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    private InstalledPods installPodsLocked(CocoaPodsServiceBuildState cocoapodsBuildState) throws IOException, ExtenderException {
        LOGGER.info("Installing pods");
        Path cacheDir;
        // store current cache dir into local variable to use the same value for all 'pod' runs
        synchronized(syncLock) {
            cacheDir = currentCacheDir;
        }
        InstalledPods installedPods = new InstalledPods();

        File workingDir = cocoapodsBuildState.getWorkingDir();
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

        installedPods.podfileLock = new File(workingDir, "Podfile.lock");
        if (!installedPods.podfileLock.exists()) {
            throw new ExtenderException("Unable to find Podfile.lock in directory " + dir);
        }

        String podfileLockContent = FileUtils.readFileToString(installedPods.podfileLock, Charset.defaultCharset());
        LOGGER.info("Podfile.lock:\n{}", podfileLockContent);

        File podsDir = new File(workingDir, "Pods");
        // iterate over Pods folder and obtain names of all installed pods
        File[] podsNames = podsDir.listFiles(new FileFilter() {
            @Override
            public boolean accept(File f) {
                String filename = f.getName();
                return f.isDirectory() 
                    && !filename.equals("Headers")
                    && !filename.equals("Target Support Files")
                    && !filename.equals("Local Podspecs")
                    && !filename.endsWith(".xcodeproj");
            }
        });

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
        Map<String, String> podVersions = new HashMap<>();
        Map<String, List<String>> podsDependencies = new HashMap<>();
        Yaml podfileLockYaml = new Yaml();
        Map<String, Object> parsedLockfile = podfileLockYaml.load(podfileLockContent);
        List<Object> podsList = (List<Object>)parsedLockfile.get("PODS");
        for (Object podRecord : podsList) {
            // record can be simple String (if pod has no dependecies) or Map (if Pod has dependencies)
            if (podRecord instanceof String) {
                String castedRecord = (String) podRecord;
                // '  - "GoogleUtilities/Environment (7.10.0)":'   ->   'GoogleUtilities/Environment (7.10.0)'
                // String podname = line.trim().replace("- ", "").replace(":", "").replace("\"","");
                // 'GoogleUtilities/Environment (7.10.0)'  -> 'GoogleUtilities/Environment' -> ['GoogleUtilities', 'Environment']
                // String podnameparts[] = PodUtils.splitPodname(castedRecord);
                // 'GoogleUtilities'
                String mainpodname = PodUtils.getPodName(castedRecord);
                // 'GoogleUtilities/Environment (7.10.0)'  -> '7.10.0'
                String version = PodUtils.getSpecVersion(castedRecord);
                String specName = PodUtils.getSpecName(castedRecord);
                podVersions.put(mainpodname, version);
                podsDependencies.put(specName, null);
            } else if (podRecord instanceof Map) {
                // Podfile has one level depth
                Map<String, List<String>> castedRecord = (Map<String, List<String>>)podRecord;
                for (Map.Entry<String, List<String>> kv : castedRecord.entrySet()) {
                    List<String> deps = new ArrayList<>();
                    for (String dep : kv.getValue()) {
                        String specName = PodUtils.getSpecName(dep);
                        deps.add(specName);
                    }
                    String record = kv.getKey();
                    String specName = PodUtils.getSpecName(record);
                    String podName = PodUtils.getPodName(record);
                    String version = PodUtils.getSpecVersion(record);
                    podVersions.put(podName, version);
                    podsDependencies.put(specName, deps);
                }
            }
        }

        for (Map.Entry<String, List<String>> entry : podsDependencies.entrySet()) {
            installedPods.pods.addAll(getPodDeps(podsDependencies, entry.getValue()));
            installedPods.pods.add(entry.getKey());
        }

        // Podfile.lock lists what was resolved, the Pods directory holds what actually landed on
        // disk. The loop below only walks disk -> lock, so check the other direction here as well:
        // a pod that resolved but was not materialised is otherwise completely invisible.
        Set<String> podDirNames = new HashSet<>();
        for (File podDir : podsNames) {
            podDirNames.add(podDir.getName());
        }
        for (String podName : podVersions.keySet()) {
            if (!podDirNames.contains(podName)) {
                LOGGER.warn("Pod {} is listed in Podfile.lock but has no directory in {}", podName, podsDir);
            }
        }

        for (File podDir : podsNames) {
            String podName = podDir.getName();
            if (podVersions.containsKey(podName)) {
                String cmd = String.format("pod spec cat --regex ^%s$ --version=%s", podName, podVersions.get(podName));
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
                JSONObject spec = PodSpecParser.parseJson(specJson);
                installedPods.podsMap.put(podName, PodSpecParser.createPodSpec(spec, cocoapodsBuildState.getSelectedPlatform(), null));
            } else {
                LOGGER.warn("No version information for pod {}", podName);
            }
        }

        LOGGER.info("Installed pods");
        for (String entry : installedPods.pods) {
            LOGGER.info("  " + entry);
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
     * @param buildState Extender's build state
     * @return ResolvedPods instance with list of pods, install directory etc
     */
    public ResolvedPods resolveDependencies(PlatformConfig config, ExtenderBuildState buildState) throws IOException, ExtenderException {
        String platform = buildState.getBuildPlatform();
        if (!ExtenderUtil.isAppleTarget(platform)) {
            throw new ExtenderException("Unsupported platform " + platform);
        }

        Map<String, Object> jobEnvContext = createJobEnvContext(config.context);
        File jobDir = buildState.getJobDir();

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

        CocoaPodsServiceBuildState cocoapodsBuildState = new CocoaPodsServiceBuildState(buildState);
        MainPodfile mainPodfile = createMainPodfile(buildState, cocoapodsBuildState, platformPodfiles, jobEnvContext);
        InstalledPods installedPods = installPods(cocoapodsBuildState);


        XCConfigParser parser = new XCConfigParser(buildState, cocoapodsBuildState);
        CreateBuildSpecArgs args = new CreateBuildSpecArgs.Builder()
            .setJobContext(jobEnvContext)
            .setConfigParser(parser)
            .setExtenderBuildState(buildState)
            .setCocoapodsBuildState(cocoapodsBuildState)
            .build();
        Map<String, PodBuildSpec> tmpRegistry = new HashMap<>();
        List<PodBuildSpec> pods = new ArrayList<>();
        for (String specName : installedPods.pods) {
            String podName = PodUtils.getPodName(specName);
            PodSpec podSpec = installedPods.podsMap.get(podName);
            String podnameparts[] = PodUtils.splitPodname(specName);
            if (podnameparts.length > 1) {
                for (int i = 1; i < podnameparts.length; i++) {
                    String subspecname = podnameparts[i];
                    PodSpec subspec = podSpec.getSubspec(subspecname);
                    if (subspec == null) {
                        throw new ExtenderException(String.format("Unable to find subspec '%s' in pod '%s'", subspecname, podName));
                    }
                    podSpec = subspec;
                }
            }

            PodBuildSpec buildSpec = null;
            if (!tmpRegistry.containsKey(podName)) {
                PodSpec mainSpec = podSpec;
                while (mainSpec.parentSpec != null) {
                    mainSpec = mainSpec.parentSpec;
                }
                buildSpec = new PodBuildSpec(args, mainSpec);
                tmpRegistry.put(podName, buildSpec);
                pods.add(buildSpec);
            } else {
                buildSpec = tmpRegistry.get(podName);
            }
            if (podSpec.parentSpec != null) {
                buildSpec.addSubSpec(podSpec);
            }

            for (String subSpecName : podSpec.dependencies) {
                String depPodName = PodUtils.getPodName(subSpecName);
                PodBuildSpec depBuildSpec = tmpRegistry.get(depPodName);
                assert(depBuildSpec != null);
                buildSpec.dependantSpecs.add(depBuildSpec);
            }
        }
        unpackXCFrameworks(cocoapodsBuildState, pods);
        validateUnpackedFrameworks(cocoapodsBuildState.getUnpackedFrameworksDir(), pods);
        generateSwiftCompatabilityModule(pods);

        dumpDir(jobDir, 0);

        MetricsWriter.metricsTimer(meterRegistry, "extender.service.cocoapods.get", System.currentTimeMillis() - methodStart);

        ResolvedPods resolvedPods = new ResolvedPods(cocoapodsBuildState, pods, installedPods.podfileLock, mainPodfile);
        LOGGER.info("Resolved Cocoapod dependencies");
        LOGGER.info(resolvedPods.toString());

        return resolvedPods;
    }

    static PodfileParser.ParseResult parsePodfiles(List<File> podFiles, String platform, String defaultMinVersion) throws IOException, PodfileParsingException {
        PodfileParser.ParseResult result = new PodfileParser.ParseResult(platform, defaultMinVersion);
        for (File podFile : podFiles) {
            PodfileParser.ParseResult res = PodfileParser.parsePodfile(podFile);
            result.mergeWith(res);
        }
        return result;
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
        cacheLock.writeLock().lock();
        try {
            Path cacheDir;
            synchronized(syncLock) {
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
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    @Scheduled(cron="${extender.cocoapods.cache-dir-rotate-cron}")
    public void rotatePodCacheDirectory() {
        cacheLock.writeLock().lock();
        try {
            rotatePodCacheDirectoryLocked();
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    private void rotatePodCacheDirectoryLocked() {
        LOGGER.info("Rotate pod cache directory");
        Path newCacheDir = generateCacheDirPath();
        Path cacheDir;
        synchronized(this.syncLock) {
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
        synchronized(this.syncLock) {
            this.currentCacheDir = newCacheDir;
            storeCurrentCacheDir(newCacheDir);
        }
        initializeTrunkRepo();
    }

    @Scheduled(cron="${extender.cocoapods.old-cache-clean-cron}")
    private void cleanupOldCacheDirectories() {
        cacheLock.writeLock().lock();
        try {
            cleanupOldCacheDirectoriesLocked();
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    private void cleanupOldCacheDirectoriesLocked() {
        LOGGER.info("Cleanup old cache directories");
        Path cacheDir;
        synchronized(this.syncLock) {
            cacheDir = this.currentCacheDir;
        }
        Path activeCacheDir = cacheDir.toAbsolutePath().normalize();
        File oldDirFile = Path.of(this.homeDirPrefix, CocoaPodsService.OLD_CACHE_DIR_FILE).toFile();
        if (!oldDirFile.exists()) {
            LOGGER.warn("File with old cache paths doesn't exist. Cleanup skipped");
            return;
        }

        List<String> retained = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(oldDirFile))) {
            String strPath = reader.readLine();
            while (strPath != null) {
                String path = strPath.trim();
                if (!path.isEmpty() && !removeOldCacheDirectory(path, activeCacheDir)) {
                    retained.add(path);
                }
                strPath = reader.readLine();
            }
        } catch(IOException io) {
            // leave the file untouched so that nothing is forgotten
            LOGGER.warn("Exception while read old cache paths file", io);
            return;
        }

        if (retained.isEmpty()) {
            oldDirFile.delete();
        } else {
            // keep the directories we failed to remove so the next run retries them, instead of
            // dropping the whole file and leaking them for good
            LOGGER.warn("{} old pod cache directories could not be removed and will be retried", retained.size());
            try (FileWriter writer = new FileWriter(oldDirFile, false)) {
                for (String path : retained) {
                    writer.append(path);
                    writer.append("\n");
                }
            } catch(IOException exc) {
                LOGGER.warn("Error while rewriting old cache paths file", exc);
            }
        }
    }

    /**
     * Remove a single old pod cache directory.
     * @param strPath Path of the directory to remove
     * @param activeCacheDir The cache directory currently in use, which must never be removed
     * @return true if the directory is gone or should not be retried, false if removal failed
     */
    private boolean removeOldCacheDirectory(String strPath, Path activeCacheDir) {
        Path dirPath;
        try {
            dirPath = Path.of(strPath);
        } catch(InvalidPathException exc) {
            LOGGER.warn("Ignoring malformed old pod cache path {}", strPath, exc);
            return true;
        }
        // never delete the directory builds are currently using. It gets appended to the file
        // again by the next rotation, so it is safe to drop it from the list here.
        if (dirPath.toAbsolutePath().normalize().equals(activeCacheDir)) {
            LOGGER.warn("Skip removal of pod cache directory {}, it is the current one", strPath);
            return true;
        }
        File path = dirPath.toFile();
        if (!path.exists()) {
            return true;
        }
        LOGGER.info("Remove old pod cache directory: {}", strPath);
        try {
            FileUtils.deleteDirectory(path);
            return true;
        } catch(IOException exc) {
            // a single failure must not abort the cleanup of the remaining directories
            LOGGER.warn("Unable to remove old pod cache directory {}", strPath, exc);
            return false;
        }
    }

    @Scheduled(initialDelay=3600000, fixedDelayString="${extender.cocoapods.repo-update-interval:3600000}")
    public void updateSpecRepo() {
        // exclusive: 'pod repo update' rewrites the spec repo that concurrent installs read from
        cacheLock.writeLock().lock();
        try {
            LOGGER.info("Run pod spec update");
            Path cacheDir;
            synchronized(this.syncLock) {
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
        } finally {
            cacheLock.writeLock().unlock();
        }
    }
}
