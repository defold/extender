package com.defold.extender.services.spm;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
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
import com.defold.extender.process.ProcessSandbox;
import com.defold.extender.process.DarwinSandboxPaths;
import com.defold.extender.process.SandboxPolicy;
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
 *
 * <p>Package.swift manifests are untrusted code that SwiftPM compiles and runs, and its own
 * manifest sandbox cannot be nested inside the process sandbox. The build is therefore split
 * so that no manifest ever runs with network access: every repository the graph needs is
 * mirrored with plain git (network on, no Swift code involved), and xcodebuild then resolves
 * and builds with network denied, its git redirected to the mirrors through
 * {@code url.<mirror>.insteadOf}. Transitive dependencies are not known before the manifests
 * that declare them have run, so the two steps alternate: a round that cannot reach a
 * repository names it in its output, that repository is mirrored, and the round is repeated
 * until the graph closes. An uploaded Package.resolved is used as a seed when present but is
 * not required. Binary targets are handled the same way: SwiftPM takes an archive from the
 * package cache before it tries the network, so the archives a round could not download are
 * fetched by plain curl into that cache and the round is repeated; SwiftPM then verifies the
 * archive against the checksum in the manifest that declares it.
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
    static final String MIRRORS_SUBDIR = "mirrors";
    static final String GIT_CONFIG_FILENAME = "gitconfig";
    // marker next to a mirror, touched after every successful clone or fetch
    static final String FETCH_MARKER_SUFFIX = ".fetched";
    // one round per level of the dependency graph, plus the round that finds it closed and
    // one forced mirror refresh; deeper than this is a runaway, not a package graph
    static final int MAX_RESOLVE_ROUNDS = 12;
    // transitive manifests pick these URLs, so the number of repositories git is asked to
    // clone is attacker-controlled
    static final int MAX_MIRRORED_PACKAGES = 256;
    // xcodebuild's own line above whatever SwiftPM reported, present for every failure of the
    // resolution and for none of the compilation
    static final String RESOLUTION_FAILED_MARKER = "Could not resolve package dependencies";
    // SwiftPM's binary artifact cache inside -packageCachePath: one file per archive URL
    static final String ARTIFACTS_SUBDIR = "artifacts";
    // binary target URLs are chosen by manifests as well
    static final int MAX_BINARY_ARTIFACTS = 64;
    // SwiftPM verifies a cached archive against the manifest's checksum; a mismatch means the
    // publisher replaced the archive behind its URL (or the cache entry was tampered with)
    static final String CHECKSUM_MISMATCH_MARKER = "does not match checksum specified by the manifest";

    private final Object syncLock = new Object();
    // one git or curl process at a time per mirror or archive; concurrent jobs wanting the same
    // one wait. Striped by path hash so uploads naming ever new URLs cannot grow the registry
    private static final int LOCK_STRIPES = 64;
    private final Object[] fetchLocks = new Object[LOCK_STRIPES];
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
    // a mirror fetched more recently than this is used as it is, so parallel builds of the
    // same graph cost one fetch per repository instead of one per build
    @Value("${extender.spm.mirror-refresh-interval:600000}") long mirrorRefreshIntervalMillis;
    // one binary artifact download; SwiftPM has no size limit of its own
    @Value("${extender.spm.artifact-download-timeout:600000}") long artifactDownloadTimeoutMillis;
    @Value("${extender.spm.max-artifact-size:1073741824}") long maxArtifactSizeBytes;

    SwiftPackageManagerService(@Value("classpath:template.package-swift") Resource packageSwiftTemplate,
            @Value("classpath:template.project-yml") Resource projectYmlTemplate,
            SpmServiceConfiguration spmConfiguration,
            MeterRegistry meterRegistry) throws IOException {
        this.meterRegistry = meterRegistry;
        this.spmConfiguration = spmConfiguration;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            fetchLocks[i] = new Object();
        }
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
    // package-private: the offline discovery test seeds the mirror store under this dir
    Path ensureCacheDirInitialized() {
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
        File userLockFile = findUserLockFile(platformManifests);

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
            // bare git mirrors of every pinned repository, written only by the plain git
            // pre-fetch below and read-only for xcodebuild; git content does not depend on
            // the Xcode version, so the store is shared across the per-version cache dirs
            File mirrorsDir = new File(ensureCacheDirInitialized().toFile(), MIRRORS_SUBDIR);
            mirrorsDir.mkdirs();
            File clonedSourcesDir = spmBuildState.getClonedSourcePackagesDir();

            generateXcodeProject(spmBuildState, processEnv);
            resolveAndBuild(spmBuildState, manifest, userLockFile, clonedSourcesDir, packageCacheDir,
                mirrorsDir, processEnv);
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

    // Mach services and preference domains xcodebuild asks for under (deny default), read off
    // `log stream --predicate 'sender == "Sandbox"'`; the keychain services
    // (com.apple.SecurityServer, com.apple.securityd.xpc) are deliberately absent
    static final List<String> XCODEBUILD_MACH_SERVICES = List.of(
            "com.apple.CoreServices.coreservicesd", "com.apple.lsd.mapdb", "com.apple.lsd.modifydb",
            "com.apple.coreservices.quarantine-resolver", "com.apple.FSEvents",
            "com.apple.SystemConfiguration.configd", "com.apple.distributed_notifications@Uv3",
            "com.apple.DiskArbitration.diskarbitrationd", "com.apple.mobileassetd.v2", "com.apple.FileCoordination");
    static final List<String> XCODEBUILD_PREFERENCE_DOMAINS = List.of(
            "com.apple.dt.Xcode", "com.apple.dt.xcodebuild", "kCFPreferencesAnyApplication", "com.apple.coresimulator");

    /**
     * xcodebuild resolves the home through getpwuid, not $HOME, and keeps SwiftPM's manifest
     * and collection databases, its fingerprint store and the module cache used for manifest
     * compilation in per-user locations no flag can move. Those stay shared between jobs (a
     * documented residual, like the Gradle cache on Linux). Everything else the build writes
     * is under the job's SwiftPackageManagerService dir, which is also executable because
     * SwiftPM plugins and macros are built and run from there.
     *
     * No network: Package.swift manifests, plugins and macros run inside this profile, and
     * every repository they need is already mirrored (see {@link #prefetchMirrors}).
     */
    static SandboxPolicy xcodebuildPolicy(SpmServiceBuildState buildState, File packageCacheDir, File mirrorsDir)
            throws IOException {
        Path home = DarwinSandboxPaths.realHome();
        Path cache = DarwinSandboxPaths.userCacheDir();
        List<String> sharedState = new ArrayList<>();
        for (Path p : List.of(packageCacheDir.toPath(), home.resolve("Library/Caches/org.swift.swiftpm"),
                home.resolve(".swiftpm"), home.resolve("Library/org.swift.swiftpm"),
                cache.resolve("clang/ModuleCache"), cache.resolve("com.apple.DeveloperTools"))) {
            Files.createDirectories(p);
            sharedState.add(p.toString());
        }
        return SandboxPolicy.dependencyResolver(sharedState)
                .withNetwork(SandboxPolicy.Network.NONE)
                // platform/simulator/Metal toolchain indexes Xcode keeps per user, and the
                // git mirrors SwiftPM clones from
                .withReadOnlyPaths(List.of(home.resolve("Library/Developer").toString(), mirrorsDir.getAbsolutePath()))
                // SwiftPM compiles Package.swift into an executable under TMPDIR (which the
                // sandbox puts under the wrapper dir, inside this tree) and runs it; plugins and
                // macros are built and run from the working dir as well
                .withReadWriteExecPaths(List.of(buildState.getWorkingDir().getAbsolutePath()))
                // the Xcode build service (swbuild.tmp.*) and the tools it resets TMPDIR for use
                // the darwin user temp dir, and CFNetwork downloads binary targets there; only
                // the entries they create are granted, never the directory (which holds every job)
                .withReadWritePatterns(List.of(DarwinSandboxPaths.userTempPattern(
                        "(TemporaryDirectory\\.|ResultBundle_|com\\.apple\\.dt\\.|CFNetworkDownload_|swbuild\\.tmp\\.)")))
                .withMachServices(XCODEBUILD_MACH_SERVICES)
                .withPreferenceDomains(XCODEBUILD_PREFERENCE_DOMAINS)
                // Xcode's build service maps a sparse file larger than the default 8 GiB
                // RLIMIT_FSIZE; SIGXFSZ kills it silently ("The Xcode build system has crashed")
                .withMaxFileSizeBytes(0);
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

    /** The uploaded Package.resolved next to a platform manifest, or null: it seeds the resolution, it is not required. */
    static File findUserLockFile(List<File> platformManifests) {
        List<File> lockFiles = new ArrayList<>();
        for (File manifest : platformManifests) {
            File candidate = new File(manifest.getParentFile(), LOCK_FILENAME);
            if (candidate.isFile()) {
                lockFiles.add(candidate);
            }
        }
        if (lockFiles.isEmpty()) {
            return null;
        }
        // the directory-walk order is filesystem-dependent; the picked lock file must be
        // the same on every build of the same upload
        lockFiles.sort(Comparator.comparing(File::getAbsolutePath));
        File userLockFile = lockFiles.get(0);
        if (lockFiles.size() > 1) {
            LOGGER.warn("Multiple {} files uploaded {}, using {}", LOCK_FILENAME, lockFiles, userLockFile);
        }
        return userLockFile;
    }

    // Xcode only reads the lock file from inside the generated project's workspace
    private void copyLockFile(File userLockFile, SpmServiceBuildState buildState) throws IOException {
        File target = buildState.getLockFile();
        target.getParentFile().mkdirs();
        FileUtils.copyFile(userLockFile, target);
        LOGGER.info("Seeding the Swift package resolution with the uploaded {}", LOCK_FILENAME);
    }

    /** A repository to mirror: the URL git is given, and every spelling SwiftPM may ask git for. */
    static final class PackageRepo {
        final String key;
        final String fetchUrl;
        final Set<String> spellings = new LinkedHashSet<>();

        PackageRepo(String key, String fetchUrl) {
            this.key = key;
            this.fetchUrl = fetchUrl;
        }
    }

    /**
     * Adds a package URL to the repositories to mirror, returning true when it names one that
     * was not known yet. URLs harvested from a build log are chosen by transitive manifests,
     * so they are validated exactly like the ones declared in SwiftPackages.json.
     */
    static boolean addRepo(Map<String, PackageRepo> repos, String rawUrl) throws ExtenderException {
        String url = SpmManifestParser.sanitizeUrl(rawUrl);
        String key = SpmManifestParser.canonicalKey(url);
        PackageRepo repo = repos.get(key);
        boolean added = repo == null;
        if (added) {
            if (repos.size() >= MAX_MIRRORED_PACKAGES) {
                throw new ExtenderException(String.format(
                    "The Swift package graph pulls in more than %d repositories, which is not supported",
                    MAX_MIRRORED_PACKAGES));
            }
            repo = new PackageRepo(key, url);
            repos.put(key, repo);
        }
        addSpellings(repo.spellings, url);
        return added;
    }

    // xcodebuild names every repository it fetches, updates or fails to clone. Only those
    // three shapes are harvested: a binary target download ("failed downloading '<url>' which
    // is required by binary target") is not a repository and must keep its own message.
    private static final Pattern PACKAGE_URL_PATTERN = Pattern.compile(
        "(?:Fetching|Updating) from (https://[^\\s:]+)|Failed to clone repository (https://[^\\s:]+)");

    /** The package repository URLs an xcodebuild log mentions, in the order they appear. */
    static List<String> harvestPackageUrls(String output) {
        List<String> urls = new ArrayList<>();
        if (output == null) {
            return urls;
        }
        Matcher matcher = PACKAGE_URL_PATTERN.matcher(output);
        while (matcher.find()) {
            String url = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (!urls.contains(url)) {
                urls.add(url);
            }
        }
        return urls;
    }

    /** A binary target's archive, as named by the round that could not download it. */
    record BinaryArtifact(String url, String targetName) {}

    // SwiftPM downloads every missing archive of a round concurrently and reports each one
    private static final Pattern BINARY_ARTIFACT_PATTERN = Pattern.compile(
        "failed downloading '(https://[^'\\s]+)' which is required by binary target '([^'\\s]+)'");

    /** The binary target archives an xcodebuild log could not download, in the order they appear. */
    static List<BinaryArtifact> harvestBinaryArtifacts(String output) {
        List<BinaryArtifact> artifacts = new ArrayList<>();
        if (output == null) {
            return artifacts;
        }
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = BINARY_ARTIFACT_PATTERN.matcher(output);
        while (matcher.find()) {
            if (seen.add(matcher.group(1))) {
                artifacts.add(new BinaryArtifact(matcher.group(1), matcher.group(2)));
            }
        }
        return artifacts;
    }

    // xcodebuild reports a checksum mismatch by target name only; the archive URL is then
    // looked up in the manifest that declares the target, checked out under the job
    private static final Pattern CHECKSUM_MISMATCH_PATTERN = Pattern.compile(
        "checksum of downloaded artifact of binary target '([^'\\s]+)'");

    /** The binary targets whose cached archive an xcodebuild log rejected, each once. */
    static List<String> harvestMismatchedTargets(String output) {
        List<String> names = new ArrayList<>();
        if (output == null) {
            return names;
        }
        Matcher matcher = CHECKSUM_MISMATCH_PATTERN.matcher(output);
        while (matcher.find()) {
            if (!names.contains(matcher.group(1))) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    /**
     * The archive URL a checked-out manifest declares for a binary target, or null. Manifests
     * are Swift code, but binary targets are spelled with literal name and url (the checksum
     * forces a literal anyway), which is what is looked for.
     */
    static String findArtifactUrlInCheckouts(File clonedSourcesDir, String targetName) throws IOException {
        File checkouts = new File(clonedSourcesDir, "checkouts");
        File[] packages = checkouts.listFiles(File::isDirectory);
        if (packages == null) {
            return null;
        }
        Pattern declaration = Pattern.compile(
            "\\.binaryTarget\\(\\s*name:\\s*\"" + Pattern.quote(targetName) + "\"\\s*,\\s*url:\\s*\"([^\"]+)\"");
        for (File pkg : packages) {
            File manifest = new File(pkg, "Package.swift");
            if (!manifest.isFile()) {
                continue;
            }
            Matcher matcher = declaration.matcher(Files.readString(manifest.toPath()));
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    /**
     * Adds an archive to the artifacts to fetch, returning true when it was not known yet.
     * The URL is validated like a package URL; SwiftPM looks the archive up by the URL
     * exactly as the manifest spells it, so that spelling is what is kept.
     */
    static boolean addArtifact(Map<String, BinaryArtifact> artifacts, BinaryArtifact artifact) throws ExtenderException {
        SpmManifestParser.sanitizeUrl(artifact.url);
        if (artifacts.containsKey(artifact.url)) {
            return false;
        }
        if (artifacts.size() >= MAX_BINARY_ARTIFACTS) {
            throw new ExtenderException(String.format(
                "The Swift package graph declares more than %d binary targets, which is not supported",
                MAX_BINARY_ARTIFACTS));
        }
        artifacts.put(artifact.url, artifact);
        return true;
    }

    /**
     * The file name SwiftPM gives an archive in its artifact cache: the URL as a C99 extended
     * identifier (every other character becomes an underscore, a leading digit is prefixed).
     */
    static String artifactCacheName(String url) {
        StringBuilder name = new StringBuilder(url.length() + 1);
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            boolean identifier = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
            name.append(identifier ? c : '_');
        }
        if (name.length() > 0 && Character.isDigit(name.charAt(0))) {
            name.insert(0, '_');
        }
        return name.toString();
    }

    /**
     * Mirrors, resolves and builds, repeating until the package graph closes. Transitive
     * dependencies are only known once the manifests declaring them have run, and those must
     * not run with network access, so discovery happens the other way round: a round that
     * cannot reach a repository names it, the repository is mirrored by plain git (which
     * evaluates nothing), and the round is repeated. The round that finds the graph closed is
     * the build itself, so a resolved graph costs no extra xcodebuild invocation.
     */
    private void resolveAndBuild(SpmServiceBuildState buildState, SpmManifestParser.ParseResult manifest,
            File userLockFile, File clonedSourcesDir, File packageCacheDir, File mirrorsDir,
            Map<String, String> processEnv) throws IOException, ExtenderException {
        Map<String, PackageRepo> repos = new LinkedHashMap<>();
        for (PackageRef declared : manifest.packages.values()) {
            addRepo(repos, declared.url);
        }
        if (userLockFile != null) {
            // the pins name the whole graph, so a current lock file closes it in one round
            for (SpmLockFile.Pin pin : SpmLockFile.parse(userLockFile).values()) {
                addRepo(repos, pin.location);
            }
            copyLockFile(userLockFile, buildState);
        }

        // archives of binary targets, named by the round that could not download them
        Map<String, BinaryArtifact> artifacts = new LinkedHashMap<>();
        File artifactsDir = new File(packageCacheDir, ARTIFACTS_SUBDIR);
        boolean forceRefresh = false;
        boolean refreshRetried = false;
        boolean evictArtifacts = false;
        boolean artifactsRetried = false;
        String lastFailure = null;
        for (int round = 1; round <= MAX_RESOLVE_ROUNDS; round++) {
            Map<String, File> mirrors;
            try {
                mirrors = prefetchMirrors(repos.values(), mirrorsDir, buildState, processEnv, forceRefresh);
            } catch (ExtenderException e) {
                if (!forceRefresh) {
                    throw e;
                }
                // the refresh was a guess; a mirror that cannot be updated must not replace
                // the failure that actually stopped the build
                LOGGER.info("Refreshing the Swift package mirrors failed: {}", e.getMessage());
                throw buildFailure(lastFailure);
            }
            forceRefresh = false;
            prefetchArtifacts(artifacts.values(), artifactsDir, buildState, processEnv, evictArtifacts);
            evictArtifacts = false;
            File gitConfig = writeGitConfig(buildState, repos.values(), mirrors);
            LOGGER.info("Building the Swift package graph offline (round {}, {} repositories mirrored, {} artifacts cached)",
                round, repos.size(), artifacts.size());
            String failure = buildWrapperProject(buildState, clonedSourcesDir, packageCacheDir, mirrorsDir,
                gitConfig, processEnv);
            if (failure == null) {
                return;
            }
            lastFailure = failure;
            boolean discovered = false;
            List<String> harvested = harvestPackageUrls(failure);
            for (String url : harvested) {
                discovered |= addRepo(repos, url);
            }
            for (BinaryArtifact artifact : harvestBinaryArtifacts(failure)) {
                discovered |= addArtifact(artifacts, artifact);
            }
            if (discovered) {
                continue;
            }
            if (!artifactsRetried && failure.contains(CHECKSUM_MISMATCH_MARKER)) {
                // the archive behind a URL changed since it was cached (or a build tampered
                // with the cache): fetch the rejected archives again, once. A cached archive
                // was never named to this job, so its URL comes from the declaring manifest
                artifactsRetried = true;
                evictArtifacts = true;
                for (String targetName : harvestMismatchedTargets(failure)) {
                    String url = findArtifactUrlInCheckouts(clonedSourcesDir, targetName);
                    if (url == null) {
                        // the cache is shared with every concurrent build of this Xcode
                        // version, so nothing wider than the one archive may be evicted
                        throw new ExtenderException(String.format("The cached archive of Swift binary target %s does not "
                            + "match the checksum its manifest declares, and the manifest does not spell the archive URL "
                            + "as a literal next to the target name, so it cannot be fetched again:\n%s", targetName, failure));
                    }
                    addArtifact(artifacts, new BinaryArtifact(url, targetName));
                }
                LOGGER.info("A cached Swift package archive does not match its manifest checksum, fetching again");
                continue;
            }
            // only a resolution failure can be about a mirror; a compile error must not cost
            // the job a second full build
            if (!refreshRetried && failure.contains(RESOLUTION_FAILED_MARKER)) {
                // nothing new to mirror, so the graph may want a tag or branch published after
                // the mirrors were last fetched: refresh every one of them and try once more
                LOGGER.info("Refreshing every Swift package mirror and retrying");
                refreshRetried = true;
                forceRefresh = true;
                continue;
            }
            throw buildFailure(failure);
        }
        throw new ExtenderException(String.format(
            "The Swift package graph did not resolve in %d rounds; it is either too deep or keeps changing",
            MAX_RESOLVE_ROUNDS));
    }

    // the message is what the client gets; the cause-carrying constructor would report the
    // IOException the executor raised and drop both the output and the hint
    private static ExtenderException buildFailure(String output) {
        return new ExtenderException("Swift package build failed:\n" + output + offlineBuildHint(output));
    }

    /**
     * Mirrors every known repository with plain git, the only step of the build with network
     * access. git never evaluates Package.swift, so a malicious manifest gets no chance to
     * phone home here. A mirror fetched less than the refresh interval ago is used as it is,
     * which is what keeps parallel builds of the same graph from re-asking the forge.
     */
    Map<String, File> prefetchMirrors(Collection<PackageRepo> repos, File mirrorsDir, SpmServiceBuildState buildState,
            Map<String, String> processEnv, boolean forceRefresh) throws IOException, ExtenderException {
        Map<String, File> mirrors = new LinkedHashMap<>();
        for (PackageRepo repo : repos) {
            File mirror = new File(mirrorsDir, mirrorDirName(repo.key));
            synchronized (fetchLock(mirror)) {
                updateMirror(mirror, repo, mirrorsDir, buildState, processEnv, forceRefresh);
            }
            mirrors.put(repo.key, mirror);
        }
        return mirrors;
    }

    private void updateMirror(File mirror, PackageRepo repo, File mirrorsDir, SpmServiceBuildState buildState,
            Map<String, String> processEnv, boolean forceRefresh) throws IOException, ExtenderException {
        // the marker is missing for a mirror written before it existed, which just fetches once
        File marker = new File(mirrorsDir, mirror.getName() + FETCH_MARKER_SUFFIX);
        String failure;
        if (!mirror.isDirectory()) {
            LOGGER.info("Mirroring {} into {}", repo.fetchUrl, mirror.getName());
            failure = runGit(List.of("git", "clone", "--mirror", "--quiet", repo.fetchUrl, mirror.getAbsolutePath()),
                mirrorsDir, buildState, processEnv);
            if (failure != null) {
                // a half-written clone would be mistaken for a mirror next time
                FileUtils.deleteQuietly(mirror);
            }
        } else if (forceRefresh || System.currentTimeMillis() - marker.lastModified() > mirrorRefreshIntervalMillis) {
            LOGGER.info("Updating mirror {} for {}", mirror.getName(), repo.fetchUrl);
            failure = runGit(List.of("git", "-C", mirror.getAbsolutePath(), "fetch", "--quiet", "--prune", "origin"),
                mirrorsDir, buildState, processEnv);
        } else {
            LOGGER.info("Mirror {} for {} is up to date", mirror.getName(), repo.fetchUrl);
            return;
        }
        if (failure != null) {
            throw new ExtenderException("Fetching Swift package " + repo.fetchUrl + " failed:\n" + failure);
        }
        touchFetchMarker(marker);
    }

    private static void touchFetchMarker(File marker) {
        try {
            Files.write(marker.toPath(), new byte[0]);
            Files.setLastModifiedTime(marker.toPath(), FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException e) {
            // costs an extra fetch on the next build, nothing else
            LOGGER.warn("Failed to record the fetch time of {}", marker, e);
        }
    }

    /**
     * Fetches every known binary target archive that is not in SwiftPM's artifact cache yet,
     * with plain curl: like git for the mirrors, it evaluates nothing. The archive is keyed by
     * its URL and immutable as far as this step is concerned; SwiftPM checks it against the
     * checksum in the consuming manifest, and a mismatch comes back here as an eviction.
     */
    void prefetchArtifacts(Collection<BinaryArtifact> artifacts, File artifactsDir, SpmServiceBuildState buildState,
            Map<String, String> processEnv, boolean evict) throws IOException, ExtenderException {
        if (artifacts.isEmpty()) {
            return;
        }
        artifactsDir.mkdirs();
        for (BinaryArtifact artifact : artifacts) {
            File archive = new File(artifactsDir, artifactCacheName(artifact.url));
            if (evict) {
                FileUtils.deleteQuietly(archive);
            }
            synchronized (fetchLock(archive)) {
                if (archive.isFile()) {
                    LOGGER.info("Archive of binary target {} is cached", artifact.targetName);
                    continue;
                }
                downloadArtifact(artifact, archive, artifactsDir, buildState, processEnv);
            }
        }
    }

    private void downloadArtifact(BinaryArtifact artifact, File archive, File artifactsDir,
            SpmServiceBuildState buildState, Map<String, String> processEnv) throws IOException, ExtenderException {
        LOGGER.info("Fetching archive of binary target {} from {}", artifact.targetName, artifact.url);
        // a concurrent job fetching the same URL writes its own part file; the move is atomic
        File part = new File(artifactsDir, archive.getName() + ".part-" + UUID.randomUUID());
        List<String> args = List.of("curl", "--fail", "--silent", "--show-error", "--location", "--max-redirs", "5",
            // https only, redirects included
            "--proto", "=https", "--proto-redir", "=https",
            "--max-time", Long.toString(Math.max(1, artifactDownloadTimeoutMillis / 1000)),
            // only honoured when the server declares a length; the size is checked again below
            "--max-filesize", Long.toString(maxArtifactSizeBytes),
            "--output", part.getAbsolutePath(), artifact.url);
        try {
            String failure = runPrefetchCommand(args, artifactPrefetchPolicy(artifactsDir), Map.of(), buildState, processEnv);
            if (failure != null) {
                throw new ExtenderException("Fetching the archive of Swift binary target " + artifact.targetName
                    + " from " + artifact.url + " failed:\n" + failure);
            }
            if (!part.isFile()) {
                throw new ExtenderException("Fetching the archive of Swift binary target " + artifact.targetName
                    + " from " + artifact.url + " produced no file");
            }
            if (part.length() > maxArtifactSizeBytes) {
                throw new ExtenderException(String.format("The archive of Swift binary target %s (%s) is larger than %d bytes",
                    artifact.targetName, artifact.url, maxArtifactSizeBytes));
            }
            Files.move(part.toPath(), archive.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            FileUtils.deleteQuietly(part);
        }
    }

    private Object fetchLock(File path) {
        return fetchLocks[Math.floorMod(path.getAbsolutePath().hashCode(), LOCK_STRIPES)];
    }

    /** Network on, the artifact cache the only writable location. */
    static SandboxPolicy artifactPrefetchPolicy(File artifactsDir) {
        return SandboxPolicy.dependencyResolver(List.of(artifactsDir.getAbsolutePath()))
                // proxy settings lookup by libcurl
                .withMachServices(List.of("com.apple.SystemConfiguration.configd"));
    }

    /**
     * Runs git under the pre-fetch policy. Returns null on exit 0, otherwise the command's
     * output (the executor raises on a non-zero exit); a launch failure still propagates.
     */
    private String runGit(List<String> args, File mirrorsDir, SpmServiceBuildState buildState,
            Map<String, String> processEnv) throws IOException {
        // https only: a redirect to ssh://, git:// or a local path is refused by git itself
        return runPrefetchCommand(args, gitPrefetchPolicy(mirrorsDir), Map.of("GIT_ALLOW_PROTOCOL", "https"),
            buildState, processEnv);
    }

    private String runPrefetchCommand(List<String> args, SandboxPolicy policy, Map<String, String> extraEnv,
            SpmServiceBuildState buildState, Map<String, String> processEnv) throws IOException {
        ProcessExecutor processExecutor = new ProcessExecutor();
        processExecutor.setCwd(buildState.getWrapperDir());
        processExecutor.putEnv(processEnv);
        processExecutor.putEnv(extraEnv);
        processExecutor.setPolicy(policy);
        try {
            processExecutor.execute(args);
            return null;
        } catch (IOException e) {
            String output = processExecutor.getOutput();
            if (output == null || output.isBlank()) {
                // nothing ran at all (launcher or git missing): not a git failure
                throw e;
            }
            LOGGER.info("{} failed:\n{}", String.join(" ", args), output.strip());
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running " + String.join(" ", args), e);
        }
    }

    /** Mirror directory for a canonical package key: readable name plus a hash, so nothing collides or escapes. */
    static String mirrorDirName(String canonicalKey) {
        String name = canonicalKey.substring(canonicalKey.lastIndexOf('/') + 1);
        name = UNSAFE_CACHE_SUBDIR_CHARS.matcher(name).replaceAll("_");
        if (name.length() > 40) {
            name = name.substring(0, 40);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalKey.getBytes(StandardCharsets.UTF_8));
            return name + "-" + HexFormat.of().formatHex(digest, 0, 6) + ".git";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Git configuration for the offline build: every URL SwiftPM may ask git for is rewritten
     * to its mirror. insteadOf matches by prefix and the longest prefix wins, so both the
     * ".git" and the bare spelling of each location are listed; a URL that matches nothing
     * stays https and is then refused by GIT_ALLOW_PROTOCOL=file.
     */
    static File writeGitConfig(SpmServiceBuildState buildState, Collection<PackageRepo> repos,
            Map<String, File> mirrors) throws IOException {
        StringBuilder config = new StringBuilder();
        for (PackageRepo repo : repos) {
            config.append("[url \"file://").append(mirrors.get(repo.key).getAbsolutePath()).append("\"]\n");
            for (String spelling : repo.spellings) {
                config.append("\tinsteadOf = ").append(spelling).append('\n');
            }
        }
        File gitConfig = new File(buildState.getWorkingDir(), GIT_CONFIG_FILENAME);
        Files.writeString(gitConfig.toPath(), config.toString());
        return gitConfig;
    }

    private static void addSpellings(Set<String> spellings, String url) {
        String trimmed = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        spellings.add(trimmed);
        if (trimmed.endsWith(".git")) {
            spellings.add(trimmed.substring(0, trimmed.length() - 4));
        } else {
            spellings.add(trimmed + ".git");
        }
    }

    /** Plain git fetching into the mirror store: network on, nothing else beyond the toolchain policy. */
    static SandboxPolicy gitPrefetchPolicy(File mirrorsDir) {
        return SandboxPolicy.dependencyResolver(List.of(mirrorsDir.getAbsolutePath()))
                // proxy settings lookup by libcurl
                .withMachServices(List.of("com.apple.SystemConfiguration.configd"));
    }

    /**
     * Resolves and builds the graph offline. Returns null when the build succeeded, otherwise
     * the xcodebuild output, which names the repositories the resolution could not reach.
     */
    private String buildWrapperProject(SpmServiceBuildState buildState, File clonedSourcesDir, File packageCacheDir,
            File mirrorsDir, File gitConfig, Map<String, String> processEnv) throws IOException, ExtenderException {
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
        // Package.swift manifests, plugins and macros are untrusted code SwiftPM compiles and
        // runs. SwiftPM confines them with its own nested sandbox-exec, which the kernel refuses
        // inside the process sandbox xcodebuild already runs in, so that confinement is dropped
        // only where the outer sandbox replaces it - never where the sandbox is switched off.
        if (ProcessSandbox.current().isEnabled()) {
            args.add("-IDEPackageSupportDisableManifestSandbox=YES");
        }
        args.addAll(buildState.getExtraBuildSettings());
        args.add("build");

        ProcessExecutor processExecutor = new ProcessExecutor();
        processExecutor.setCwd(buildState.getWrapperDir());
        processExecutor.putEnv(processEnv);
        // the system git xcodebuild spawns reads the insteadOf rewrites from here and may
        // only use the file transport; both hold even when the process sandbox is disabled
        processExecutor.putEnv("GIT_CONFIG_GLOBAL", gitConfig.getAbsolutePath());
        processExecutor.putEnv("GIT_ALLOW_PROTOCOL", "file");
        processExecutor.setPolicy(xcodebuildPolicy(buildState, packageCacheDir, mirrorsDir));
        try {
            processExecutor.execute(args);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeBuildLog(buildState, processExecutor);
            throw new IOException("Interrupted while building the Swift package graph", e);
        } catch (IOException e) {
            writeBuildLog(buildState, processExecutor);
            String output = processExecutor.getOutput();
            if (output == null || output.isBlank()) {
                // nothing ran at all (launcher or xcodebuild missing): not a build failure
                throw e;
            }
            return output;
        }
        writeBuildLog(buildState, processExecutor);
        return null;
    }

    /** Adds the reason a resolution can fail under the offline build, when the log points at one. */
    static String offlineBuildHint(String output) {
        if (output == null) {
            return "";
        }
        // every repository the log named was mirrored and rewritten to its mirror before this
        // round, so a URL git still refuses is one no mirror could be built for
        if (output.contains("transport 'https' not allowed")) {
            return "\n\nA dependency could not be mirrored; Swift package builds resolve offline from git "
                + "mirrors, so every package in the graph must be a plain https git repository.";
        }
        // every archive the log named was fetched into the cache before this round, so one
        // SwiftPM still cannot get is one it refused to read from there
        if (output.contains("required by binary target")) {
            return "\n\nA binary target archive was fetched into the package cache but SwiftPM could not use it; "
                + "resolution runs without network access here.";
        }
        if (output.contains(CHECKSUM_MISMATCH_MARKER)) {
            return "\n\nThe archive behind a binary target URL does not match the checksum its manifest declares, "
                + "even after fetching it again; the publisher replaced the archive or the manifest is wrong.";
        }
        return "";
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
