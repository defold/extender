package com.defold.extender.services.spm;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.defold.extender.ExtenderBuildState;
import com.defold.extender.ExtenderException;
import com.defold.extender.ExtenderUtil;
import com.defold.extender.PlatformConfig;
import com.defold.extender.TemplateExecutor;
import com.defold.extender.metrics.MetricsWriter;
import com.defold.extender.process.ProcessExecutor;
import com.defold.extender.services.spm.SpmBuildOutputParser.LinkInfo;
import com.defold.extender.services.spm.SpmManifestParser.PackageRef;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Resolves Swift Package Manager dependencies declared in SwiftPackages.json manifests:
 * a generated aggregator Package.swift plus an XcodeGen wrapper project are built with
 * xcodebuild, and the products directory and the wrapper's final link line are harvested
 * into a {@link ResolvedPackages}.
 *
 * Xcode is selected per job via the child processes' DEVELOPER_DIR (never xcode-select —
 * concurrent jobs may need different Xcode versions).
 */
@Service
@ConditionalOnProperty(prefix = "extender", name = "spm.enabled", havingValue = "true")
public class SwiftPackageManagerService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SwiftPackageManagerService.class);

    static final String MANIFEST_FILENAME = "SwiftPackages.json";
    static final String LOCK_FILENAME = "Package.resolved";
    private static final String CURRENT_CACHE_DIR_FILE = "current_spm_cache.txt";
    private static final String OLD_CACHE_DIR_FILE = "old_spm_caches.txt";
    private static final String DEFAULT_CACHE_SUBDIR = "default";
    private static final Pattern UNSAFE_CACHE_SUBDIR_CHARS = Pattern.compile("[^A-Za-z0-9._-]");
    private static final int MAX_CACHE_SUBDIR_LENGTH = 64;
    private static final String FALLBACK_IOS_MIN_VERSION = "12.0";
    private static final String FALLBACK_MACOS_MIN_VERSION = "10.15";

    private final Object syncLock = new Object();
    // builds take the read lock (SwiftPM locks concurrent resolutions inside the cache
    // itself), rotation and cleanup take the write lock
    private final ReentrantReadWriteLock cacheLock = new ReentrantReadWriteLock(true);
    private Path currentCacheDir = Path.of("");

    private final String packageSwiftTemplateContents;
    private final String projectYmlTemplateContents;
    private final TemplateExecutor templateExecutor = new TemplateExecutor();
    private final MeterRegistry meterRegistry;
    private final SpmServiceConfiguration spmConfiguration;

    // package-private so service-level tests can construct the service directly
    @Value("${extender.spm.home-dir-prefix}") String homeDirPrefix;
    @Value("${extender.spm.default-developer-dir}") String defaultDeveloperDir;
    @Value("${extender.spm.xcodegen-path:xcodegen}") String xcodegenPath;
    @Value("${extender.spm.wrapper-mach-o-type:staticlib}") String wrapperMachOType;
    @Value("${extender.spm.swift-version:6.0}") String swiftVersion;

    SwiftPackageManagerService(@Value("classpath:template.package-swift") Resource packageSwiftTemplate,
            @Value("classpath:template.project-yml") Resource projectYmlTemplate,
            SpmServiceConfiguration spmConfiguration,
            MeterRegistry meterRegistry) throws IOException {
        this.meterRegistry = meterRegistry;
        this.spmConfiguration = spmConfiguration;
        this.packageSwiftTemplateContents = ExtenderUtil.readContentFromResource(packageSwiftTemplate);
        this.projectYmlTemplateContents = ExtenderUtil.readContentFromResource(projectYmlTemplate);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void runAfterStartup() {
        ensureCacheDirInitialized();
        cleanupOldCacheDirectories();

        if (!new File(defaultDeveloperDir).isDirectory()) {
            LOGGER.warn("SPM default developer dir does not exist: {}", defaultDeveloperDir);
        }
        LOGGER.info("SPM startup task completed");
    }

    /**
     * A build can reach the service before the ApplicationReadyEvent listener has run
     * (listeners execute sequentially and earlier ones take seconds), so the cache dir
     * must never be read from the raw field.
     */
    private Path ensureCacheDirInitialized() {
        synchronized (this.syncLock) {
            if (!this.currentCacheDir.toString().isEmpty()) {
                return this.currentCacheDir;
            }
            Path storedCacheDir = readCurrentCacheDir();
            if (storedCacheDir != null && storedCacheDir.startsWith(this.homeDirPrefix)) {
                this.currentCacheDir = storedCacheDir;
            } else {
                LOGGER.info("SPM has no current cache dir or prefix is changed. Created...");
                Path newCacheDir = generateCacheDirPath();
                try {
                    Files.createDirectories(newCacheDir);
                } catch (IOException | UnsupportedOperationException | SecurityException exc) {
                    LOGGER.warn("Cannot create SPM cache directory {}", newCacheDir, exc);
                }
                this.currentCacheDir = newCacheDir;
                storeCurrentCacheDir(newCacheDir);
            }
            return this.currentCacheDir;
        }
    }

    private Map<String, Object> createJobEnvContext(Map<String, Object> env) {
        Map<String, Object> context = new HashMap<>(env);
        context.putIfAbsent("env.IOS_VERSION_MIN", System.getenv("IOS_VERSION_MIN"));
        context.putIfAbsent("env.MACOS_VERSION_MIN", System.getenv("MACOS_VERSION_MIN"));
        context.putIfAbsent("env.XCODE_VERSION", System.getenv("XCODE_VERSION"));
        return context;
    }

    /** Returns null when the upload declares no Swift package manifests. */
    public ResolvedPackages resolveDependencies(PlatformConfig config, ExtenderBuildState buildState) throws IOException, ExtenderException {
        String platform = buildState.getBuildPlatform();
        if (!ExtenderUtil.isAppleTarget(platform)) {
            throw new ExtenderException("Unsupported platform " + platform);
        }

        Map<String, Object> jobEnvContext = createJobEnvContext(config.context);
        File jobDir = buildState.getJobDir();

        List<File> allManifests = ExtenderUtil.listFilesMatchingRecursive(jobDir, MANIFEST_FILENAME);
        List<File> platformManifests = new ArrayList<>();
        for (File manifest : allManifests) {
            String parentFolder = manifest.getParentFile().getName();
            if ((platform.contains("ios") && parentFolder.contains("ios")) ||
                (platform.contains("osx") && parentFolder.contains("osx"))) {
                platformManifests.add(manifest);
            }
            else {
                LOGGER.warn("Unexpected {} found in {}", MANIFEST_FILENAME, manifest);
            }
        }
        if (platformManifests.isEmpty()) {
            LOGGER.info("Project has no Swift package dependencies");
            return null;
        }

        SpmServiceBuildState spmBuildState = new SpmServiceBuildState(buildState);
        return resolveDependencies(platformManifests, spmBuildState, jobEnvContext, ExtenderUtil.isIOSTarget(platform));
    }

    ResolvedPackages resolveDependencies(List<File> platformManifests, SpmServiceBuildState spmBuildState,
            Map<String, Object> jobEnvContext, boolean isIOS) throws IOException, ExtenderException {
        long methodStart = System.currentTimeMillis();
        LOGGER.info("Resolving Swift package dependencies");

        String platformFamily = isIOS ? "ios" : "osx";
        String defaultMinVersion = defaultMinVersion(jobEnvContext, isIOS);
        SpmManifestParser.ParseResult manifest = SpmManifestParser.parseManifests(platformManifests, platformFamily, defaultMinVersion);

        generateProjectFiles(spmBuildState, manifest, isIOS);

        String xcodeVersion = resolveXcodeVersion(jobEnvContext);
        String developerDir = resolveDeveloperDir(xcodeVersion);
        LOGGER.info("Building Swift packages with DEVELOPER_DIR={} (XCODE_VERSION={})", developerDir, xcodeVersion);
        Map<String, String> processEnv = hardenedProcessEnv(developerDir);

        cacheLock.readLock().lock();
        try {
            // checkouts must be per job: SwiftPM prunes -clonedSourcePackagesDirPath to the
            // current graph, so sharing it between jobs with different dependencies thrashes.
            // The shared warmth is -packageCachePath (repo mirrors + binary artifacts).
            File packageCacheDir = new File(sharedCacheDirFor(xcodeVersion), "packageCache");
            packageCacheDir.mkdirs();
            File clonedSourcesDir = spmBuildState.getClonedSourcePackagesDir();

            generateXcodeProject(spmBuildState, processEnv);
            copyUserLockFile(platformManifests, spmBuildState);
            buildWrapperProject(spmBuildState, clonedSourcesDir, packageCacheDir, processEnv);
        } finally {
            cacheLock.readLock().unlock();
        }

        LinkInfo linkInfo = parseLinkInfo(spmBuildState);
        File swiftRuntimeLibDir = new File(developerDir, "Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/"
            + spmBuildState.getSelectedPlatform().toString().toLowerCase());
        ResolvedPackages resolvedPackages = ResolvedPackages.harvest(spmBuildState, manifest.minVersion, linkInfo, swiftRuntimeLibDir);

        MetricsWriter.metricsTimer(meterRegistry, "extender.service.spm.get", System.currentTimeMillis() - methodStart);
        LOGGER.info("Resolved Swift package dependencies");
        LOGGER.info(resolvedPackages.toString());

        return resolvedPackages;
    }

    private String defaultMinVersion(Map<String, Object> jobEnvContext, boolean isIOS) {
        Object minVersion = jobEnvContext.get(isIOS ? "env.IOS_VERSION_MIN" : "env.MACOS_VERSION_MIN");
        if (minVersion != null) {
            return minVersion.toString();
        }
        String fallback = isIOS ? FALLBACK_IOS_MIN_VERSION : FALLBACK_MACOS_MIN_VERSION;
        LOGGER.warn("No platform min version in the job environment, using {}", fallback);
        return fallback;
    }

    private String resolveXcodeVersion(Map<String, Object> jobEnvContext) {
        Object version = jobEnvContext.get("env.XCODE_VERSION");
        return version != null ? version.toString() : null;
    }

    // resolved per build: one instance serves builds pinned to different Xcode versions
    private String resolveDeveloperDir(String xcodeVersion) {
        if (xcodeVersion != null) {
            String developerDir = spmConfiguration.getXcodeDeveloperDirs().get(xcodeVersion);
            if (developerDir != null) {
                return developerDir;
            }
            if (!spmConfiguration.getXcodeDeveloperDirs().isEmpty()) {
                LOGGER.warn("No Xcode developer dir configured for XCODE_VERSION={}, using default", xcodeVersion);
            }
        }
        return defaultDeveloperDir;
    }

    private Map<String, String> hardenedProcessEnv(String developerDir) {
        Map<String, String> env = new HashMap<>();
        env.put("DEVELOPER_DIR", developerDir);
        // strips every git credential helper so public packages clone anonymously with no
        // keychain access; only effective together with xcodebuild -scmProvider system
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_ASKPASS", "/usr/bin/true");
        return env;
    }

    void generateProjectFiles(SpmServiceBuildState buildState, SpmManifestParser.ParseResult manifest, boolean isIOS) throws IOException {
        Files.writeString(new File(buildState.getPackageDir(), "Package.swift").toPath(), generatePackageSwift(manifest, isIOS));
        Files.writeString(new File(buildState.getPackageDir(), "Sources/" + SpmServiceBuildState.AGGREGATOR_NAME + "/Empty.swift").toPath(),
            "// Intentionally empty. The aggregator target only re-exports the requested package products.\n");
        Files.writeString(new File(buildState.getWrapperDir(), "project.yml").toPath(), generateProjectYml(manifest, isIOS));
        Files.writeString(new File(buildState.getWrapperDir(), "Sources/Dummy.swift").toPath(),
            "// Dummy source so the wrapper framework target is well-formed.\n"
            + "public enum SpmWrapperMarker {}\n");
    }

    String generatePackageSwift(SpmManifestParser.ParseResult manifest, boolean isIOS) {
        List<Map<String, String>> packages = new ArrayList<>();
        List<Map<String, String>> productDeps = new ArrayList<>();
        for (PackageRef ref : manifest.packages.values()) {
            packages.add(Map.of("URL", ref.url, "REQUIREMENT", ref.requirement.toSwiftArgument()));
            for (String product : ref.products) {
                productDeps.add(Map.of("PRODUCT", product, "PACKAGE_LABEL", ref.label()));
            }
        }
        Map<String, Object> context = new HashMap<>();
        context.put("AGGREGATOR_NAME", SpmServiceBuildState.AGGREGATOR_NAME);
        context.put("SPM_PLATFORM", isIOS ? "iOS" : "macOS");
        context.put("PLATFORM_MIN_VERSION", manifest.minVersion);
        context.put("PACKAGES", packages);
        context.put("PRODUCT_DEPS", productDeps);
        return templateExecutor.execute(packageSwiftTemplateContents, context);
    }

    String generateProjectYml(SpmManifestParser.ParseResult manifest, boolean isIOS) {
        Map<String, Object> context = new HashMap<>();
        context.put("WRAPPER_NAME", SpmServiceBuildState.WRAPPER_NAME);
        context.put("AGGREGATOR_NAME", SpmServiceBuildState.AGGREGATOR_NAME);
        context.put("XCODEGEN_PLATFORM", isIOS ? "iOS" : "macOS");
        context.put("PLATFORM_MIN_VERSION", manifest.minVersion);
        context.put("SWIFT_VERSION", swiftVersion);
        context.put("MACH_O_TYPE", machOTypeFor(manifest));
        return templateExecutor.execute(projectYmlTemplateContents, context);
    }

    String machOTypeFor(SpmManifestParser.ParseResult manifest) {
        if ("static".equals(manifest.wrapperType)) {
            return "staticlib";
        }
        if ("dynamic".equals(manifest.wrapperType)) {
            return "mh_dylib";
        }
        return wrapperMachOType;
    }

    private void generateXcodeProject(SpmServiceBuildState buildState, Map<String, String> processEnv) throws ExtenderException {
        ProcessExecutor processExecutor = new ProcessExecutor();
        processExecutor.setCwd(buildState.getWrapperDir());
        processExecutor.putEnv(processEnv);
        try {
            processExecutor.execute(List.of(xcodegenPath, "generate", "--spec", "project.yml"));
        } catch (IOException | InterruptedException e) {
            throw new ExtenderException(e, "xcodegen generate failed:\n" + processExecutor.getOutput());
        }
    }

    // an uploaded Package.resolved pins the graph; Xcode only reads it from inside the
    // generated project's workspace
    private void copyUserLockFile(List<File> platformManifests, SpmServiceBuildState buildState) throws IOException {
        List<File> lockFiles = new ArrayList<>();
        for (File manifest : platformManifests) {
            File candidate = new File(manifest.getParentFile(), LOCK_FILENAME);
            if (candidate.isFile()) {
                lockFiles.add(candidate);
            }
        }
        if (lockFiles.isEmpty()) {
            return;
        }
        // the directory-walk order is filesystem-dependent; the picked lock file must be
        // the same on every build of the same upload
        lockFiles.sort(Comparator.comparing(File::getAbsolutePath));
        File userLockFile = lockFiles.get(0);
        if (lockFiles.size() > 1) {
            LOGGER.warn("Multiple {} files uploaded {}, using {}", LOCK_FILENAME, lockFiles, userLockFile);
        }

        File target = buildState.getLockFile();
        target.getParentFile().mkdirs();
        FileUtils.copyFile(userLockFile, target);
        LOGGER.info("Using uploaded {} to pin Swift package versions", LOCK_FILENAME);
    }

    private void buildWrapperProject(SpmServiceBuildState buildState, File clonedSourcesDir, File packageCacheDir,
            Map<String, String> processEnv) throws IOException, ExtenderException {
        List<String> args = new ArrayList<>(List.of(
            "xcodebuild",
            "-project", buildState.getXcodeProjDir().getAbsolutePath(),
            "-scheme", SpmServiceBuildState.WRAPPER_NAME,
            "-destination", buildState.getDestination(),
            "-configuration", SpmServiceBuildState.BUILD_CONFIGURATION,
            "-scmProvider", "system",
            "-derivedDataPath", buildState.getDerivedDataDir().getAbsolutePath(),
            "-clonedSourcePackagesDirPath", clonedSourcesDir.getAbsolutePath(),
            "-packageCachePath", packageCacheDir.getAbsolutePath(),
            "-skipPackagePluginValidation",
            "CLANG_MODULE_CACHE_PATH=" + buildState.getModuleCacheDir().getAbsolutePath(),
            "CODE_SIGNING_ALLOWED=NO"));
        args.addAll(buildState.getExtraBuildSettings());
        args.add("build");

        ProcessExecutor processExecutor = new ProcessExecutor();
        processExecutor.setCwd(buildState.getWrapperDir());
        processExecutor.putEnv(processEnv);
        try {
            processExecutor.execute(args);
        } catch (IOException | InterruptedException e) {
            writeBuildLog(buildState, processExecutor);
            throw new ExtenderException(e, "Swift package build failed:\n" + processExecutor.getOutput());
        }
        writeBuildLog(buildState, processExecutor);
    }

    private void writeBuildLog(SpmServiceBuildState buildState, ProcessExecutor processExecutor) {
        try {
            processExecutor.writeLog(buildState.getBuildLogFile());
        } catch (IOException e) {
            LOGGER.warn("Failed to write SPM build log", e);
        }
    }

    private LinkInfo parseLinkInfo(SpmServiceBuildState buildState) throws IOException {
        String linkLine = SpmBuildOutputParser.findWrapperLinkLine(buildState.getBuildLogFile(), SpmServiceBuildState.WRAPPER_NAME);
        if (linkLine == null) {
            LOGGER.warn("No wrapper link line found in the SPM build log; system link dependencies are not harvested");
            return null;
        }
        // everything on a libtool -static line is an input merged INTO the wrapper archive,
        // not an external link dependency — harvesting it would double-link or dangle
        if (linkLine.contains("libtool") && linkLine.contains("-static")) {
            return null;
        }
        // xcodebuild runs the link step from the wrapper dir; @<path> response files on the
        // line resolve against it
        return SpmBuildOutputParser.parseLinkLine(linkLine, buildState.getDerivedDataDir().getAbsolutePath(),
            buildState.getWrapperDir());
    }

    // Shared cache layout: <home-dir-prefix>/<uuid>/<xcode-version>/packageCache, keyed by
    // Xcode version because SwiftPM state is not guaranteed compatible across Swift versions.

    private File sharedCacheDirFor(String xcodeVersion) {
        return new File(ensureCacheDirInitialized().toFile(), cacheSubDirFor(xcodeVersion));
    }

    // XCODE_VERSION comes from the job's build.yml: a value carrying a separator would place
    // the cache outside home-dir-prefix, where cleanup never reclaims it
    static String cacheSubDirFor(String xcodeVersion) {
        if (xcodeVersion == null) {
            return DEFAULT_CACHE_SUBDIR;
        }
        String sanitized = UNSAFE_CACHE_SUBDIR_CHARS.matcher(xcodeVersion).replaceAll("_");
        if (sanitized.length() > MAX_CACHE_SUBDIR_LENGTH) {
            sanitized = sanitized.substring(0, MAX_CACHE_SUBDIR_LENGTH);
        }
        // "." and ".." resolve to the cache root and its parent
        boolean allDots = !sanitized.isEmpty() && sanitized.chars().allMatch(c -> c == '.');
        if (sanitized.isEmpty() || allDots) {
            LOGGER.warn("XCODE_VERSION '{}' is not usable as a cache directory name, using '{}'",
                xcodeVersion, DEFAULT_CACHE_SUBDIR);
            return DEFAULT_CACHE_SUBDIR;
        }
        return sanitized;
    }

    private Path generateCacheDirPath() {
        return Path.of(this.homeDirPrefix, UUID.randomUUID().toString());
    }

    private Path readCurrentCacheDir() {
        File currentCacheDirFile = Path.of(this.homeDirPrefix, CURRENT_CACHE_DIR_FILE).toFile();
        if (!currentCacheDirFile.exists()) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(currentCacheDirFile))) {
            String strPath = reader.readLine();
            if (strPath != null) {
                Path result = Path.of(strPath);
                return result.toFile().exists() ? result : null;
            }
        } catch (IOException io) {
            LOGGER.warn("Exception while read current SPM cache path file", io);
        }
        return null;
    }

    private void storeCurrentCacheDir(Path cacheDir) {
        try {
            Files.createDirectories(Path.of(this.homeDirPrefix));
        } catch (IOException exc) {
            LOGGER.warn("Can't create directories to store SPM cache path", exc);
            return;
        }
        try (FileWriter writer = new FileWriter(new File(this.homeDirPrefix, CURRENT_CACHE_DIR_FILE))) {
            writer.append(cacheDir.toAbsolutePath().toString());
        } catch (IOException exc) {
            LOGGER.warn("Error while writing to current SPM cache path file", exc);
        }
    }

    @Scheduled(cron = "${extender.spm.cache-dir-rotate-cron:0 20 2 * * *}")
    public void rotateCacheDirectory() {
        cacheLock.writeLock().lock();
        try {
            rotateCacheDirectoryLocked();
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    private void rotateCacheDirectoryLocked() {
        LOGGER.info("Rotate SPM cache directory");
        Path newCacheDir = generateCacheDirPath();
        Path cacheDir = ensureCacheDirInitialized();
        try {
            Files.createDirectories(newCacheDir);
        } catch (IOException | UnsupportedOperationException | SecurityException exc) {
            LOGGER.warn("Cannot create new SPM cache directory", exc);
            return;
        }

        try (FileWriter writer = new FileWriter(new File(this.homeDirPrefix, OLD_CACHE_DIR_FILE), true)) {
            writer.append(cacheDir.toAbsolutePath().toString());
            writer.append("\n");
        } catch (IOException exc) {
            LOGGER.warn("Error while writing to old SPM cache paths file", exc);
        }
        synchronized (this.syncLock) {
            this.currentCacheDir = newCacheDir;
            storeCurrentCacheDir(newCacheDir);
        }
    }

    @Scheduled(cron = "${extender.spm.old-cache-clean-cron:0 20 6 * * *}")
    public void cleanupOldCacheDirectories() {
        cacheLock.writeLock().lock();
        try {
            cleanupOldCacheDirectoriesLocked();
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    private void cleanupOldCacheDirectoriesLocked() {
        LOGGER.info("Cleanup old SPM cache directories");
        Path activeCacheDir = ensureCacheDirInitialized().toAbsolutePath().normalize();
        File oldDirFile = Path.of(this.homeDirPrefix, OLD_CACHE_DIR_FILE).toFile();
        if (!oldDirFile.exists()) {
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
        } catch (IOException io) {
            // leave the file untouched so that nothing is forgotten
            LOGGER.warn("Exception while read old SPM cache paths file", io);
            return;
        }

        if (retained.isEmpty()) {
            oldDirFile.delete();
        } else {
            // keep the directories we failed to remove so the next run retries them, instead of
            // dropping the whole file and leaking them for good
            LOGGER.warn("{} old SPM cache directories could not be removed and will be retried", retained.size());
            try (FileWriter writer = new FileWriter(oldDirFile, false)) {
                for (String path : retained) {
                    writer.append(path);
                    writer.append("\n");
                }
            } catch (IOException exc) {
                LOGGER.warn("Error while rewriting old SPM cache paths file", exc);
            }
        }
    }

    /**
     * Remove a single old SPM cache directory.
     * @param strPath Path of the directory to remove
     * @param activeCacheDir The cache directory currently in use, which must never be removed
     * @return true if the directory is gone or should not be retried, false if removal failed
     */
    private boolean removeOldCacheDirectory(String strPath, Path activeCacheDir) {
        Path dirPath;
        try {
            dirPath = Path.of(strPath);
        } catch (InvalidPathException exc) {
            LOGGER.warn("Ignoring malformed old SPM cache path {}", strPath, exc);
            return true;
        }
        // never delete the directory builds are currently using. It gets appended to the file
        // again by the next rotation, so it is safe to drop it from the list here.
        if (dirPath.toAbsolutePath().normalize().equals(activeCacheDir)) {
            LOGGER.warn("Skip removal of SPM cache directory {}, it is the current one", strPath);
            return true;
        }
        File path = dirPath.toFile();
        if (!path.exists()) {
            return true;
        }
        LOGGER.info("Remove old SPM cache directory: {}", strPath);
        try {
            FileUtils.deleteDirectory(path);
            return true;
        } catch (IOException exc) {
            // a single failure must not abort the cleanup of the remaining directories
            LOGGER.warn("Unable to remove old SPM cache directory {}", strPath, exc);
            return false;
        }
    }
}
