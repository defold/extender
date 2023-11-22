package com.defold.extender;

import com.google.common.collect.ImmutableMap;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.RegexFileFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.List;
import java.util.AbstractMap;
import java.util.AbstractMap.SimpleEntry;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.defold.extender.FrameworkUtil;
import com.defold.extender.services.GradleService;
import com.defold.extender.services.CocoaPodsService;
import com.defold.extender.services.CocoaPodsService.PodSpec;
import com.defold.extender.services.CocoaPodsService.ResolvedPods;

class Extender {
    private static final Logger LOGGER = LoggerFactory.getLogger(Extender.class);
    private final String appManifestPath;
    private final String platform;
    private final String hostPlatform;
    private final File sdk;
    private final File uploadDirectory;
    private final File jobDirectory;
    private final File buildDirectory;
    private final Configuration config;                 // build.yml from the defoldsdk
    private final PlatformConfig platformConfig;        // "common", platform, arch-platform from build.yml
    private final PlatformConfig platformVariantConfig; // "common", platform, arch-platform from build_variant.yml
    private final PlatformConfig platformAppConfig;     // "common", platform, arch-platform from game.appmanifest
    private final ExtensionManifestValidator manifestValidator;
    private final TemplateExecutor templateExecutor = new TemplateExecutor();
    private final ProcessExecutor processExecutor = new ProcessExecutor();
    private final Boolean withSymbols;
    private final Boolean useJetifier;
    private final String buildArtifacts;

    private Map<String, File>                   manifestFiles;
    private Map<String, Map<String, Object>>    manifestConfigs;
    private Map<String, Object>                 mergedAppContext;

    private List<File> extDirs;
    private List<File> manifests;       // The list of ext.manifests found in the upload
    private List<File> gradlePackages;
    private ResolvedPods resolvedPods;
    private int nameCounter = 0;


    static final String FOLDER_ENGINE_SRC = "src";      // source for the engine library
    static final String FOLDER_PLUGIN_SRC = "pluginsrc";// source for the pipeline/format plugins
    static final String FOLDER_COMMON_SRC = "commonsrc";// common source shared between both types

    static final String APPMANIFEST_FILENAME = "app.manifest";
    static final String APPMANIFEST_BASE_VARIANT_KEYWORD = "baseVariant";
    static final String APPMANIFEST_WITH_SYMBOLS_KEYWORD = "withSymbols";
    static final String APPMANIFEST_BUILD_ARTIFACTS_KEYWORD = "buildArtifacts";
    static final String APPMANIFEST_JETIFIER_KEYWORD = "jetifier";
    static final String FRAMEWORK_RE = "(.+)\\.framework";
    static final String JAR_RE = "(.+\\.jar)";
    static final String JS_RE = "(.+\\.js)";
    static final String PROTO_RE = "(?i).*(\\.proto)";
    static final String ENGINE_JAR_RE = "(?:.*)\\/share\\/java\\/[\\w\\-\\.]*\\.jar$";

    private static final String MANIFEST_IOS    = "Info.plist";
    private static final String MANIFEST_OSX    = "Info.plist";
    private static final String MANIFEST_ANDROID= "AndroidManifest.xml";
    private static final String MANIFEST_HTML5  = "engine_template.html";


    // Check that the manifest only contains valid platforms
    private final String[] ALLOWED_MANIFEST_PLATFORMS = new String[] {
        "common",
        "ios", "armv7-ios","arm64-ios","x86_64-ios",
        "android", "armv7-android","arm64-android",
        "osx", "x86-osx","x86_64-osx","arm64-osx",
        "linux", "x86-linux","x86_64-linux",
        "win32", "x86-win32","x86_64-win32",
        "web", "js-web","wasm-web",
        "nx64", "arm64-nx64",
        "ps4", "x86_64-ps4",
        "ps5", "x86_64-ps5",
    };

    // This class specifies the set of files that are used when running proguard on
    // the project jars that were found during the build process. This class is only
    // relevant on Android.
    //
    // * proGuardFiles - Array of .pro files that contain settings and rules that specify
    //                   what ProGuard should do with the input jars.
    // * libraryJars - Array of .jar files should be passed to ProGuard as '-libraryjar' entries.
    //                 Everything from a libraryjar will be kept by ProGuard, i.e no optimization or
    //                 obfuscation will be performed.
    private class ProGuardContext {
        public List<String> proGuardFiles = new ArrayList<>();
        public List<String> libraryJars   = new ArrayList<>();
    }

    private static final boolean DM_DEBUG_DISABLE_PROGUARD = System.getenv("DM_DEBUG_DISABLE_PROGUARD") != null;

    Extender(String platform, File sdk, File jobDirectory, File uploadDirectory, File buildDirectory, Map<String,String> env) throws IOException, ExtenderException {
        this.jobDirectory = jobDirectory;
        this.uploadDirectory = uploadDirectory;
        this.buildDirectory = buildDirectory;
        this.gradlePackages = new ArrayList<>();

        // Read config from SDK
        this.config = Extender.loadYaml(this.jobDirectory, new File(sdk.getPath() + "/extender/build.yml"), Configuration.class);

        // Read the app manifest from the upload folder
        Collection<File> allFiles = FileUtils.listFiles(uploadDirectory, null, true);
        List<File> appManifests = allFiles.stream().filter(f -> f.getName().equals(APPMANIFEST_FILENAME)).collect(Collectors.toList());
        if (appManifests.size() > 1 ) {
            throw new ExtenderException("Only one app.manifest allowed!");
        }

        AppManifestConfiguration appManifest = null;
        AppManifestConfiguration baseVariantManifest = null;

        if (appManifests.isEmpty()) {
            this.appManifestPath = "";
            appManifest = new AppManifestConfiguration();
        } else {
            this.appManifestPath = ExtenderUtil.getRelativePath(this.uploadDirectory, appManifests.get(0));
            appManifest = Extender.loadYaml(this.jobDirectory, appManifests.get(0), AppManifestConfiguration.class);

            // An completely empty manifest will yield a null pointer in result from Extender.loadYaml
            appManifest = (appManifest != null) ? appManifest : new AppManifestConfiguration();

            // An manifest with no platform keyword will yield a null-pointer for this.appManifest.platforms
            // This happens if we get a manifest with just the context keyword given.
            if (appManifest.platforms == null) {
                appManifest.platforms = new HashMap<String, AppManifestPlatformConfig>();
            }

            String baseVariant = ExtenderUtil.getAppManifestContextString(appManifest, APPMANIFEST_BASE_VARIANT_KEYWORD, null);
            if (baseVariant != null)
            {
                File baseVariantFile = new File(sdk.getPath() + "/extender/variants/" + baseVariant + ".appmanifest");

                if (!baseVariantFile.exists()) {
                    throw new ExtenderException("Base variant " + baseVariant + " not found!");
                }
                LOGGER.info("Using base variant: " + baseVariant);

                baseVariantManifest = Extender.loadYaml(this.jobDirectory, baseVariantFile, AppManifestConfiguration.class);
            }
        }

        this.useJetifier = ExtenderUtil.getAppManifestBoolean(appManifest, platform, APPMANIFEST_JETIFIER_KEYWORD, true);
        this.withSymbols = ExtenderUtil.getAppManifestContextBoolean(appManifest, APPMANIFEST_WITH_SYMBOLS_KEYWORD, true);
        this.buildArtifacts = ExtenderUtil.getAppManifestContextString(appManifest, APPMANIFEST_BUILD_ARTIFACTS_KEYWORD, "");

        this.platform = platform;
        this.sdk = sdk;

        String os = System.getProperty("os.name");

        // These host names are using the Defold SDK names
        if (os.contains("Mac")) {
            this.hostPlatform = "x86_64-macos";
        } else if (os.contains("Windows")) {
            this.hostPlatform = "x86_64-win32";
        } else {
            this.hostPlatform = "x86_64-linux";
        }

        if (config.platforms.get(platform) == null) {
            throw new ExtenderException(String.format("Unsupported platform %s by this sdk", platform));
        }

        // Merge the platform configs from build.yml into a single instance: common -> platform -> arch-platform
        this.platformConfig = new PlatformConfig();
        this.platformConfig.context = new HashMap<>(config.context); // the context from build.yml

        for (String platformAlt : ExtenderUtil.getPlatformAlternatives(platform)) {
            PlatformConfig platformConfigAlt = config.platforms.get(platformAlt);
            if (platformConfigAlt == null)
                continue;

            ExtenderUtil.mergeObjects(this.platformConfig, platformConfigAlt);
        }

        // Merge the variant info into a single config
        this.platformVariantConfig = new PlatformConfig();
        if (baseVariantManifest != null) {
            for (String platformAlt : ExtenderUtil.getPlatformAlternatives(platform)) {
                AppManifestPlatformConfig configAlt = baseVariantManifest.platforms.get(platformAlt);
                if (configAlt == null)
                    continue;
                PlatformConfig platformConfigAlt = ExtenderUtil.createPlatformConfig(configAlt);
                ExtenderUtil.mergeObjects(this.platformVariantConfig, platformConfigAlt);
            }
        }

        // Merge the app manifest info into a single config
        this.platformAppConfig = new PlatformConfig();
        for (String platformAlt : ExtenderUtil.getPlatformAlternatives(platform)) {
            AppManifestPlatformConfig configAlt = appManifest.platforms.get(platformAlt);
            if (configAlt == null) {
                continue;
            }

            PlatformConfig platformConfigAlt = ExtenderUtil.createPlatformConfig(configAlt);
            ExtenderUtil.mergeObjects(this.platformAppConfig, platformConfigAlt);
        }

        LOGGER.info("Using context for platform: " + platform);

        processExecutor.setCwd(jobDirectory);

        {
            HashMap<String, Object> envContext = new HashMap<>();
            envContext.put("build_folder", buildDirectory);
            envContext.put("dynamo_home", sdk);
            envContext.put("env.LD_LIBRARY_PATH", "."); // Easier when running a standalone local without such a variable

            processExecutor.putEnv("DYNAMO_HOME", sdk.getAbsolutePath());
            String java_home = System.getenv("JAVA_HOME");
            if (java_home != null)
            {
                processExecutor.putEnv("JAVA_HOME", java_home);
            }

            // Make system env variables available for the template execution below.
            for (Map.Entry<String, String> sysEnvEntry : System.getenv().entrySet()) {
                envContext.put("env." + sysEnvEntry.getKey(), sysEnvEntry.getValue());
            }
            // Make custom env variables available for the template execution below.
            for (Map.Entry<String, String> envEntry : env.entrySet()) {
                envContext.put("env." + envEntry.getKey(), envEntry.getValue());
            }

            Set<String> keys = this.platformConfig.env.keySet();
            for (String k : keys) {
                String v = this.platformConfig.env.get(k);
                v = templateExecutor.execute(v, envContext);
                processExecutor.putEnv(k, v);
            }

            // Get all "custom" env variables for this process executor and make it available
            // for commands later on.
            for (Map.Entry<String, String> envEntry : processExecutor.getEnv().entrySet()) {
                this.platformConfig.context.put("env." + envEntry.getKey(), envEntry.getValue());
            }
        }

        // The allowed symbols are the union of the values from the different "levels": "context: allowedSymbols: [...]" + "context: platforms: arm64-osx: allowedSymbols: [...]"
        List<String> allowedSymbols = ExtenderUtil.mergeLists(platformConfig.allowedSymbols, (List<String>) this.config.context.getOrDefault("allowedSymbols", new ArrayList<String>()) );

        // The user input (ext.manifest + _app/app.manifest) will be checked against this validator
        this.manifestValidator = new ExtensionManifestValidator(new WhitelistConfig(), this.platformConfig.allowedFlags, allowedSymbols);

        // Make sure the user hasn't input anything invalid in the manifest
        this.manifestValidator.validate(this.appManifestPath, this.uploadDirectory, this.platformAppConfig.context);

        // Collect extension directories (used by both buildEngine and buildClassesDex)
        this.manifests = allFiles.stream().filter(f -> f.getName().equals("ext.manifest")).collect(Collectors.toList());
        this.extDirs = this.manifests.stream().map(File::getParentFile).collect(Collectors.toList());

        // Load and merge manifests
        loadManifests();
    }

    Extender(String platform, File sdk, File jobDirectory, File uploadDirectory, File buildDirectory) throws IOException, ExtenderException {
        this(platform, sdk, jobDirectory, uploadDirectory, buildDirectory, new HashMap<String, String>());
    }

    private int getAndIncreaseNameCount() {
        return nameCounter++;
    }

    private String getNameUUID() {
        int c = getAndIncreaseNameCount();
        return String.format("%d", c);
    }

    private File createBuildFile(String name) {
        return new File(buildDirectory, name);
    }

    private File uniqueTmpFile(String prefix, String suffix) {
        return createBuildFile(prefix + getNameUUID() + suffix);
    }

    private File uniqueTmpFile(String pattern) {
        return createBuildFile(String.format(pattern, getNameUUID()));
    }

    private static int countLines(String str) {
       String[] lines = str.split("\r\n|\r|\n");
       return lines.length;
    }

    static <T> T loadYaml(File root, File manifest, Class<T> type) throws IOException, ExtenderException {
        String yaml = FileUtils.readFileToString(manifest);

        if (yaml.contains("\t")) {
            int numLines = 1 + countLines(yaml.substring(0, yaml.indexOf("\t")));
            throw new ExtenderException(String.format("%s:%d: error: Manifest files are YAML files and cannot contain tabs. Indentation should be done with spaces.", ExtenderUtil.getRelativePath(root, manifest), numLines));
        }

        try {
            return new Yaml(new ExtenderYamlSafeConstructor()).loadAs(yaml, type);
        } catch(YAMLException e) {
            throw new ExtenderException(String.format("%s:1: error: %s", ExtenderUtil.getRelativePath(root, manifest), e.toString()));
        }
    }


    // Resolves all Mustache template variables
    private void resolveVariables(Map<String, Object> context) {
        Set<String> keys = context.keySet();
        for (String k : keys) {
            Object v = context.get(k);
            try {
                if (v instanceof String) {
                    v = templateExecutor.execute((String) v, context);
                } else if (v instanceof List) {
                    v = templateExecutor.execute((List<String>) v, context);
                }
            } catch (Exception e) {
                LOGGER.error(String.format("Failed to substitute key %s", k));
                throw e;
            }
            context.put(k, v);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createContext(Map<String, Object> src) throws ExtenderException {
        Map<String, Object> context = new HashMap<>(src); // TODO: Create deep copy

        // Should not be allowed to be overridden by manifests
        context.put("dynamo_home", ExtenderUtil.getRelativePath(jobDirectory, sdk));
        context.put("platform", this.platform);
        context.put("host_platform", this.hostPlatform);

        resolveVariables(context);
        return context;
    }

    private List<String> getFrameworks(File dir) {
        List<String> frameworks = new ArrayList<>();
        final String[] platformParts = this.platform.split("-");
        frameworks.addAll(ExtenderUtil.collectFilesByName(new File(dir, "lib" + File.separator + this.platform), FRAMEWORK_RE)); // e.g. armv64-ios
        if (platformParts.length == 2) {
            frameworks.addAll(ExtenderUtil.collectFilesByName(new File(dir, "lib" + File.separator + platformParts[1]), FRAMEWORK_RE)); // e.g. "ios"
        }
        return frameworks;
    }
    private List<String> getFrameworks(ResolvedPods resolvedPods) {
        List<String> frameworks = new ArrayList<>();
        if (resolvedPods != null) {
            frameworks.addAll(getFrameworks(resolvedPods.frameworksDir));
        }
        return frameworks;
    }

    // Get a list of subfolders matching the current platform
    private List<String> getPlatformPaths(File dir) {
        List<String> paths = new ArrayList<>();
        final String[] platformParts = this.platform.split("-");
        File libDir = new File(dir, this.platform);
        if (libDir.exists()) {
            paths.add(libDir.getAbsolutePath());
        }
        if (platformParts.length == 2) {
            File dirShort = new File(dir, platformParts[1]);
            if (dirShort.exists()) {
                paths.add(dirShort.getAbsolutePath());
            }
        }
        return paths;
    }

    private List<String> getFrameworkPaths(File dir) {
        return getPlatformPaths(new File(dir, "lib"));
    }
    private List<String> getFrameworkPaths(ResolvedPods resolvedPods) {
        List<String> frameworkPaths = new ArrayList<>();
        if (resolvedPods != null) {
            frameworkPaths.addAll(getFrameworkPaths(resolvedPods.frameworksDir));
        }
        return frameworkPaths;
    }

    private List<String> getFrameworkStaticLibs(ResolvedPods resolvedPods) {
        List<String> staticLibs = new ArrayList<>();
        if (resolvedPods != null) {
            final File dir = resolvedPods.frameworksDir;
            final String[] platformParts = this.platform.split("-");
            staticLibs.addAll(ExtenderUtil.collectStaticLibsByName(new File(dir, "lib" + File.separator + this.platform))); // e.g. armv64-ios
            if (platformParts.length == 2) {
                staticLibs.addAll(ExtenderUtil.collectStaticLibsByName(new File(dir, "lib" + File.separator + platformParts[1]))); // e.g. "ios"
            }
        }
        return staticLibs;
    }
    private List<String> getFrameworkStaticLibPaths(ResolvedPods resolvedPods) {
        return getPlatformPaths(new File(resolvedPods.frameworksDir, "lib"));
    }


    private List<String> getExtensionLibJars(File extDir) {
        List<String> jars = new ArrayList<>();
        jars.addAll(ExtenderUtil.collectFilesByPath(new File(extDir, "lib" + File.separator + this.platform), JAR_RE)); // e.g. armv7-android
        String[] platformParts = this.platform.split("-");
        if (platformParts.length == 2) {
            jars.addAll(ExtenderUtil.collectFilesByPath(new File(extDir, "lib" + File.separator + platformParts[1]), JAR_RE)); // e.g. "android"
        }
        return jars;
    }

    private List<String> getAllExtensionsLibJars() {
        List<String> allLibJars = new ArrayList<>();
        for (File extDir : this.extDirs) {
            allLibJars.addAll(getExtensionLibJars(extDir));
        }

        // Where we previously stored the dependencies directly inside the extensions
        // we now use gradle to resolve the dependencies
        for (File f : gradlePackages) {
            if (f.getName().endsWith(".jar"))
                allLibJars.add(f.getAbsolutePath());
            else if(f.getName().endsWith(".aar")) {
                File classesJar = new File(f, "classes.jar");
                if (classesJar.exists()) {
                    allLibJars.add(classesJar.getAbsolutePath());
                }

                // There can be an optional libs/ folder with jar files.
                // Make sure to copy these!
                // https://developer.android.com/studio/projects/android-library.html#aar-contents
                File libs = new File(f, "libs");
                if (libs.exists() && libs.isDirectory()) {
                    for(File lib : libs.listFiles()) {
                        allLibJars.add(lib.getAbsolutePath());
                    }
                }
            }
        }

        return allLibJars;
    }

    private List<String> pruneNonExisting(List<String> paths) {
        List<String> existing = new ArrayList<>();
        for (String path : paths) {
            File f = new File(jobDirectory + File.separator + path);
            if (f.exists())
                existing.add(path);
        }
        return existing;
    }

    private List<String> getExtLocalIncludeDirs(File dir) {
        List<String> includes = new ArrayList<>();

        includes.add( ExtenderUtil.getRelativePath(jobDirectory, new File(dir, "include" + File.separator + this.platform) ) );

        String[] platformParts = this.platform.split("-");
        if (platformParts.length == 2) {
            includes.add( ExtenderUtil.getRelativePath(jobDirectory, new File(dir, "include" + File.separator + platformParts[1])));
        }

        includes.add( ExtenderUtil.getRelativePath(jobDirectory, new File(dir, "include") ) );
        return includes;
    }

    private List<String> getFrameworkStaticLibIncludeDirs(ResolvedPods pods) {
        List<String> includeDirs = new ArrayList<>();
        if (resolvedPods != null) {
            includeDirs.add(ExtenderUtil.getRelativePath(jobDirectory, new File(resolvedPods.frameworksDir, "headers" + File.separator + this.platform)));
            String[] platformParts = this.platform.split("-");
            if (platformParts.length == 2) {
                includeDirs.add(ExtenderUtil.getRelativePath(jobDirectory, new File(resolvedPods.frameworksDir, "headers" + File.separator + platformParts[1])));
            }
        }
        return includeDirs;
    }

    private List<String> getIncludeDirs(File extDir) {
        List<String> includes = getExtLocalIncludeDirs(extDir);

        includes.add( ExtenderUtil.getRelativePath(jobDirectory, new File(buildDirectory, extDir.getName())) ); // where we generate source from protobuf files
        includes.add( ExtenderUtil.getRelativePath(jobDirectory, uploadDirectory) ); //TODO: Do we ever put stuff here? Isn't it more useful to include the build folder?

        // Add the other extensions include folders
        for (File otherExtDir : this.getExtensionFolders()) {
            if (extDir.getName().equals(otherExtDir.getName())) {
                continue;
            }
            includes.addAll(getExtLocalIncludeDirs(otherExtDir));
        }

        // Add include folders for resolved pods
        if (resolvedPods != null) {
            List<String> podIncludes = new ArrayList<>();
            for (PodSpec pod : resolvedPods.pods) {
                for (File podIncludePath : pod.includePaths) {
                    String relativePath = ExtenderUtil.getRelativePath(jobDirectory, podIncludePath);
                    if (!podIncludes.contains(relativePath)) {
                        podIncludes.add(relativePath);
                    }
                }
                podIncludes.add( ExtenderUtil.getRelativePath(jobDirectory, pod.dir) );
                podIncludes.add( ExtenderUtil.getRelativePath(jobDirectory, pod.generatedDir) );
            }
            includes.addAll(podIncludes);
            includes.addAll(getFrameworkStaticLibIncludeDirs(resolvedPods));
        }

        return pruneNonExisting(includes);
    }


    // swiftc: https://gist.github.com/enomoto/7f11d57e4add7e702f9f84f34d3a0f8c
    // swift-frontend: https://gist.github.com/palaniraja/b4de1e64e874b68bda9e5236829cd8a6

    private void emitSwiftHeaders(PodSpec pod, Map<String, Object> manifestContext, List<String> commands) throws IOException, InterruptedException, ExtenderException {
        List<String> includes = getIncludeDirs(pod.dir);

        List<String> frameworks = new ArrayList<>();
        frameworks.addAll(getFrameworks(pod.dir));
        frameworks.addAll(getFrameworks(resolvedPods));
        List<String> frameworkPaths = new ArrayList<>();
        frameworkPaths.addAll(getFrameworkPaths(pod.dir));
        frameworkPaths.addAll(getFrameworkPaths(resolvedPods));

        Map<String, Object> context = createContext(manifestContext);
        context.put("ext", ImmutableMap.of("includes", includes, "frameworks", frameworks, "frameworkPaths", frameworkPaths));
        context.put("moduleName", pod.moduleName);
        context.put("swiftSourceFiles", pod.swiftSourceFilePaths);
        context.put("swiftHeaderPath", pod.swiftModuleHeader);
        context.put("swiftVersion", "5");

        String command = templateExecutor.execute(this.platformConfig.emitSwiftHeaderCmd, context);
        // LOGGER.info("swiftc command to emot header: " + command);
        commands.add(command);
    }

    private void emitSwiftModule(PodSpec pod, Map<String, Object> manifestContext, List<String> commands) throws IOException, InterruptedException, ExtenderException {
        List<String> includes = getIncludeDirs(pod.dir);

        List<String> frameworks = new ArrayList<>();
        frameworks.addAll(getFrameworks(pod.dir));
        frameworks.addAll(getFrameworks(resolvedPods));
        List<String> frameworkPaths = new ArrayList<>();
        frameworkPaths.addAll(getFrameworkPaths(pod.dir));
        frameworkPaths.addAll(getFrameworkPaths(resolvedPods));

        Map<String, Object> context = createContext(manifestContext);
        context.put("ext", ImmutableMap.of("includes", includes, "frameworks", frameworks, "frameworkPaths", frameworkPaths));
        context.put("moduleName", pod.moduleName);
        context.put("swiftSourceFiles", pod.swiftSourceFilePaths);
        context.put("swiftModulePath", new File(pod.generatedDir, pod.moduleName + ".swiftmodule"));
        context.put("swiftVersion", "5");
        String command = templateExecutor.execute(this.platformConfig.emitSwiftModuleCmd, context);
        // LOGGER.info("swiftc command to emit module: " + command);
        commands.add(command);
    }

    private File addCompileFileSwift(PodSpec pod, int index, File src, Map<String, Object> manifestContext, List<String> commands) throws IOException, InterruptedException, ExtenderException {
        File o = new File(buildDirectory, String.format("%s_%d.o", src.getName(), index));

        // remove the primary source file from the set of all source files
        String swiftPrimarySourceFile = src.getAbsolutePath();
        Set<String> swiftSourceFilePaths = new HashSet<>(pod.swiftSourceFilePaths);
        swiftSourceFilePaths.remove(swiftPrimarySourceFile);

        List<String> includes = getIncludeDirs(pod.dir);

        List<String> frameworks = new ArrayList<>();
        frameworks.addAll(getFrameworks(pod.dir));
        frameworks.addAll(getFrameworks(resolvedPods));
        List<String> frameworkPaths = new ArrayList<>();
        frameworkPaths.addAll(getFrameworkPaths(pod.dir));
        frameworkPaths.addAll(getFrameworkPaths(resolvedPods));

        Map<String, Object> context = createContext(manifestContext);
        context.put("ext", ImmutableMap.of("includes", includes, "frameworks", frameworks, "frameworkPaths", frameworkPaths));
        context.put("tgt", ExtenderUtil.getRelativePath(jobDirectory, o));
        context.put("moduleName", pod.moduleName);
        context.put("swiftPrimarySourceFile", swiftPrimarySourceFile);
        context.put("swiftSourceFiles", swiftSourceFilePaths);
        context.put("swiftVersion", "5");
        String command = templateExecutor.execute(this.platformConfig.compileSwiftCmd, context);
        // LOGGER.info("swift-frontend command to compile swift source file: " + command);
        commands.add(command);
        return o;
    }


    private File addCompileFileCpp_Internal(int index, File extDir, File src, Map<String, Object> manifestContext, String cmd, List<String> commands) throws IOException, InterruptedException, ExtenderException {
        List<String> includes = getIncludeDirs(extDir);

        File o = new File(buildDirectory, String.format("%s_%d.o", src.getName(), index));

        List<String> frameworks = new ArrayList<>();
        frameworks.addAll(getFrameworks(extDir));
        frameworks.addAll(getFrameworks(resolvedPods));
        List<String> frameworkPaths = new ArrayList<>();
        frameworkPaths.addAll(getFrameworkPaths(extDir));
        frameworkPaths.addAll(getFrameworkPaths(resolvedPods));

        Map<String, Object> context = createContext(manifestContext);
        context.put("src", ExtenderUtil.getRelativePath(jobDirectory, src));
        context.put("tgt", ExtenderUtil.getRelativePath(jobDirectory, o));
        context.put("ext", ImmutableMap.of("includes", includes, "frameworks", frameworks, "frameworkPaths", frameworkPaths));

        String command = templateExecutor.execute(cmd, context);
        commands.add(command);
        return o;
    }

    private File addCompileFileCppStatic(int index, File extDir, File src, Map<String, Object> manifestContext, List<String> commands) throws IOException, InterruptedException, ExtenderException {
        return addCompileFileCpp_Internal(index, extDir, src, manifestContext, this.platformConfig.compileCmd, commands);
    }

    private File addCompileFileCppShared(int index, File extDir, File src, Map<String, Object> manifestContext, List<String> commands) throws IOException, InterruptedException, ExtenderException {
        return addCompileFileCpp_Internal(index, extDir, src, manifestContext, this.platformConfig.compileCmdCXXSh, commands);
    }

    private File addCompileFileZigStatic(int index, File extDir, File src, Map<String, Object> manifestContext, List<String> commands) throws IOException, InterruptedException, ExtenderException {
        return addCompileFileCpp_Internal(index, extDir, src, manifestContext, this.platformConfig.zigCompileCmd, commands);
    }

    private File linkCppShared(File extBuildDir, List<String> objs, Map<String, Object> manifestContext, String cmd) throws IOException, InterruptedException, ExtenderException {
        String name = String.format(platformConfig.writeShLibPattern, manifestContext.get("extension_name"));
        File output = new File(extBuildDir, name);

        Map<String, Object> context = createContext(manifestContext);

        Map<String, Object> env = new HashMap<>();
        getProjectPaths(context, env);

        context.put("ext", env);
        context.put("src", objs);
        context.put("tgt", ExtenderUtil.getRelativePath(jobDirectory, output));

        String command = templateExecutor.execute(cmd, context);
        processExecutor.execute(command);

        return output;
    }

    private File compileMain(File maincpp, Map<String, Object> manifestContext) throws IOException, InterruptedException, ExtenderException {
        File o = uniqueTmpFile("main_tmp", ".o");
        Map<String, Object> context = createContext(manifestContext);
        context.put("extension_name", "ENGINE_MAIN");
        context.put("extension_name_upper", "ENGINE_MAIN");
        context.put("src", ExtenderUtil.getRelativePath(jobDirectory, maincpp));
        context.put("tgt", ExtenderUtil.getRelativePath(jobDirectory, o));
        String command = templateExecutor.execute(platformConfig.compileCmd, context);
        processExecutor.execute(command);
        return o;
    }

    private List<File> getExtensionManifestFiles() {
        return manifestConfigs.keySet().stream().map(k -> manifestFiles.get(k)).collect(Collectors.toList());
    }

    private List<File> getExtensionFolders() {
        return getExtensionManifestFiles().stream().map(File::getParentFile).collect(Collectors.toList());
    }

    private List<File> generateProtoCxxForEngine(File extDir, Map<String, Object> manifestContext, List<File> protoFiles) throws IOException, InterruptedException, ExtenderException {
        List<File> generated = new ArrayList<>();

        Map<String, Object> tmpcontext = createContext(manifestContext);

        if (platformConfig.protoEngineCxxCmd == null) {
            LOGGER.info("Not supporting .proto files");
            return generated;
        }

        LOGGER.info("Converting .proto to engine .cpp files for extension {}", extDir.getName());

        File extBuildDir = createDir(buildDirectory, extDir.getName());

        List<String> includes = getIncludeDirs(extDir);

        for (File protoFile : protoFiles) {

            String name = ExtenderUtil.switchExtension(protoFile.getName(), ".cpp");
            File tgtCpp = new File(extBuildDir, name);

            Map<String, Object> context = createContext(manifestContext);

            context.put("src", ExtenderUtil.getRelativePath(jobDirectory, protoFile));
            context.put("ext", ImmutableMap.of("includes", includes));
            context.put("out_dir", extBuildDir);
            String command = templateExecutor.execute(platformConfig.protoEngineCxxCmd, context);
            processExecutor.execute(command);

            LOGGER.info("Generated {}", tgtCpp);

            generated.add(tgtCpp);
        }
        return generated;
    }

    private List<File> generateProtoSrcForPlugin(File extDir, Map<String, Object> manifestContext, List<File> protoFiles, String language) throws IOException, InterruptedException, ExtenderException {
        List<File> generated = new ArrayList<>();

        Map<String, Object> tmpcontext = createContext(manifestContext);

        File extBuildDir = createDir(buildDirectory, extDir.getName());
        List<String> includes = getIncludeDirs(extDir);

        LOGGER.info("Converting .proto to plugin {} files for extension {}", language, extDir.getName());

        String suffix = "";
        if (language.equals("cpp"))
            suffix = ".pb.cc";
        else if (language.equals("java"))
            suffix = ".java";
        else if (language.equals("python"))
            suffix = "_pb2.py";
        else
            throw new ExtenderException("Proto output doesn't support language: " + (language!=null?language:"null"));

        for (File protoFile : protoFiles) {

            String name = ExtenderUtil.switchExtension(protoFile.getName(), suffix);
            File tgtFile = new File(extBuildDir, name);

            Map<String, Object> context = createContext(manifestContext);
            context.put("src", ExtenderUtil.getRelativePath(jobDirectory, protoFile));
            context.put("ext", ImmutableMap.of("includes", includes));
            context.put("out_dir", extBuildDir);
            context.put("language", language);

            // Adding the source folderpath, so the output relative path gets stripped and the output becomes "extBuildDir/proto_file_name.pb.cc"
            context.put("proto_path", ExtenderUtil.getRelativePath(jobDirectory, protoFile.getParentFile()));

            String command = templateExecutor.execute(platformConfig.protoPipelineCmd, context);
            processExecutor.execute(command);

            LOGGER.info("Generated {}", tgtFile);

            // TODO: The java files are named automatically, so this doesn't work
            if (!language.equals("java")) {
                generated.add(tgtFile);
            }
        }
        return generated;
    }

    private List<String> compileExtensionSourceFiles(File extDir, Map<String, Object> manifestContext, List<File> srcFiles) throws IOException, InterruptedException, ExtenderException {
        List<String> objs = new ArrayList<>();
        List<String> commands = new ArrayList<>();
        for (File src : srcFiles) {
            final int i = getAndIncreaseNameCount();

            File o = null;
            if (ExtenderUtil.matchesFile(src, platformConfig.sourceRe))
                o = addCompileFileCppStatic(i, extDir, src, manifestContext, commands);

            // Added in 1.4.9
            else if (platformConfig.zigSourceRe != null && ExtenderUtil.matchesFile(src, platformConfig.zigSourceRe))
                o = addCompileFileZigStatic(i, extDir, src, manifestContext, commands);

            if (o == null)
            {
                throw new ExtenderException(String.format("Source file '%s' didn't match a source builder.", src));
            }
            objs.add(ExtenderUtil.getRelativePath(jobDirectory, o));
        }
        ProcessExecutor.executeCommands(processExecutor, commands); // in parallel
        return objs;
    }

    // compile the source files of a pod and return a list of object files
    private List<String> compilePodSourceFiles(PodSpec pod, Map<String, Object> manifestContext) throws IOException, InterruptedException, ExtenderException {
        // clean up flags from context
        Map<String, Object> trimmedContext = ExtenderUtil.mergeContexts(manifestContext, new HashMap<>());
        trimmedContext.put("flags", new ArrayList<String>());

        // create contexts per supported language
        // add pod flags and defines
        Map<String, Object> podContextC = new HashMap<>();
        Map<String, Object> podContextCpp = new HashMap<>();
        Map<String, Object> podContextObjC = new HashMap<>();
        Map<String, Object> podContextObjCpp = new HashMap<>();
        Map<String, Object> podContextSwift = new HashMap<>();

        if (platform.contains("ios")) {
            podContextC.put("flags", new ArrayList<String>(pod.flags.ios.c));
            podContextC.put("defines", new ArrayList<String>(pod.defines.ios));
            podContextCpp.put("flags", new ArrayList<String>(pod.flags.ios.cpp));
            podContextCpp.put("defines", new ArrayList<String>(pod.defines.ios));
            podContextObjC.put("flags", new ArrayList<String>(pod.flags.ios.objc));
            podContextObjC.put("defines", new ArrayList<String>(pod.defines.ios));
            podContextObjCpp.put("flags", new ArrayList<String>(pod.flags.ios.objcpp));
            podContextObjCpp.put("defines", new ArrayList<String>(pod.defines.ios));
        }
        else if (platform.contains("osx")) {
            podContextC.put("flags", new ArrayList<String>(pod.flags.osx.c));
            podContextC.put("defines", new ArrayList<String>(pod.defines.osx));
            podContextCpp.put("flags", new ArrayList<String>(pod.flags.osx.cpp));
            podContextCpp.put("defines", new ArrayList<String>(pod.defines.osx));
            podContextObjC.put("flags", new ArrayList<String>(pod.flags.osx.objc));
            podContextObjC.put("defines", new ArrayList<String>(pod.defines.osx));
            podContextObjCpp.put("flags", new ArrayList<String>(pod.flags.osx.objcpp));
            podContextObjCpp.put("defines", new ArrayList<String>(pod.defines.osx));
        }

        // get the final contexts per supported language
        Map<String, Object> mergedContextWithPodsForC = ExtenderUtil.mergeContexts(trimmedContext, podContextC);
        Map<String, Object> mergedContextWithPodsForCpp = ExtenderUtil.mergeContexts(trimmedContext, podContextCpp);
        Map<String, Object> mergedContextWithPodsForObjC = ExtenderUtil.mergeContexts(trimmedContext, podContextObjC);
        Map<String, Object> mergedContextWithPodsForObjCpp = ExtenderUtil.mergeContexts(trimmedContext, podContextObjCpp);
        Map<String, Object> mergedContextWithPodsForSwift = ExtenderUtil.mergeContexts(trimmedContext, podContextSwift);

        // remove systemIncludes from objc and objc++
        // this is a bit crude but cocoapod builds do not provide any -isystem option and
        // it seems like the "{{env.SYSROOT}}/usr/include/c++/v1" set in build.yml is
        // causing problems when building objc code with -fmodules enabled
        // see https://github.com/defold/extender/issues/308
        mergedContextWithPodsForObjC.put("systemIncludes", new ArrayList<String>());
        mergedContextWithPodsForObjCpp.put("systemIncludes", new ArrayList<String>());
        mergedContextWithPodsForSwift.put("systemIncludes", new ArrayList<String>());


        List<String> objs = new ArrayList<>();

        if (!pod.swiftSourceFiles.isEmpty()) {
            // generate headers from swift files
            List<String> emitSwiftHeaderCommands = new ArrayList<>();
            emitSwiftHeaders(pod, mergedContextWithPodsForC, emitSwiftHeaderCommands);
            ProcessExecutor.executeCommands(processExecutor, emitSwiftHeaderCommands); // in parallel

            // generate swift module from swift files
            List<String> emitSwiftModuleCommands = new ArrayList<>();
            emitSwiftModule(pod, mergedContextWithPodsForC, emitSwiftModuleCommands);
            ProcessExecutor.executeCommands(processExecutor, emitSwiftModuleCommands); // in parallel

            // compile swift source files one by one
            List<String> compileSwiftCommands = new ArrayList<>();
            for (File src : pod.swiftSourceFiles) {
                final int i = getAndIncreaseNameCount();
                File o = addCompileFileSwift(pod, i, src, mergedContextWithPodsForC, compileSwiftCommands);
                objs.add(ExtenderUtil.getRelativePath(jobDirectory, o));
            }
            ProcessExecutor.executeCommands(processExecutor, compileSwiftCommands); // in parallel
        }

        List<String> commands = new ArrayList<>();
        for (File src : pod.sourceFiles) {
            String extension = FilenameUtils.getExtension(src.getAbsolutePath());
            final int i = getAndIncreaseNameCount();
            File o = null;
            // use the correct context depending on the source file language
            if (extension.equals("c")) {
                o = addCompileFileCppStatic(i, pod.dir, src, mergedContextWithPodsForC, commands);
            }
            else if (extension.equals("m")) {
                o = addCompileFileCppStatic(i, pod.dir, src, mergedContextWithPodsForObjC, commands);
            }
            else if (extension.equals("mm")) {
                o = addCompileFileCppStatic(i, pod.dir, src, mergedContextWithPodsForObjCpp, commands);
            }
            else {
                o = addCompileFileCppStatic(i, pod.dir, src, mergedContextWithPodsForCpp, commands);
            }
            objs.add(ExtenderUtil.getRelativePath(jobDirectory, o));
        }
        ProcessExecutor.executeCommands(processExecutor, commands); // in parallel

        return objs;
    }

    // build the source files of each resolved pod file into a library
    private List<File> buildPods() throws IOException, InterruptedException, ExtenderException {
        List<File> outputFiles = new ArrayList<>();
        if (resolvedPods == null) {
            LOGGER.info("buildPods - no pods");
            return outputFiles;
        }

        LOGGER.info("buildPods");
        for (PodSpec pod : resolvedPods.pods) {
            // The source files of each pod will be compiled and built as a library.
            // We use the same mechanism as when building the extension and create a
            // manifest context for each pod
            Map<String, Object> manifestContext = new HashMap<>();
            manifestContext = ExtenderUtil.mergeContexts(manifestContext, this.platformConfig.context);
            manifestContext = ExtenderUtil.mergeContexts(manifestContext, this.platformVariantConfig.context);
            manifestContext.put("extension_name", pod.name);
            manifestContext.put("extension_name_upper", pod.name.toUpperCase());
            manifestContext.put("osMinVersion", resolvedPods.platformMinVersion);

            // Compile pod source files
            List<String> objs = compilePodSourceFiles(pod, manifestContext);
            if (!objs.isEmpty()) {
                // Create c++ library
                File lib = createBuildFile(String.format(platformConfig.writeLibPattern, manifestContext.get("extension_name") + "_" + getNameUUID()));
                Map<String, Object> context = createContext(manifestContext);
                context.put("tgt", lib);
                context.put("objs", objs);
                String command = templateExecutor.execute(platformConfig.libCmd, context);
                processExecutor.execute(command);
            }
        }

        LOGGER.info("buildPods - adding framework resource to build output");
        File resourcesBuildDir = new File(buildDirectory, "resources");
        resourcesBuildDir.mkdir();
        List<String> resources = resolvedPods.getAllPodResources(platform);
        for (String resource : resources) {
            File resourceFile = new File(resource);
            if (resourceFile.isFile()) {
                File resourceDestFile = new File(resourcesBuildDir, resourceFile.getName());
                Files.copy(resourceFile.toPath(), resourceDestFile.toPath());
                outputFiles.add(resourceDestFile);
            }
            else {
                File resourceDestDir = new File(resourcesBuildDir, resourceFile.getName());
                FileUtils.copyDirectory(resourceFile, resourceDestDir);
                outputFiles.add(resourceDestDir);
            }
        }


        LOGGER.info("buildPods - adding dynamic frameworks to build output");
        File frameworksBuildDir = new File(buildDirectory, "frameworks");
        frameworksBuildDir.mkdir();

        List<String> paths = getFrameworkPaths(resolvedPods.frameworksDir);
        for (String path : paths) {
            File[] frameworks = new File(path).listFiles();
            for (File framework : frameworks) {
                if (FrameworkUtil.isDynamicallyLinked(framework)) {
                    // copy framework and filter out certain files and folders
                    LOGGER.info("buildPods - adding " + framework.getName());
                    File frameworkDestDir = new File(frameworksBuildDir, framework.getName());
                    FileUtils.copyDirectory(framework, frameworkDestDir, new FileFilter() {
                        @Override
                        public boolean accept(File pathname) {
                            String name = pathname.getName();
                            return !name.equals("Headers")
                                && !name.equals("Modules");
                        }
                    });
                    outputFiles.add(frameworkDestDir);
                }
            }
        }

        return outputFiles;
    }

    private List<File> buildExtensionInternal(File manifest, Map<String, Object> manifestContext, List<File> srcDirs, File libraryOut) throws IOException, InterruptedException, ExtenderException {
        LOGGER.info("buildExtension");

        File extDir = manifest.getParentFile();

        // Gather all the source files
        List<File> srcFiles = ExtenderUtil.listFiles(srcDirs, platformConfig.sourceRe);

        // Added in 1.4.9
        if (platformConfig.zigSourceRe != null)
            srcFiles.addAll(ExtenderUtil.listFiles(srcDirs, platformConfig.zigSourceRe));

        if (srcFiles.isEmpty()) {
            throw new ExtenderException(String.format("%s:1: error: Extension has no source!", ExtenderUtil.getRelativePath(this.uploadDirectory, manifest) ));
        }

        // Generate C++ files first (output into the source folder)
        List<File> protoFiles = ExtenderUtil.listFiles(srcDirs, PROTO_RE);
        List<File> generated = generateProtoCxxForEngine(extDir, manifestContext, protoFiles);
        if (!protoFiles.isEmpty() && generated.isEmpty()) {
            throw new ExtenderException(String.format("%s:1: error: Protofiles didn't generate any output engine cpp files!", ExtenderUtil.getRelativePath(this.uploadDirectory, protoFiles.get(0)) ));
        }
        srcFiles.addAll(generated);

        // Compile extension source files
        List<String> objs = new ArrayList<>();
        objs.addAll(compileExtensionSourceFiles(extDir, manifestContext, srcFiles));

        // Create c++ library
        File lib;
        if (libraryOut != null)
            lib = libraryOut;
        else
            lib = createBuildFile(String.format(platformConfig.writeLibPattern, manifestContext.get("extension_name") + "_" + getNameUUID()));

        List<File> outputFiles = new ArrayList<>();
        outputFiles.add(lib);

        Map<String, Object> context = createContext(manifestContext);
        context.put("tgt", lib);
        context.put("objs", objs);
        String command = templateExecutor.execute(platformConfig.libCmd, context);
        processExecutor.execute(command);

        return outputFiles;
    }

    private List<File> buildExtension(File manifest, Map<String, Object> manifestContext) throws IOException, InterruptedException, ExtenderException {
        LOGGER.info("buildExtension");
        File extDir = manifest.getParentFile();
        List<File> srcDirs = new ArrayList<>();
        srcDirs.add(new File(extDir, FOLDER_COMMON_SRC));
        srcDirs.add(new File(extDir, FOLDER_ENGINE_SRC));
        return buildExtensionInternal(manifest, manifestContext, srcDirs, null);
    }

    private List<File> buildLibrary(File manifest, Map<String, Object> manifestContext) throws IOException, InterruptedException, ExtenderException {
        LOGGER.info("buildLibrary");
        List<File> srcDirs = new ArrayList<>();
        srcDirs.add(uploadDirectory);
        String libName = String.format(platformConfig.writeLibPattern, manifestContext.get("extension_name"));
        return buildExtensionInternal(manifest, manifestContext, srcDirs, new File(buildDirectory, libName));
    }

    private List<File> buildPipelineExtension(File manifest, Map<String, Object> manifestContext) throws IOException, InterruptedException, ExtenderException {
        if (platformConfig.protoEngineCxxCmd == null) {
            LOGGER.info("This SDK version doesn't support compiling plugins!");
            return new ArrayList<File>();
        }

        File extDir = manifest.getParentFile();
        File extBuildDir = createDir(buildDirectory, extDir.getName());
        extBuildDir.mkdir();
        File[] srcDirs = { new File(extDir, FOLDER_COMMON_SRC), new File(extDir, FOLDER_PLUGIN_SRC) };

        List<File> protoFiles = ExtenderUtil.listFiles(srcDirs, PROTO_RE);

        List<File> outputFiles = new ArrayList<>();

        // ***************************************************************************
        // C++
        {
            List<File> srcFiles = ExtenderUtil.listFiles(srcDirs, platformConfig.sourceRe);

            if (srcFiles.isEmpty()) {
                LOGGER.info("No C++ source found for plugin. Skipping {}", extDir);
            } else {
                // We leave the C++ protobuf support for a later date
                // since the Java support is fully adequate, and also provides better error handling.
                //List<File> generatedFiles = generateProtoSrcForPlugin(extDir, manifestContext, protoFiles, "cpp");
                List<File> generatedFiles = new ArrayList<>();

                srcFiles.addAll(generatedFiles);

                List<String> objs = new ArrayList<>();
                List<String> commands = new ArrayList<>();

                // Compile C++ source into object files
                int i = getAndIncreaseNameCount();
                for (File src : srcFiles) {
                    File o = addCompileFileCppShared(i, extDir, src, manifestContext, commands);
                    objs.add(ExtenderUtil.getRelativePath(jobDirectory, o));
                    i++;
                }
                ProcessExecutor.executeCommands(processExecutor, commands); // in parallel

                File sharedLibrary = linkCppShared(extBuildDir, objs, manifestContext, platformConfig.linkCmdCXXSh);
                outputFiles.add(sharedLibrary);
            }

            // produce a shared library
        }

        // ***************************************************************************
        // Java
        {
            List<File> srcFiles = ExtenderUtil.listFiles(srcDirs, platformConfig.javaSourceRe);

            if (srcFiles.isEmpty()) {
                LOGGER.info("No Java source found for plugin. Skipping {}", extDir);
            } else {
                generateProtoSrcForPlugin(extDir, manifestContext, protoFiles, "java");
                List<File> generatedFiles = ExtenderUtil.listFiles(extBuildDir, platformConfig.javaSourceRe);

                srcFiles.addAll(generatedFiles);

                File sourcesListFile = new File(extBuildDir, "sources.txt");
                sourcesListFile.createNewFile();

                // Write source paths to sources.txt
                for (File javaFile : srcFiles) {
                    FileUtils.writeStringToFile(sourcesListFile, javaFile.getAbsolutePath() + "\n", Charset.defaultCharset(), true);
                }

                File classesDir = new File(extBuildDir, "classes");
                classesDir.delete();
                classesDir.mkdir();

                {
                    Map<String, Object> context = createContext(manifestContext);

                    String classPath = classesDir.getAbsolutePath();
                    List<String> extraPaths = ExtenderUtil.getStringList(context, "javaPipelineClasspath");
                    for (String path : extraPaths) {
                        classPath += ":" + path;
                    }

                    context.put("classesDir", classesDir.getAbsolutePath());
                    context.put("classPath", classPath);
                    context.put("sourcesListFile", sourcesListFile.getAbsolutePath());
                    String command = templateExecutor.execute(platformConfig.javacCmd, context);
                    processExecutor.execute(command);
                }

                // Collect all classes into a Jar file
                Map<String, Object> context = createContext(manifestContext);
                File outputJar = new File(extBuildDir, String.format("plugin%s.jar", manifestContext.get("extension_name")));

                context.put("outputJar", outputJar.getAbsolutePath());
                context.put("classesDir", classesDir.getAbsolutePath());
                String command = templateExecutor.execute(platformConfig.jarCmd, context);
                processExecutor.execute(command);

                outputFiles.add(outputJar);
            }
        }

        // ***************************************************************************
        // Python
        {
            List<File> srcFiles = ExtenderUtil.listFiles(srcDirs, platformConfig.sourceRe);

            if (!protoFiles.isEmpty()) {
                List<File> generatedFiles = generateProtoSrcForPlugin(extDir, manifestContext, protoFiles, "python");
                outputFiles.addAll(generatedFiles);
            }
        }

        return outputFiles;
    }

    private List<String> patchLibs(List<String> libs) {
        if (libs == null) {
            return new ArrayList<>();
        }
        return libs;
    }

    private void getProjectPaths(Map<String, Object> mainContext, Map<String, Object> env) throws ExtenderException {
        List<String> extLibs = new ArrayList<>();
        List<String> extShLibs = new ArrayList<>();
        List<String> extLibPaths = new ArrayList<>(Arrays.asList(buildDirectory.toString()));
        List<String> extFrameworks = new ArrayList<>();
        List<String> extFrameworkPaths = new ArrayList<>(Arrays.asList(buildDirectory.toString()));
        List<String> extJsLibs = new ArrayList<>();

        extShLibs.addAll(ExtenderUtil.collectFilesByName(buildDirectory, platformConfig.shlibRe));
        extLibs.addAll(ExtenderUtil.collectFilesByName(buildDirectory, platformConfig.stlibRe));

        if (resolvedPods != null) {
            extFrameworks.addAll(getFrameworks(resolvedPods));
            extFrameworkPaths.addAll(getFrameworkPaths(resolvedPods));
            extLibs.addAll(getFrameworkStaticLibs(resolvedPods));
            extLibPaths.addAll(getFrameworkStaticLibPaths(resolvedPods));
        }

        for (File extDir : this.extDirs) {
            File libDir = new File(extDir, "lib" + File.separator + this.platform); // e.g. arm64-ios

            if (libDir.exists()) {
                extLibPaths.add(libDir.toString());
                extFrameworkPaths.add(libDir.toString());
            }

            extShLibs.addAll(ExtenderUtil.collectFilesByName(libDir, platformConfig.shlibRe));
            extLibs.addAll(ExtenderUtil.collectFilesByName(libDir, platformConfig.stlibRe));
            extJsLibs.addAll(ExtenderUtil.collectFilesByPath(libDir, JS_RE));

            extFrameworks.addAll(getFrameworks(extDir));

            String[] platformParts = this.platform.split("-");
            if (platformParts.length == 2) {
                File libCommonDir = new File(extDir, "lib" + File.separator + platformParts[1]); // e.g. ios

                if (libCommonDir.exists()) {
                    extLibPaths.add(libCommonDir.toString());
                    extFrameworkPaths.add(libCommonDir.toString());
                }

                extShLibs.addAll(ExtenderUtil.collectFilesByName(libCommonDir, platformConfig.shlibRe));
                extLibs.addAll(ExtenderUtil.collectFilesByName(libCommonDir, platformConfig.stlibRe));
                extJsLibs.addAll(ExtenderUtil.collectFilesByPath(libCommonDir, JS_RE));
                extFrameworkPaths.addAll(getFrameworkPaths(extDir));
            }
        }

        extShLibs = ExtenderUtil.pruneItems( extShLibs, ExtenderUtil.getStringList(mainContext, "includeDynamicLibs"), ExtenderUtil.getStringList(mainContext, "excludeDynamicLibs"));
        extLibs = ExtenderUtil.pruneItems( extLibs, ExtenderUtil.getStringList(mainContext, "includeLibs"), ExtenderUtil.getStringList(mainContext, "excludeLibs"));
        extJsLibs = ExtenderUtil.pruneItems( extJsLibs, ExtenderUtil.getStringList(mainContext, "includeJsLibs"), ExtenderUtil.getStringList(mainContext, "excludeJsLibs"));

        // This is a workaround due to a linker crash when the helpshift "Support" library is in front of the Facebook extension (not certain of this though)
        Collections.sort(extLibs, Collections.reverseOrder());

        env.put("libs", extLibs);
        env.put("dynamicLibs", extShLibs);
        env.put("libPaths", extLibPaths);

        if (ExtenderUtil.isAppleTarget(this.platform)) {
            env.put("frameworks", extFrameworks);
            env.put("frameworkPaths", extFrameworkPaths);
        }

        if (ExtenderUtil.isWebTarget(this.platform)) {
            env.put("jsLibs", extJsLibs);
        }
    }

    private List<File> linkEngine(List<String> symbols, Map<String, Object> linkContext, File resourceFile) throws IOException, InterruptedException, ExtenderException {
        LOGGER.info("Linking engine");

        File maincpp = new File(buildDirectory , "main.cpp");

        List<String> extSymbols = new ArrayList<>();
        extSymbols.addAll(symbols);

        Map<String, Object> mainContext = createContext(linkContext);

        extSymbols = ExtenderUtil.pruneItems( extSymbols, ExtenderUtil.getStringList(mainContext, "includeSymbols"), ExtenderUtil.getStringList(mainContext, "excludeSymbols") );
        mainContext.put("symbols", ExtenderUtil.pruneItems( ExtenderUtil.getStringList(mainContext, "symbols"), ExtenderUtil.getStringList(mainContext, "includeSymbols"), ExtenderUtil.getStringList(mainContext, "excludeSymbols")));
        mainContext.put("ext", ImmutableMap.of("symbols", extSymbols));

        String main = templateExecutor.execute(config.main, mainContext);
        FileUtils.writeStringToFile(maincpp, main);

        File mainObject = compileMain(maincpp, linkContext);

        String writeExePattern = platformConfig.writeExePattern;
        File exe = new File(buildDirectory, writeExePattern);

        List<String> objects = new ArrayList<>();
        objects.add(ExtenderUtil.getRelativePath(jobDirectory, mainObject));
        if (resourceFile != null) { // For Win32 targets
            objects.add(ExtenderUtil.getRelativePath(jobDirectory, resourceFile));
        }

        Map<String, Object> env = new HashMap<>();
        getProjectPaths(mainContext, env);

        Map<String, Object> context = createContext(linkContext);
        context.put("src", objects);
        context.put("tgt", ExtenderUtil.getRelativePath(jobDirectory, exe));
        context.put("ext", env);

        // WINE->clang transition pt1: in the transition period from link.exe -> lld, we want to make sure we can write "foo" as opposed to "foo.lib"
        context.put("libs", patchLibs((List<String>) context.get("libs")));
        context.put("extLibs", patchLibs((List<String>) context.get("extLibs")));
        context.put("engineLibs", patchLibs((List<String>) context.get("engineLibs")));

        List<String> commands = platformConfig.linkCmds; // Used by e.g. the Switch platform

        if (platformConfig.linkCmds == null) {
            commands = new ArrayList<>();
            commands.add(platformConfig.linkCmd);
        }

        for (String template : commands) {
            String command = templateExecutor.execute(template, context);

            // WINE->clang transition pt2: Replace any redundant ".lib.lib"
            command = command.replace(".lib.lib", ".lib").replace(".Lib.lib", ".lib").replace(".LIB.lib", ".lib");

            processExecutor.execute(command);
        }

        // Extract symbols
        if (this.withSymbols) {
            String symbolCmd = platformConfig.symbolCmd;
            if (symbolCmd != null && !symbolCmd.equals("")) {
                Map<String, Object> symbolContext = createContext(linkContext);
                symbolContext.put("src", ExtenderUtil.getRelativePath(jobDirectory, exe));

                symbolCmd = templateExecutor.execute(symbolCmd, symbolContext);
                processExecutor.execute(symbolCmd);
            }
        }

        // Collect output/binaries
        String zipContentPattern = platformConfig.zipContentPattern;
        if (zipContentPattern == null) {
            zipContentPattern = writeExePattern;
        }

        // If we wish to grab the symbols, prepend the pattern (E.g. to "(.*dSYM)|(dmengine)")
        if (this.withSymbols) {
            String symbolsPattern = platformConfig.symbolsPattern;
            if (!symbolsPattern.equals("")) {
                zipContentPattern = symbolsPattern + "|" + zipContentPattern;
            }
        }

        final Pattern p = Pattern.compile(zipContentPattern);
        List<File> outputFiles = Arrays.asList(buildDirectory.listFiles(new FileFilter(){
            @Override
            public boolean accept(File file) {
                return p.matcher(file.getName()).matches();
            }
        }));

        return outputFiles;
    }

    private List<File> getAndroidAssetsFolders(String platform) {
        List<File> assetDirs = new ArrayList<>();
        assetDirs.addAll(gradlePackages.stream()
                                         .map(f -> new File(f, "assets"))
                                         .collect(Collectors.toList()));
        return assetDirs.stream()
                            .filter(f -> f.isDirectory())
                            .collect(Collectors.toList());
    }

    private List<File> getAndroidJniFolders(String platform) {
        List<File> jniDirs = new ArrayList<>();
        jniDirs.addAll(gradlePackages.stream()
                                         .map(f -> new File(f, "jni"))
                                         .collect(Collectors.toList()));
        return jniDirs.stream()
                            .filter(f -> f.isDirectory())
                            .collect(Collectors.toList());
    }

    private List<String> getAndroidResourceFolders(String platform) {
        // New feature from 1.2.165
        File packageDir = new File(uploadDirectory, "packages");
        if (!packageDir.exists()) {
            return new ArrayList<>();
        }
        List<File> packageDirs = new ArrayList<>();

        for (File dir : packageDir.listFiles(File::isDirectory)) {
            File resDir = ExtenderUtil.getAndroidResourceFolder(dir);
            if (resDir != null) {
                packageDirs.add(resDir);
            }
        }

        // find all extension directories
        for (File extensionFolder : getExtensionFolders()) {
            for (String platformAlt : ExtenderUtil.getPlatformAlternatives(platform)) {
                File f = new File(extensionFolder, "res/" + platformAlt + "/res");
                if (!f.exists() || !f.isDirectory()) {
                    continue;
                }
                File resDir = ExtenderUtil.getAndroidResourceFolder(f);
                if (resDir != null) {
                    packageDirs.add(resDir);
                }
            }
        }

        // we add all packages (even non-directories)
        packageDirs.addAll(gradlePackages.stream()
                                         .map(f -> new File(f, "res"))
                                         .collect(Collectors.toList()));

        return packageDirs.stream()
                        .filter(f -> f.isDirectory())
                        .map(File::getAbsolutePath)
                        .collect(Collectors.toList());
    }


    private static File createDir(File parent, String child) throws IOException {
        File dir = new File(parent, child);
        dir.mkdirs();
        return dir;
    }
    private static File createDir(String parent, String child) throws IOException {
        return createDir(new File(parent), child);
    }

    /**
    * Compile android resources into "flat" files
    * https://developer.android.com/studio/build/building-cmdline#compile_and_link_your_apps_resources
    */
    private File compileAndroidResources(List<String> resourceDirectories, Map<String, Object> mergedAppContext) throws ExtenderException {
        LOGGER.info("Compiling Android resources");

        File outputDirectory = new File(buildDirectory, "compiledResources");
        outputDirectory.mkdirs();
        try {
            Map<String, Object> context = createContext(mergedAppContext);
            for (String resDir : resourceDirectories) {
                // /tmp/.gradle/unpacked/android.arch.lifecycle-livedata-1.1.1.aar/res
                File resourceDirectory = new File(resDir);
                // android.arch.lifecycle-livedata-1.1.1.aar
                String packageName = resourceDirectory.getParentFile().getName();

                // we compile the package resources to one output directory per package
                File packageDirectoryOut = createDir(outputDirectory, packageName);
                context.put("outputDirectory", packageDirectoryOut.getAbsolutePath());

                // iterate over the directories in the res directory of the package
                for (File resourceTypeDir : resourceDirectory.listFiles(File::isDirectory)) {
                    // compile each resource file to a .flat file
                    for (File resourceFile : resourceTypeDir.listFiles()) {
                        context.put("resourceFile", resourceFile.getAbsolutePath());

                        String command = templateExecutor.execute(platformConfig.aapt2compileCmd, context);
                        processExecutor.execute(command);
                    }
                }
            }

        } catch (IOException | InterruptedException e) {
            throw new ExtenderException(e, processExecutor.getOutput());
        }

        return outputDirectory;
    }

    private Map<String, File> linkAndroidResources(File compiledResourcesDir, Map<String, Object> mergedAppContext) throws ExtenderException {
        LOGGER.info("Linking Android resources");

        Map<String, Object> context = createContext(mergedAppContext);
        Map<String, File> files = new HashMap<>();

        try {
            // write compiled resource list to a txt file
            StringBuilder sb = new StringBuilder();
            for (File packageDir : compiledResourcesDir.listFiles(File::isDirectory)) {
                for (File file : packageDir.listFiles()) {
                    if (file.getAbsolutePath().endsWith(".flat")) {
                        sb.append(file.getAbsolutePath() + " ");
                    }
                }
            }
            File resourceList = new File(buildDirectory, "compiledresources.txt");
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(resourceList))) {
                writer.write(sb.toString());
            }
            context.put("resourceListFile", resourceList.getAbsolutePath());

            // extra packages
            List<String> extraPackages = getExtraPackagesFromGradlePackages();
            if (mergedAppContext.containsKey("aaptExtraPackages")) {
                extraPackages.addAll((List<String>)mergedAppContext.get("aaptExtraPackages"));
            }
            if (!extraPackages.isEmpty()) {
                String extraPackagesString = String.join(":", extraPackages);
                context.put("extraPackages", extraPackagesString);
                LOGGER.info("Extra packages {}", extraPackagesString);
            }

            File manifestFile = new File(buildDirectory, MANIFEST_ANDROID);
            context.put("manifestFile", manifestFile.getAbsolutePath());

            File resourceIdsFile = new File(buildDirectory, "resource_ids.txt");
            context.put("resourceIdsFile", resourceIdsFile.getAbsolutePath());

            File outputJavaDirectory = createDir(buildDirectory, "out_java");
            context.put("outJavaDirectory", outputJavaDirectory.getAbsolutePath());

            File outApkFile = new File(buildDirectory, "compiledresources.apk");
            context.put("outApkFile", outApkFile.getAbsolutePath());

            files.put("resourceIdsFile", resourceIdsFile);
            files.put("outApkFile", outApkFile);
            files.put("outJavaDirectory", outputJavaDirectory);

            String command = templateExecutor.execute(platformConfig.aapt2linkCmd, context);
            processExecutor.execute(command);
        }
        catch (IOException | InterruptedException e) {
            throw new ExtenderException(e, processExecutor.getOutput());
        }

        return files;
    }

    // https://manpages.debian.org/jessie/aapt/aapt.1.en.html
    private File generateRJava(List<String> resourceDirectories, Map<String, Object> mergedAppContext) throws ExtenderException {
        File rJavaDir = new File(uploadDirectory, "_app/rjava");
        if (rJavaDir.exists()) {
            LOGGER.info("Using pre-existing R.java files");
            return rJavaDir;
        }

        LOGGER.info("Generating R.java files");

        // From 1.2.165
        rJavaDir = new File(buildDirectory, "rjava");
        try {
            rJavaDir.mkdir();

            if (platformConfig.rjavaCmd == null) {
                LOGGER.info("No rjavaCmd found. Skipping");
                return rJavaDir;
            }

            Map<String, Object> context = createContext(mergedAppContext);
            if (mergedAppContext.containsKey("aaptExtraPackages")) {
                context.put("extraPackages", String.join(":", (List<String>)mergedAppContext.get("aaptExtraPackages")));
            }

            // Use the merged manifest
            File manifestFile = new File(buildDirectory, MANIFEST_ANDROID);
            context.put("manifestFile", manifestFile.getAbsolutePath());
            context.put("outputDirectory", rJavaDir.getAbsolutePath());
            context.put("resourceDirectories", resourceDirectories);

            String command = templateExecutor.execute(platformConfig.rjavaCmd, context);
            processExecutor.execute(command);

        } catch (IOException | InterruptedException e) {
            throw new ExtenderException(e, processExecutor.getOutput());
        }

        return rJavaDir;
    }

    private File buildRJar(File rJavaDir) throws ExtenderException {
        try {
            // Collect all *.java files
            Collection<File> files = FileUtils.listFiles(rJavaDir, new RegexFileFilter(platformConfig.javaSourceRe), DirectoryFileFilter.DIRECTORY);

            if (!files.isEmpty()) {
                LOGGER.info("Building Android resources (R.java).");

                // Create temp directories
                File tmpDir = uniqueTmpFile("tmp", "rjava");
                tmpDir.delete();
                tmpDir.mkdir();

                // <tmpDir>/classes - Output folder for compiling R.java files into classes
                File classesDir = new File(tmpDir, "classes");
                classesDir.delete();
                classesDir.mkdir();

                // <tmpDir>/R.jar - Jar file containing all R.classes
                File outputJar = new File(tmpDir, "R.jar");

                // <tmpDir>/sources.txt - Text file listing all R.java source paths, used by javac command
                File sourcesListFile = new File(tmpDir, "sources.txt");
                sourcesListFile.createNewFile();

                // Write source paths to sources.txt
                for (File javaFile : files) {
                    FileUtils.writeStringToFile(sourcesListFile, javaFile.getAbsolutePath() + "\n", Charset.defaultCharset(), true);
                }

                // Compile sources into class files
                Map<String, Object> context = createContext(mergedAppContext);
                context.put("classesDir", classesDir.getAbsolutePath());
                context.put("classPath", classesDir.getAbsolutePath());
                context.put("sourcesListFile", sourcesListFile.getAbsolutePath());
                String command = templateExecutor.execute(platformConfig.javacCmd, context);
                processExecutor.execute(command);

                // Collect all classes into a Jar file
                context = createContext(mergedAppContext);
                context.put("outputJar", outputJar.getAbsolutePath());
                context.put("classesDir", classesDir.getAbsolutePath());
                command = templateExecutor.execute(platformConfig.jarCmd, context);
                processExecutor.execute(command);

                return outputJar;
            }
        } catch (IOException | InterruptedException e) {
            throw new ExtenderException(e, processExecutor.getOutput());
        }

        return null;
    }

    // returns:
    //   a pair of the .jar file and a list of all of the proguard files that were found
    //   in the manifests/android folder. If proGuard isn't supported (i.e old build.yml), a null pointer will be returned.
    private Map.Entry<File, ProGuardContext> buildJavaExtension(File manifest, Map<String, Object> manifestContext, File rJar) throws ExtenderException {
        try {
            // Collect all Java source files
            File extDir = manifest.getParentFile();
            File srcDir = new File(extDir, "src");
            Collection<File> javaSrcFiles = new ArrayList<>();
            if (srcDir.isDirectory()) {
                javaSrcFiles = FileUtils.listFiles(srcDir, null, true);
                javaSrcFiles = ExtenderUtil.filterFiles(javaSrcFiles, platformConfig.javaSourceRe);
            }

            File manifestDir = new File(extDir, "manifests/android/");
            Collection<File> proGuardSrcFiles = new ArrayList<>();
            if (manifestDir.isDirectory()) {
                proGuardSrcFiles = FileUtils.listFiles(manifestDir, null, true);
                proGuardSrcFiles = ExtenderUtil.filterFiles(proGuardSrcFiles, platformConfig.proGuardSourceRe);
            }
            // We want to collect ProGuards files even if we don't have java or jar files for the build
            // because it's possible that this extention depends on some base extension
            if (javaSrcFiles.size() == 0 && proGuardSrcFiles.size() == 0) {
                LOGGER.info("No Java sources. Skipping");
                return null;
            }

            LOGGER.info("Building Java sources with extension source {}", uploadDirectory);

            ProGuardContext proGuardContext = new ProGuardContext();

            // * If we found proguard files, we add all of them to the proguard context
            //   for this extension. It is implied that if there are .pro files present,
            //   then the extension developer is responsible to make sure the correct classes and symbols are kept.
            // * However, if no proguard files were found, we need to add all potential jar files
            //   from the extension lib folder into the context so that we can set them as -libraryjar when
            //   running proguard.
            if (proGuardSrcFiles.size() > 0) {
                for (File pFile : proGuardSrcFiles) {
                    proGuardContext.proGuardFiles.add(pFile.getAbsolutePath());
                }
            } else {
                // Get extension supplied Jar libraries
                List<String> extJars = getExtensionLibJars(extDir);
                proGuardContext.libraryJars = new ArrayList<>(extJars);
            }

            // Create temp working directory, which will include;
            // * classes/    - Output directory of javac compilation
            // * sources.txt - Text file with list of Java sources
            // * output.jar  - Resulting Jar file with all compiled Java classes
            // The temporary working directory should be removed when done.
            File tmpDir = uniqueTmpFile("tmp", "javac");
            tmpDir.delete();
            tmpDir.mkdir();

            if (javaSrcFiles.size() == 0) {
                // If we collect proguard files without building `jar`
                // we have to use special name to avoid "File doesn't exist"
                // error. We check it later with `(.*)/proguard_files_without_jar` pattern.
                File proguardFakeJar = new File(tmpDir, "proguard_files_without_jar");
                return new AbstractMap.SimpleEntry<File, ProGuardContext>(proguardFakeJar, proGuardContext);
            }

            File classesDir = new File(tmpDir, "classes");
            classesDir.delete();
            classesDir.mkdir();

            File sourcesListFile = new File(tmpDir, "sources.txt");
            sourcesListFile.createNewFile();

            File outputJar = new File(tmpDir, "output.jar");

            // Add all Java file paths to the sources.txt file
            for (File javaSrc : javaSrcFiles) {
                FileUtils.writeStringToFile(sourcesListFile, javaSrc.getAbsolutePath() + "\n", Charset.defaultCharset(), true);
            }

            // Compile sources into class files
            Map<String, Object> context = createContext(manifestContext);
            context.put("classesDir", classesDir.getAbsolutePath());
            String classPath = srcDir.getAbsolutePath() + ":" + classesDir.getAbsolutePath();
            if (rJar != null) {
                classPath += ":" + rJar.getAbsolutePath();
            }

            // We want to include all jars from all extensions to have the possibility
            // of creation base (core) extensions that contain only jars.
            // For example, firebase-core for firebase-analytics and firebase-push
            List<String> allLibJars = getAllExtensionsLibJars();
            for (String jarPath : allLibJars) {
                classPath += ":" + jarPath;
            }

            context.put("classPath", classPath);
            context.put("sourcesListFile", sourcesListFile.getAbsolutePath());
            String command = templateExecutor.execute(platformConfig.javacCmd, context);
            processExecutor.execute(command);

            // Collect all classes into a Jar file
            context = createContext(manifestContext);
            context.put("outputJar", outputJar.getAbsolutePath());
            context.put("classesDir", classesDir.getAbsolutePath());
            command = templateExecutor.execute(platformConfig.jarCmd, context);
            processExecutor.execute(command);

            return new AbstractMap.SimpleEntry<File, ProGuardContext>(outputJar, proGuardContext);

        } catch (IOException | InterruptedException e) {
            throw new ExtenderException(e, processExecutor.getOutput());
        }
    }

    // returns:
    //   a file path to the built jar as well as a (potential) list
    //   of proguard files that should be applied to the final application jar.
    //   If the collection contains zero entries, then the jar should be treated as a library jar,
    //   which means that it should not be obfuscated or optimized.
    private Map<String,ProGuardContext> buildJava(File rJar) throws ExtenderException {
        Map<String,ProGuardContext> builtJars = new HashMap<>();

        if (rJar != null) {
            builtJars.put(rJar.getAbsolutePath(), null);
        }

        List<String> symbols = getSortedKeys(manifestConfigs.keySet());
        for (String extensionSymbol : symbols) {
            Map<String, Object> extensionContext = manifestConfigs.get(extensionSymbol);
            File extensionManifest = manifestFiles.get(extensionSymbol);

            Map.Entry<File, ProGuardContext> javaExtensionsEntry = buildJavaExtension(extensionManifest, extensionContext, rJar);
            if (javaExtensionsEntry != null) {
                builtJars.put(javaExtensionsEntry.getKey().getAbsolutePath(), javaExtensionsEntry.getValue());
            }
        }
        return builtJars;
    }

    // arguments:
    //   extensionJarMap - a mapping from a jar file to a list of its corresponding proGuard files
    // returns:
    //   all jar files from each extension, as well as the engine defined jar files
    private List<String> getAllJars(Map<String,ProGuardContext> extensionJarMap) throws ExtenderException {
        List<String> includeJars = ExtenderUtil.getStringList(mergedAppContext, "includeJars");
        List<String> excludeJars = ExtenderUtil.getStringList(mergedAppContext, "excludeJars");

        List<String> extensionJars = getAllExtensionsLibJars();

        for (Map.Entry<String,ProGuardContext> extensionJar : extensionJarMap.entrySet()) {
            extensionJars.add(extensionJar.getKey());
        }

        Map<String, Object> context = createContext(mergedAppContext);
        List<String> allJars = ExtenderUtil.pruneItems( (List<String>)context.get("engineJars"), includeJars, excludeJars);
        allJars.addAll( ExtenderUtil.pruneItems( extensionJars, includeJars, excludeJars) );
        return allJars;
    }

    // arguments:
    //   jars            - the list of all available jar files gathered from the build
    //   extensionJarMap - a mapping from a jar file to a list of its corresponding proGuard contexts
    private Map<String, ProGuardContext> getProGuardMapping(List<String> jars, Map<String,ProGuardContext> extensionJarMap) {
        Map<String,ProGuardContext> jarToProGuardContextMap = new HashMap<>();

        for (String jar : jars) {
            jarToProGuardContextMap.put(jar, null);
        }

        for (Map.Entry<String,ProGuardContext> extensionJarEntry : extensionJarMap.entrySet()) {
            String jar          = extensionJarEntry.getKey();
            ProGuardContext ctx = extensionJarEntry.getValue();

            // rJars from the buildRJar function will exist in the extensionJarMap,
            // but associated with a null context.
            if (ctx == null) {
                continue;
            }

            jarToProGuardContextMap.put(jar,ctx);

            // If we couldn't find any proguard files for this extension,
            // we need to make sure that there is a context available
            // for all the .jar files we found in the extension
            if (ctx.proGuardFiles.size() == 0) {
                for (String libraryJar : ctx.libraryJars) {
                    ProGuardContext libraryCtx = jarToProGuardContextMap.get(libraryJar);

                    if (libraryCtx == null) {
                        libraryCtx = new ProGuardContext();
                        libraryCtx.proGuardFiles.addAll(ctx.proGuardFiles);
                        jarToProGuardContextMap.put(libraryJar,libraryCtx);
                    }
                }
            }
        }

        return jarToProGuardContextMap;
    }

    // arguments:
    //   allJars         - the list of all available jar files gathered from the build
    //   extensionJarMap - a mapping from a jar file to a list of its corresponding proGuard contexts
    // returns:
    //   a pair of the built & optimized proGuard jar and its corresponding mappings.txt file.
    //   the mappings file can be uploaded to google play and then used for symbolication
    private Map.Entry<File,File> buildProGuard(List<String> allJars, Map<String,ProGuardContext> extensionJarMap) throws ExtenderException {
        // To support older versions of build.yml where proGuardCmd is not defined:
        String proGuardCmd = platformConfig.proGuardCmd;
        if (proGuardCmd == null || proGuardCmd.isEmpty() || DM_DEBUG_DISABLE_PROGUARD) {
            if (DM_DEBUG_DISABLE_PROGUARD) {
                LOGGER.info("ProGuard support disabled by environment flag DM_DEBUG_DISABLE_PROGUARD");
            } else {
                LOGGER.info("No SDK support. Skipping ProGuard step.");
            }
            return null;
        }

        File appPro = new File(uploadDirectory, "/_app/app.pro");
        if (!appPro.exists()) {
            LOGGER.info("No .pro file present. Skipping ProGuard step.");
            return null;
        }

        LOGGER.info("Building using ProGuard {}", uploadDirectory);

        String appProPath = appPro.getAbsolutePath();
        Map<String,ProGuardContext> allJarsMap = getProGuardMapping(allJars, extensionJarMap);

        List<String> allPro = new ArrayList<>();
        allPro.add(appProPath);

        File targetFile  = new File(buildDirectory, "dmengine.jar");
        File mappingFile = new File(buildDirectory, "mapping.txt");

        List<String> jarList          = new ArrayList<>();
        List<String> jarLibrariesList = new ArrayList<>();

        for (Map.Entry<String,ProGuardContext> jarMapEntry : allJarsMap.entrySet())
        {
            String jar = jarMapEntry.getKey();
            ProGuardContext jarProGuardContext = jarMapEntry.getValue();

            // jarProGuardContext is null for all the jars that are affected by the
            // 'global' appPro file. We could make a context for them, but it's not necessary
            if (jarProGuardContext == null || jarProGuardContext.proGuardFiles.size() > 0) {
                jarList.add(jar);

                if (jarProGuardContext != null) {
                    for (String proGuardFile : jarProGuardContext.proGuardFiles) {
                        allPro.add(proGuardFile);
                    }
                }
            } else {
                jarLibrariesList.add(jar);
            }
        }
        //exclude fake `jar` paths for extensions without java code
        List<String> excludeJars = new ArrayList<>();
        excludeJars.add("(.*)/proguard_files_without_jar");
        jarLibrariesList = ExtenderUtil.excludeItems(jarLibrariesList, excludeJars);
        jarList = ExtenderUtil.excludeItems(jarList, excludeJars);

        Map<String, Object> context = createContext(mergedAppContext);
        context.put("jars", jarList);
        context.put("libraryjars", jarLibrariesList);
        context.put("src", allPro);
        context.put("tgt", targetFile.getAbsolutePath());
        context.put("mapping", mappingFile.getAbsolutePath());

        String command = templateExecutor.execute(proGuardCmd, context);

        try {
            processExecutor.execute(command);
        } catch (IOException | InterruptedException e) {
            throw new ExtenderException(e, processExecutor.getOutput());
        }

        return new AbstractMap.SimpleEntry<File, File>(targetFile, mappingFile);
    }

    public void validateManifestPlatforms(ManifestConfiguration manifestConfig) throws ExtenderException {
        if (manifestConfig.platforms == null) {
            return;
        }

        Set<String> manifestPlatforms = new HashSet<>(manifestConfig.platforms.keySet());
        manifestPlatforms.removeAll(Arrays.asList(ALLOWED_MANIFEST_PLATFORMS));
        if (!manifestPlatforms.isEmpty()) {
            throw new ExtenderException(String.format("Extension %s contains invalid platform(s): %s. Allowed platforms: %s", manifestConfig.name, manifestPlatforms.toString(), Arrays.toString(ALLOWED_MANIFEST_PLATFORMS)));
        }
    }

    public static Map<String, Object> getManifestContext(String platform, ManifestConfiguration manifestConfig) throws ExtenderException {
        if (manifestConfig.platforms == null) {
            return new HashMap<>();
        }

        ManifestPlatformConfig manifestPlatformConfig = manifestConfig.platforms.get(platform);
        if (manifestPlatformConfig != null && manifestPlatformConfig.context != null) {
            return manifestPlatformConfig.context;
        }
        return new HashMap<>();
    }

    private File buildMainDexList(List<String> jars) throws ExtenderException {

        // Find the engine libraries (**/share/java/*.jar)
        List<String> mainListJars = ExtenderUtil.filterStrings(jars, ENGINE_JAR_RE);

        if (mainListJars.isEmpty()) {
            throw new ExtenderException("Regex failed to find any engine jars: " + ENGINE_JAR_RE);
        }

        List<String> mainClassNames = new ArrayList<String>();

        for (String jarFile : mainListJars) {
            try {
                List<String> entries = ZipUtils.getEntries(jarFile);
                Collections.sort(entries); // make comparisons easier

                mainClassNames.addAll( entries );
            } catch (IOException e) {
                throw new ExtenderException(e, "Failed to read the class names from " + jarFile);
            }
        }

        // Only keep the .class files
        mainClassNames = ExtenderUtil.filterStrings(mainClassNames, "(?:.*)\\.class$");

        File mainList = new File(buildDirectory, "main_dex_list.txt");

        try {
            mainList.createNewFile();
            for (String classFile : mainClassNames) {
                FileUtils.writeStringToFile(mainList, classFile + "\n", Charset.defaultCharset(), true);
            }
        } catch (IOException e) {
            throw new ExtenderException(e, "Failed to write to " + mainList.getAbsolutePath());
        }
        return mainList;
    }

    private File[] buildClassesDex(List<String> jars, File mainDexList) throws ExtenderException {
        LOGGER.info("Building classes.dex with extension source {}", uploadDirectory);

        // The empty list is also present for backwards compatability with older build.yml
        List<String> empty_list = new ArrayList<>();

        Map<String, Object> context = createContext(mergedAppContext);
        context.put("classes_dex_dir", buildDirectory.getAbsolutePath());
        context.put("jars", jars);
        context.put("engineJars", empty_list);
        context.put("mainDexList", mainDexList.getAbsolutePath());

        String command = platformConfig.dxCmd;

        command = templateExecutor.execute(command, context);

        try {
            processExecutor.execute(command);
        } catch (IOException | InterruptedException e) {
            throw new ExtenderException(e, processExecutor.getOutput());
        }

        File[] classes = ExtenderUtil.listFilesMatching(buildDirectory, "^classes(|[0-9]+)\\.dex$");
        return classes;
    }

    private File buildWin32Resources(Map<String, Object> mergedAppContext) throws ExtenderException {
        Map<String, Object> context = createContext(mergedAppContext);
        File resourceFile = new File(buildDirectory, "dmengine.res");
        context.put("tgt", ExtenderUtil.getRelativePath(jobDirectory, resourceFile));

        String command = templateExecutor.execute(platformConfig.windresCmd, context);
        if (command.equals("")) {
            return null;
        }
        try {
            LOGGER.info("Creating .res file");
            processExecutor.execute(command);
        } catch (IOException | InterruptedException e) {
            throw new ExtenderException(e, processExecutor.getOutput());
        }

        return resourceFile;
    }

    private void loadManifests() throws IOException, ExtenderException {
        // Creates the contexts for each stage: extension and app
        //  extension context: merge(platform, variant, extension, app)
        //  app context:       merge(platform, variant, extensions, app)

        manifestConfigs = new HashMap<>();
        manifestFiles = new HashMap<>();

        HashMap<String, ManifestConfiguration> _manifestConfigs = new HashMap<>();
        for (File manifest : this.manifests) {
            ManifestConfiguration manifestConfig = Extender.loadYaml(this.jobDirectory, manifest, ManifestConfiguration.class);

            if (manifestConfig == null) {
                throw new ExtenderException("Missing manifest file: " + manifest.getAbsolutePath());
            }
            validateManifestPlatforms(manifestConfig);
            _manifestConfigs.put(manifestConfig.name, manifestConfig);
            manifestFiles.put(manifestConfig.name, manifest);
        }

        List<String> symbols = getSortedKeys(_manifestConfigs.keySet());
        for (String extensionSymbol : symbols) {
            File manifest = manifestFiles.get(extensionSymbol);
            String relativePath = ExtenderUtil.getRelativePath(this.uploadDirectory, manifest);

            ManifestConfiguration manifestConfig = _manifestConfigs.get(extensionSymbol);

            Map<String, Object> manifestContext = new HashMap<>();
            manifestContext = ExtenderUtil.mergeContexts(manifestContext, this.platformConfig.context);
            manifestContext = ExtenderUtil.mergeContexts(manifestContext, this.platformVariantConfig.context);

            for (String platformAlternative : ExtenderUtil.getPlatformAlternatives(platform)) {
                Map<String, Object> ctx = getManifestContext(platformAlternative, manifestConfig);
                this.manifestValidator.validate(relativePath, manifest.getParentFile(), ctx);

                manifestContext = ExtenderUtil.mergeContexts(manifestContext, ctx);
            }

            manifestContext = ExtenderUtil.mergeContexts(manifestContext, this.platformAppConfig.context);

            // Apply any global settings to the context
            manifestContext.put("extension_name", manifestConfig.name);
            manifestContext.put("extension_name_upper", manifestConfig.name.toUpperCase());

            manifestConfigs.put(extensionSymbol, manifestContext);
        }

        // Now create the app context
        mergedAppContext = new HashMap<>();
        mergedAppContext = ExtenderUtil.mergeContexts(mergedAppContext, this.platformConfig.context);
        mergedAppContext = ExtenderUtil.mergeContexts(mergedAppContext, this.platformVariantConfig.context);

        for (String extensionSymbol : symbols) {
            ManifestConfiguration manifestConfig = _manifestConfigs.get(extensionSymbol);
            for (String platformAlternative : ExtenderUtil.getPlatformAlternatives(platform)) {
                Map<String, Object> ctx = getManifestContext(platformAlternative, manifestConfig);
                mergedAppContext = ExtenderUtil.mergeContexts(mergedAppContext, ctx);
            }
        }
        // The final link context is a merge of the app manifest and the extension contexts
        mergedAppContext = ExtenderUtil.mergeContexts(mergedAppContext, this.platformAppConfig.context);

        // Since the defines variable is dependent on these
        // It's easier to add them here than to curate the context before sending it to each build function
        // (e.g. building java doesn't require the C++ defines)
        mergedAppContext.put("extension_name", "unknown");
        mergedAppContext.put("extension_name_upper", "UNKNOWN");

        mergedAppContext.put("dynamo_home", ExtenderUtil.getRelativePath(jobDirectory, sdk));
        mergedAppContext.put("platform", this.platform);
        mergedAppContext.put("host_platform", this.hostPlatform);

        //exclude fake `jar` path for extensions without java code
        List<String> excludeJars = ExtenderUtil.getStringList(mergedAppContext, "excludeJars");
        excludeJars.add("(.*)/proguard_files_without_jar");
        mergedAppContext.put("excludeJars", excludeJars);
    }

    public Map<String, Object> getPlatformContext() {
        return this.platformConfig.context;
    }

    public Map<String, Object> getPlatformVariantContext() {
        return this.platformVariantConfig.context;
    }

    public Map<String, Object> getAppContext() {
        return this.platformVariantConfig.context;
    }

    public Map<String, Object> getMergedExtensionContext(String name) {
        return this.manifestConfigs.get(name);
    }

    public Map<String, Object> getMergedAppContext() {
        return this.mergedAppContext;
    }

    private List<String> getSortedKeys(Set<String> keyset) {
        String[] keys = keyset.toArray(new String[keyset.size()]);
        Arrays.sort(keys);
        return new ArrayList<String>(Arrays.asList(keys));
    }

    private boolean shouldBuildArtifact(String artifact) {
        List<String> artifacts = Arrays.asList(this.buildArtifacts.split(","));
        return artifacts.contains(artifact);
    }

    private boolean shouldBuildEngine() {
        return this.buildArtifacts.equals("") || shouldBuildArtifact("engine");
    }
    private boolean shouldBuildPlugins() {
        return shouldBuildArtifact("plugins");
    }
    private boolean shouldBuildLibrary() {
        return shouldBuildArtifact("library");
    }

    private List<File> buildLibraries() throws ExtenderException {
        System.out.printf("buildLibrary\n");

        if (!shouldBuildLibrary()) {
            return new ArrayList<>();
        }
        LOGGER.info("Building library for platform {} with extension source {}", platform, uploadDirectory);

        List<File> outputFiles = new ArrayList<>();
        try {
            List<String> symbols = getSortedKeys(manifestConfigs.keySet());
            for (String extensionSymbol : symbols) {
                Map<String, Object> extensionContext = manifestConfigs.get(extensionSymbol);
                File manifest = manifestFiles.get(extensionSymbol);

                // TODO: Thread this step
                outputFiles.addAll(buildLibrary(manifest, extensionContext));
            }

            return outputFiles;
        } catch (IOException | InterruptedException e) {
            throw new ExtenderException(e, processExecutor.getOutput());
        }
    }

    private List<File> buildEngine() throws ExtenderException {
        if (!shouldBuildEngine()) {
            return new ArrayList<>();
        }
        LOGGER.info("Building engine for platform {} with extension source {}", platform, uploadDirectory);

        List<File> outputFiles = new ArrayList<>();
        try {
            outputFiles.addAll(buildPods());

            List<String> symbols = getSortedKeys(manifestConfigs.keySet());
            for (String extensionSymbol : symbols) {
                Map<String, Object> extensionContext = manifestConfigs.get(extensionSymbol);
                File manifest = manifestFiles.get(extensionSymbol);

                // TODO: Thread this step
                outputFiles.addAll(buildExtension(manifest, extensionContext));
            }

            File resourceFile = null;
            if (platform.endsWith("win32")) {
                resourceFile = buildWin32Resources(mergedAppContext);
            }

            Map<String, Object> podAppContext = new HashMap<>();
            if (resolvedPods != null) {
                podAppContext.put("frameworks", resolvedPods.getAllPodFrameworks(platform));
                podAppContext.put("weakFrameworks", resolvedPods.getAllPodWeakFrameworks(platform));
                podAppContext.put("libs", resolvedPods.getAllPodLibs(platform));
                podAppContext.put("linkFlags", resolvedPods.getAllPodLinkFlags(platform));
                podAppContext.put("osMinVersion", resolvedPods.platformMinVersion);
            }
            Map mergedAppContextWithPods = ExtenderUtil.mergeContexts(mergedAppContext, podAppContext);

            outputFiles.addAll(linkEngine(symbols, mergedAppContextWithPods, resourceFile));
            return outputFiles;
        } catch (IOException | InterruptedException e) {
            throw new ExtenderException(e, processExecutor.getOutput());
        }
    }

    private boolean isDesktopPlatform(String platform) {
        return platform.equals("x86_64-osx") || platform.equals("arm64-osx") || platform.equals("x86_64-linux") || platform.equals("x86_64-win32");
    }

    // Supported from 1.2.186
    private List<File> buildPipelinePlugin() throws ExtenderException {

        if (!isDesktopPlatform(platform)) {
            return new ArrayList<>();
        }
        if (!shouldBuildPlugins()) {
            return new ArrayList<>();
        }
        if (platformConfig.writeShLibPattern == null) {
            throw new ExtenderException("Trying to build plugins with an old sdk");
        }

        LOGGER.info("Building pipeline plugin for platform {} with extension source {}", platform, uploadDirectory);

        try {
            List<File> output = new ArrayList<>();

            List<String> symbols = getSortedKeys(manifestConfigs.keySet());
            for (String extensionSymbol : symbols) {
                Map<String, Object> extensionContext = manifestConfigs.get(extensionSymbol);
                File manifest = manifestFiles.get(extensionSymbol);

                List<File> pluginOutput = buildPipelineExtension(manifest, extensionContext);
                output.addAll(pluginOutput);
            }

            return output;
        } catch (IOException | InterruptedException e) {
            throw new ExtenderException(e, processExecutor.getOutput());
        }
    }

    private List<File> copyAndroidJniFolders(String platform) throws ExtenderException {
        List<File> jniFolders = getAndroidJniFolders(platform);
        if (jniFolders.isEmpty()) {
            return new ArrayList<>();
        }
        File targetDir = new File(buildDirectory, "jni");
        targetDir.mkdir();

        try {
            for (File jni : jniFolders) {
                FileUtils.copyDirectory(jni, targetDir);
            }
        } catch (IOException e) {
            throw new ExtenderException(e, "Failed to copy android JNIs");
        }
        return FileUtils.listFiles(targetDir, null, true).stream()
                                                      .collect(Collectors.toList());
    }

    private List<File> copyAndroidAssetFolders(String platform) throws ExtenderException {
        List<File> assets = getAndroidAssetsFolders(platform);
        if (assets.isEmpty()) {
            return new ArrayList<>();
        }
        File targetDir = new File(buildDirectory, "assets");
        targetDir.mkdir();

        try {
            for (File a : assets) {
                FileUtils.copyDirectory(a, targetDir);
            }
        } catch (IOException e) {
            throw new ExtenderException(e, "Failed to copy android assets");
        }
        return FileUtils.listFiles(targetDir, null, true).stream()
                                                      .collect(Collectors.toList());
    }

    private List<File> copyAndroidResourceFolders(List<String> androidResourceFolders) throws ExtenderException {
        if (androidResourceFolders.isEmpty()) {
            return new ArrayList<>();
        }
        File packagesDir = new File(buildDirectory, "packages");
        packagesDir.mkdir();

        List<String> packagesList = new ArrayList<>();

        try {
            for (String androidResourceFolder : androidResourceFolders) {
                File packageResourceDir = new File(androidResourceFolder);
                File targetDir = new File(packagesDir, packageResourceDir.getParentFile().getName() + "/res");
                FileUtils.copyDirectory(packageResourceDir, targetDir);

                String relativePath = ExtenderUtil.getRelativePath(packagesDir, targetDir);
                packagesList.add(relativePath);
            }

            Files.write(new File(packagesDir, "packages.txt").toPath(), packagesList, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ExtenderException(e, "Failed to copy android resources");
        }
        return FileUtils.listFiles(packagesDir, null, true).stream()
                                                      .collect(Collectors.toList());
    }


    // get extra packages (for aapt2) from the 'package' attribute in the AndroidManifest
    // of the gradle dependencies. only get extra packages from aar dependencies which
    // have a res folder
    private List<String> getExtraPackagesFromGradlePackages() throws ExtenderException {
        Set<String> extraPackages = new HashSet<String>();
        try {
            for (File f : gradlePackages) {
                if(f.getName().endsWith(".aar")) {
                    File res = new File(f, "res");
                    File androidManifest = new File(f, "AndroidManifest.xml");
                    if (res.exists() && androidManifest.exists()) {
                        String am = FileUtils.readFileToString(androidManifest);
                        Pattern p = Pattern.compile(".*package=\"(.*?)\".*", Pattern.MULTILINE | Pattern.DOTALL);
                        Matcher m = p.matcher(am);
                        if (m.matches()) {
                            String packageName = m.group(1);
                            extraPackages.add(packageName);
                        }
                    }
                }
            }
        }
        catch (IOException e) {
            throw new ExtenderException(e, "Failed to get android packages");
        }
        return new ArrayList<String>(extraPackages);
    }

    private List<File> buildAndroid(String platform) throws ExtenderException {
        LOGGER.info("Building Android specific code");

        List<File> outputFiles = new ArrayList<>();

        final List<String> androidResourceFolders = getAndroidResourceFolders(platform);

        File rJavaDir = null;
        // 1.2.174
        if (platformConfig.aapt2compileCmd != null) {
            // compile and link all of the resource files
            // we get the compiled resources and some additional data in an apk which we pass back to the client
            // we also get a mapping of resources to resource ids which is useful for debugging
            // we finally also get one or more R.java files which we use in the next step when compiling all java files
            File compiledResourcesDir = compileAndroidResources(androidResourceFolders, mergedAppContext);
            Map<String, File> files = linkAndroidResources(compiledResourcesDir, mergedAppContext);
            outputFiles.add(files.get("outApkFile"));
            outputFiles.add(files.get("resourceIdsFile"));
            rJavaDir = files.get("outJavaDirectory");
        }
        else {
            rJavaDir = generateRJava(androidResourceFolders, mergedAppContext);
        }

        // take the generated R.java files and compile them to jar files
        File rJar = buildRJar(rJavaDir);

        Map<String, ProGuardContext> extensionJarMap = buildJava(rJar);
        List<String> allJars                         = getAllJars(extensionJarMap);
        Map.Entry<File,File> proGuardFiles           = buildProGuard(allJars, extensionJarMap);

        File mainDexList = buildMainDexList(allJars);

        // If we have proGuard support, we need to reset the allJars list so that
        // we don't get duplicate symbols.
        if (proGuardFiles != null) {
            allJars.clear();
            allJars.add(proGuardFiles.getKey().getAbsolutePath()); // built jar
            outputFiles.add(proGuardFiles.getValue()); // mappings file

            // Add the jars that were not run through ProGuard
            for (Map.Entry<String,ProGuardContext> extensionJarEntry : extensionJarMap.entrySet()) {
                String extensionJar = extensionJarEntry.getKey();
                ProGuardContext proGuardContext = extensionJarEntry.getValue();

                if (proGuardContext != null && proGuardContext.proGuardFiles.size() == 0) {
                    allJars.add(extensionJar);

                    for (String extensionLibraryJar : proGuardContext.libraryJars) {
                        allJars.add(extensionLibraryJar);
                    }
                }
            }
        }

        File[] classesDex = buildClassesDex(allJars, mainDexList);
        if (classesDex.length > 0) {
            outputFiles.addAll(Arrays.asList(classesDex));
        }

        outputFiles.addAll(copyAndroidResourceFolders(androidResourceFolders));
        outputFiles.addAll(copyAndroidAssetFolders(platform));
        outputFiles.addAll(copyAndroidJniFolders(platform));

        return outputFiles;
    }

    private String getBasePlatform(String platform) {
        String[] platformParts = this.platform.split("-");
        return platformParts[1];
    }

    // Called for each platform, to merge the manifests into one
    private List<File> buildManifests(String platform) throws ExtenderException {
        List<File> out = new ArrayList<>();

        // prior to 1.2.165
        if (platformConfig.manifestMergeCmd == null) {
            LOGGER.info("Manifest merging not supported by this sdk");
            return out;
        }

        String platformName = getBasePlatform(platform);
        String manifestName = this.platformConfig.manifestName;

        if (manifestName == null) {
            LOGGER.info("No manifest base name!");
            return out;
        }

        // The merged output will end up here
        File targetManifest = new File(buildDirectory, manifestName);
        out.add(targetManifest);

        File mainManifest = new File(uploadDirectory, manifestName);

        // Make sure they're in the "<extension>/manifests/<platform>/<manifest name>"
        List<File> allManifests = new ArrayList<>();
        for (File dir : getExtensionFolders()) {
            File manifest = new File(dir, String.format("manifests/%s/%s", platformName, manifestName));
            if (manifest.exists()) {
                allManifests.add(manifest);
            }
        }

        // Add all dependency manifest files
        if (gradlePackages != null) {
            for (File dependencyDir : gradlePackages) {
                File manifest = new File(dependencyDir, manifestName);
                if (manifest.exists()) {
                    allManifests.add(manifest);
                }
            }
        }

        // no need to merge a single file
        if (allManifests.isEmpty()) {
            if (!mainManifest.exists()) {
                return out;
            }

            LOGGER.info("Copying manifest");
            try {
                FileUtils.copyFile(mainManifest, targetManifest);
            } catch (IOException e) {
                throw new ExtenderException(e, String.format("Failed to copy manifest %s to %s", mainManifest.getAbsolutePath(), targetManifest.getAbsolutePath()));
            }
        } else {
            LOGGER.info("Merging manifests");

            // Merge the files
            Map<String, Object> context = createContext(mergedAppContext);
            context.put("mainManifest", mainManifest.getAbsolutePath());
            context.put("target", targetManifest.getAbsolutePath());

            context.put("platform", platformName);

            List<String> libraries = allManifests.stream()
                                                 .map(File::getAbsolutePath)
                                                 .collect(Collectors.toList());
            context.put("libraries", libraries);

            try {
                String command = templateExecutor.execute(platformConfig.manifestMergeCmd, context);
                processExecutor.execute(command);
            } catch (IOException | InterruptedException e) {
                throw new ExtenderException(e, processExecutor.getOutput());
            }
        }
        return out;
    }

    File writeLog() {
        File logFile = new File(buildDirectory, "log.txt");
        try {
            LOGGER.info("Writing log file");
            processExecutor.writeLog(logFile);
        } catch (IOException e) {
            LOGGER.error("Failed to write log file to {}", logFile.getAbsolutePath());
        }
        return logFile;
    }

    void resolve(GradleService gradleService) throws ExtenderException {
        try {
            gradlePackages = gradleService.resolveDependencies(this.platformConfig.context, jobDirectory, useJetifier);
        }
        catch (IOException e) {
            throw new ExtenderException(e, "Failed to resolve Gradle dependencies. " + e.getMessage());
        }
    }

    void resolve(CocoaPodsService cocoaPodsService) throws ExtenderException {
        try {
            resolvedPods = cocoaPodsService.resolveDependencies(this.platformConfig.context, jobDirectory, platform);
        }
        catch (IOException e) {
            throw new ExtenderException(e, "Failed to resolve CocoaPod dependencies. " + e.getMessage());
        }
    }

    List<File> build() throws ExtenderException {
        List<File> outputFiles = new ArrayList<>();

        outputFiles.addAll(buildManifests(platform));

        // TODO: Thread this step
        if (platform.endsWith("android")) {
            outputFiles.addAll(buildAndroid(platform));
        }
        if (shouldBuildLibrary())
            outputFiles.addAll(buildLibraries());
        else
            outputFiles.addAll(buildEngine());
        outputFiles.addAll(buildPipelinePlugin());
        File log = writeLog();
        if (log.exists()) {
            outputFiles.add(log);
        }
        return outputFiles;
    }
}
