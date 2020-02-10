package com.defold.extender;

import com.google.common.collect.ImmutableMap;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.RegexFileFilter;
import org.apache.commons.io.comparator.NameFileComparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class Extender {
    private static final Logger LOGGER = LoggerFactory.getLogger(Extender.class);
    private final Configuration config;
    private final String appManifestPath;
    private final String platform;
    private final File sdk;
    private final File uploadDirectory;
    private final File jobDirectory;
    private final File buildDirectory;
    private final PlatformConfig platformConfig;
    private final ExtensionManifestValidator manifestValidator;
    private final TemplateExecutor templateExecutor = new TemplateExecutor();
    private final ProcessExecutor processExecutor = new ProcessExecutor();
    private final Map<String, Object> appManifestContext;
    private final Boolean withSymbols;

    private Map<String, Map<String, Object>>    manifestConfigs;
    private Map<String, File>                   manifestFiles;
    private Map<String, Object>                 mergedAppContext;

    private List<File> extDirs;
    private List<File> manifests;
    private List<File> gradlePackages;
    private int nameCounter = 0;

    static final String APPMANIFEST_BASE_VARIANT_KEYWORD = "baseVariant";
    static final String APPMANIFEST_WITH_SYMBOLS_KEYWORD = "withSymbols";
    static final String FRAMEWORK_RE = "(.+)\\.framework";
    static final String JAR_RE = "(.+\\.jar)";
    static final String JS_RE = "(.+\\.js)";
    static final String ENGINE_JAR_RE = "(?:.*)\\/share\\/java\\/[\\w\\-\\.]*\\.jar$";

    private static final String ANDROID_NDK_PATH = System.getenv("ANDROID_NDK_PATH");
    private static final String ANDROID_NDK_INCLUDE_PATH = System.getenv("ANDROID_NDK_INCLUDE");
    private static final String ANDROID_STL_INCLUDE_PATH = System.getenv("ANDROID_STL_INCLUDE");
    private static final String ANDROID_STL_ARCH_INCLUDE_PATH = System.getenv("ANDROID_STL_ARCH_INCLUDE");
    private static final String ANDROID_STL_LIB_PATH = System.getenv("ANDROID_STL_LIB");
    private static final String ANDROID_SYSROOT_PATH = System.getenv("ANDROID_SYSROOT");

    private static final String MANIFEST_IOS    = "Info.plist";
    private static final String MANIFEST_OSX    = "Info.plist";
    private static final String MANIFEST_ANDROID= "AndroidManifest.xml";
    private static final String MANIFEST_HTML5  = "engine_template.html";

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

    Extender(String platform, File sdk, File jobDirectory, File uploadDirectory, File buildDirectory, List<File> gradlePackages) throws IOException, ExtenderException {
        this.jobDirectory = jobDirectory;
        this.uploadDirectory = uploadDirectory;
        this.buildDirectory = buildDirectory;
        this.gradlePackages = gradlePackages;

        // Read config from SDK
        this.config = Extender.loadYaml(this.jobDirectory, new File(sdk.getPath() + "/extender/build.yml"), Configuration.class);

        // Read the app manifest from the upload folder
        Collection<File> allFiles = FileUtils.listFiles(uploadDirectory, null, true);
        List<File> appManifests = allFiles.stream().filter(f -> f.getName().equals("app.manifest")).collect(Collectors.toList());
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

            // To avoid null pointers later on
            if (appManifest.platforms.get(platform) == null) {
                appManifest.platforms.put(platform, new AppManifestPlatformConfig());
            }
            if (appManifest.platforms.get(platform).context == null) {
                appManifest.platforms.get(platform).context = new HashMap<String, Object>();
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

        this.withSymbols = ExtenderUtil.getAppManifestContextBoolean(appManifest, APPMANIFEST_WITH_SYMBOLS_KEYWORD, false);

        this.platform = platform;
        this.sdk = sdk;

        this.platformConfig = getPlatformConfig(platform);
        this.appManifestContext = ExtenderUtil.getAppManifestContext(appManifest, platform, baseVariantManifest);
        LOGGER.info("Using context for platform: " + platform);

        processExecutor.setCwd(jobDirectory);

        if (this.platformConfig != null && this.platformConfig.env != null) {

            HashMap<String, Object> envContext = new HashMap<>();
            envContext.put("build_folder", buildDirectory);

            // Make system env variables available for the template execution below.
            for (Map.Entry<String, String> sysEnvEntry : System.getenv().entrySet()) {
                envContext.put("env." + sysEnvEntry.getKey(), sysEnvEntry.getValue());
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
        List<String> allowedSymbols = ExtenderUtil.mergeLists(this.platformConfig.allowedSymbols, (List<String>) this.config.context.getOrDefault("allowedSymbols", new ArrayList<String>()) );

        // The user input (ext.manifest + _app/app.manifest) will be checked against this validator
        this.manifestValidator = new ExtensionManifestValidator(new WhitelistConfig(), this.platformConfig.allowedFlags, allowedSymbols);

        // Make sure the user hasn't input anything invalid in the manifest
        this.manifestValidator.validate(this.appManifestPath, appManifestContext);

        // Collect extension directories (used by both buildEngine and buildClassesDex)
        this.manifests = allFiles.stream().filter(f -> f.getName().equals("ext.manifest")).collect(Collectors.toList());
        this.extDirs = this.manifests.stream().map(File::getParentFile).collect(Collectors.toList());

        // Load and merge manifests
        loadManifests();
    }

    private PlatformConfig getPlatformConfig(String platform) throws ExtenderException {
        PlatformConfig platformConfig = config.platforms.get(platform);

        if (platformConfig == null) {
            throw new ExtenderException(String.format("Unsupported platform %s", platform));
        }

        return platformConfig;
    }

    private String getNameUUID() {
        int c = nameCounter++;
        return String.format("%d", c);
    }

    private File uniqueTmpFile(String prefix, String suffix) {
        File file;
        do {
            file = new File(buildDirectory, prefix + getNameUUID() + suffix);
        } while (file.exists());

        return file;
    }

    private File uniqueTmpFile(String pattern) {
        File file;
        do {
            file = new File(buildDirectory, String.format(pattern, getNameUUID()));
        } while (file.exists());

        return file;
    }

    private static int countLines(String str){
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
            return new Yaml().loadAs(yaml, type);
        } catch(YAMLException e) {
            throw new ExtenderException(String.format("%s:1: error: %s", ExtenderUtil.getRelativePath(root, manifest), e.toString()));
        }
    }

    static List<File> filterFiles(Collection<File> files, String re) {
        Pattern p = Pattern.compile(re);
        return files.stream().filter(f -> p.matcher(f.getName()).matches()).collect(Collectors.toList());
    }

    static List<String> filterStrings(Collection<String> strings, String re) {
        Pattern p = Pattern.compile(re);
        return strings.stream().filter(s -> p.matcher(s).matches()).collect(Collectors.toList());
    }

    static List<String> mergeLists(List<String> a, List<String> b) {
        return Stream.concat(a.stream(), b.stream()).collect(Collectors.toList());
    }

    static boolean isListOfStrings(List<Object> list) {
        return list.stream().allMatch(o -> o instanceof String);
    }

    // Copies the original context, and appends the extra context's elements, if the keys and types are valid
    static Map<String, Object> mergeContexts(Map<String, Object> originalContext, Map<String, Object> extensionContext) throws ExtenderException {
        Map<String, Object> context = new HashMap<>(originalContext);

        Set<String> keys = extensionContext.keySet();
        for (String k : keys) {
            Object v1 = context.getOrDefault(k, null);
            Object v2 = extensionContext.get(k);

            if (v1 == null && v2 == null) {
                // Simply skip keys that hold no values at all
                context.remove(k);
                continue;
            }

            if (v1 != null && v2 != null && !v1.getClass().equals(v2.getClass())) {
                throw new ExtenderException(String.format("Wrong manifest context variable type for %s: Expected %s, got %s: %s", k, v1.getClass().toString(), v2.getClass().toString(), v2.toString()));
            }
            if (v2 != null && v2 instanceof List && !Extender.isListOfStrings((List<Object>) v2)) {
                throw new ExtenderException(String.format("The context variables only support lists of strings. Got %s (type %s)", v2.toString(), v2.getClass().getCanonicalName()));
            }

            if (v1 != null && v2 != null && v1 instanceof List) {
                v1 = Extender.mergeLists((List<String>) v1, (List<String>) v2);
            }

            if (v1 != null) {
                context.put(k, v1);
            } else {
                context.put(k, v2);
            }
        }
        return context;
    }

    private static Map<String, Object> createEmptyContext(Map<String, Object> original) {
        Map<String, Object> out = new HashMap<>();
        Set<String> keys = original.keySet();
        for (String k : keys) {
            Object v = original.get(k);
            if (v instanceof String) {
                v = "";
            } else if (v instanceof List) {
                v = new ArrayList<String>();
            }
            out.put(k, v);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> context(Map<String, Object> manifestContext) throws ExtenderException {
        Map<String, Object> context = new HashMap<>(config.context);

        // Not needed since 1.2.153 - keep in case someone uses older build.yml
        if (this.platform.contains("android")) {
            context.put("android_ndk_path", ANDROID_NDK_PATH);
            context.put("android_ndk_include", ANDROID_NDK_INCLUDE_PATH);
            context.put("android_stl_include", ANDROID_STL_INCLUDE_PATH);
            context.put("android_stl_arch_include", ANDROID_STL_ARCH_INCLUDE_PATH);
            context.put("android_stl_lib", ANDROID_STL_LIB_PATH);
            context.put("android_sysroot", ANDROID_SYSROOT_PATH);
        }

        context = Extender.mergeContexts(context, platformConfig.context);
        context = Extender.mergeContexts(context, manifestContext);

        // Should not be allowed to be overridden by manifests
        context.put("dynamo_home", ExtenderUtil.getRelativePath(jobDirectory, sdk));
        context.put("platform", this.platform);

        context.put("extension_name", manifestContext.getOrDefault("extension_name", "UNKNOWN"));
        context.put("extension_name_upper", manifestContext.getOrDefault("extension_name_upper", "UNKNOWN"));

        Set<String> keys = context.keySet();
        for (String k : keys) {
            Object v = context.get(k);
            if (v instanceof String) {
                v = templateExecutor.execute((String) v, context);
            } else if (v instanceof List) {
                v = templateExecutor.execute((List<String>) v, context);
            }
            context.put(k, v);
        }

        // Added in 1.2.163 to make it easier to upgrade to Clang 9
        if (this.platform.contains("ios") || this.platform.contains("osx")) {
            LOGGER.debug("Adding arclite hack to ios/osx");
            List<String> linkFlags = (List<String>)context.get("linkFlags");
            if (!linkFlags.contains("-Wl,-U,_objc_loadClassref")) {
                linkFlags.add("-Wl,-U,_objc_loadClassref");
            }

            List<String> libs = (List<String>)context.get("libs");
            if (this.platform.contains("osx")) {
                if (!libs.contains("clang_rt.osx")) {
                    libs.add("clang_rt.osx");
                }
            } else {
                if (!libs.contains("clang_rt.ios")) {
                    libs.add("clang_rt.ios");
                }
            }

            Object platformsdk_dir = this.platformConfig.context.get("env.PLATFORMSDK_DIR");
            if (platformsdk_dir == null) {
                platformsdk_dir = "/opt/platformsdk";
            }
            String path = String.format("%s/XcodeDefault11.0.xctoolchain/usr/lib/clang/11.0.0/lib/darwin", platformsdk_dir);
            List<String> libPaths = (List<String>)context.get("libPaths");
            if (!libPaths.contains(path)) {
                libPaths.add(path);
            }
        }
        else if ( this.platform.contains("win32")) {
            LOGGER.debug("Adding WinMain hack to win32");
            List<String> linkFlags = (List<String>)context.get("linkFlags");
            if (!linkFlags.contains("-Wl,/entry:mainCRTStartup")) {
                linkFlags.add("-Wl,/entry:mainCRTStartup");
            }

            LOGGER.debug("Adding SEH hack to win32");
            if (!linkFlags.contains("-Wl,/safeseh:no")) {
                linkFlags.add("-Wl,/safeseh:no");
            }
        }

        return context;
    }

    private List<String> getFrameworks(File extDir) {
        List<String> frameworks = new ArrayList<>();
        frameworks.addAll(ExtenderUtil.collectFilesByName(new File(extDir, "lib" + File.separator + this.platform), FRAMEWORK_RE)); // e.g. armv64-ios
        String[] platformParts = this.platform.split("-");
        if (platformParts.length == 2) {
            frameworks.addAll(ExtenderUtil.collectFilesByName(new File(extDir, "lib" + File.separator + platformParts[1]), FRAMEWORK_RE)); // e.g. "ios"
        }
        return frameworks;
    }

    private List<String> getFrameworkPaths(File extDir) {
        List<String> frameworkPaths = new ArrayList<>();
        File dir = new File(extDir, "lib" + File.separator + this.platform);
        if (dir.exists()) {
            frameworkPaths.add(dir.getAbsolutePath());
        }
        String[] platformParts = this.platform.split("-");
        if (platformParts.length == 2) {
            File dirShort = new File(extDir, "lib" + File.separator + platformParts[1]);
            if (dirShort.exists()) {
                frameworkPaths.add(dirShort.getAbsolutePath());
            }
        }
        return frameworkPaths;
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
            }
        }

        return allLibJars;
    }

    private File compileFile(int index, File extDir, File src, Map<String, Object> manifestContext) throws IOException, InterruptedException, ExtenderException {
        List<String> includes = new ArrayList<>();
        includes.add( ExtenderUtil.getRelativePath(jobDirectory, new File(extDir, "include") ) );
        includes.add( ExtenderUtil.getRelativePath(jobDirectory, uploadDirectory) );
        File o = new File(buildDirectory, String.format("%s_%d.o", src.getName(), index));

        List<String> frameworks = getFrameworks(extDir);
        List<String> frameworkPaths = getFrameworkPaths(extDir);

        Map<String, Object> context = context(manifestContext);
        context.put("src", ExtenderUtil.getRelativePath(jobDirectory, src));
        context.put("tgt", ExtenderUtil.getRelativePath(jobDirectory, o));
        context.put("ext", ImmutableMap.of("includes", includes, "frameworks", frameworks, "frameworkPaths", frameworkPaths));

        String command = templateExecutor.execute(platformConfig.compileCmd, context);
        processExecutor.execute(command);
        return o;
    }

    private File compileMain(File maincpp, Map<String, Object> manifestContext) throws IOException, InterruptedException, ExtenderException {
        manifestContext.put("extension_name", "ENGINE_MAIN");
        manifestContext.put("extension_name_upper", "ENGINE_MAIN");
        Map<String, Object> context = context(manifestContext);
        File o = uniqueTmpFile("main_tmp", ".o");
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

    private void buildExtension(File manifest, Map<String, Object> manifestContext) throws IOException, InterruptedException, ExtenderException {
        File extDir = manifest.getParentFile();
        File srcDir = new File(extDir, "src");

        File[] srcFiles = null;
        if (srcDir.isDirectory()) {
            Collection<File> _srcFiles = new ArrayList<>();
            _srcFiles = FileUtils.listFiles(srcDir, null, true);
            _srcFiles = Extender.filterFiles(_srcFiles, platformConfig.sourceRe);

            // sorting makes it easier to diff different builds
            srcFiles = _srcFiles.toArray(new File[_srcFiles.size()]);

        }

        if (srcFiles == null) {
            throw new ExtenderException(String.format("%s:1: error: Extension has no source!", ExtenderUtil.getRelativePath(this.uploadDirectory, manifest) ));
        }

        // Makes it a lot easier to diff build logs
        Arrays.sort(srcFiles, NameFileComparator.NAME_INSENSITIVE_COMPARATOR);

        List<String> objs = new ArrayList<>();

        // Compile C++ source into object files
        int i = 0;
        for (File src : srcFiles) {
            File o = compileFile(i, extDir, src, manifestContext);
            objs.add(ExtenderUtil.getRelativePath(jobDirectory, o));
            i++;
        }

        // Create c++ library
        File lib = null;
        if (platformConfig.writeLibPattern != null) {
            lib = uniqueTmpFile(platformConfig.writeLibPattern);
        } else {
            lib = uniqueTmpFile("lib", ".a"); // Deprecated, remove in a few versions
        }
        Map<String, Object> context = context(manifestContext);
        context.put("tgt", lib);
        context.put("objs", objs);
        String command = templateExecutor.execute(platformConfig.libCmd, context);
        processExecutor.execute(command);
    }

    private List<String> patchLibs(List<String> libs) {
        if (libs == null) {
            return new ArrayList<>();
        }
        return libs;
    }

    private List<File> linkEngine(List<String> symbols, Map<String, Object> manifestContext, File resourceFile) throws IOException, InterruptedException, ExtenderException {
        File maincpp = new File(buildDirectory , "main.cpp");

        List<String> extSymbols = new ArrayList<>();
        extSymbols.addAll(symbols);

        Map<String, Object> mainContext = context(manifestContext);

        extSymbols = ExtenderUtil.pruneItems( extSymbols, ExtenderUtil.getStringList(mainContext, "includeSymbols"), ExtenderUtil.getStringList(mainContext, "excludeSymbols") );
        mainContext.put("symbols", ExtenderUtil.pruneItems( ExtenderUtil.getStringList(mainContext, "symbols"), ExtenderUtil.getStringList(mainContext, "includeSymbols"), ExtenderUtil.getStringList(mainContext, "excludeSymbols")));
        mainContext.put("ext", ImmutableMap.of("symbols", extSymbols));

        String main = templateExecutor.execute(config.main, mainContext);
        FileUtils.writeStringToFile(maincpp, main);

        File mainObject = compileMain(maincpp, manifestContext);

        List<String> extLibs = new ArrayList<>();
        List<String> extShLibs = new ArrayList<>();
        List<String> extLibPaths = new ArrayList<>(Arrays.asList(buildDirectory.toString()));
        List<String> extFrameworks = new ArrayList<>();
        List<String> extFrameworkPaths = new ArrayList<>(Arrays.asList(buildDirectory.toString()));
        List<String> extJsLibs = new ArrayList<>();

        extShLibs.addAll(ExtenderUtil.collectFilesByName(buildDirectory, platformConfig.shlibRe));
        extLibs.addAll(ExtenderUtil.collectFilesByName(buildDirectory, platformConfig.stlibRe));
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

        String writeExePattern = platformConfig.writeExePattern;
        if (writeExePattern == null ) {
            writeExePattern = String.format("%sdmengine%s", platformConfig.exePrefix, platformConfig.exeExt); // Legacy, remove in a few versions!
        }
        File exe = new File(buildDirectory, writeExePattern);

        List<String> objects = new ArrayList<>();
        objects.add(ExtenderUtil.getRelativePath(jobDirectory, mainObject));
        if (resourceFile != null) { // For Win32 targets
            objects.add(ExtenderUtil.getRelativePath(jobDirectory, resourceFile));
        }

        Map<String, Object> env = new HashMap<>();
        env.put("libs", extLibs);
        env.put("dynamicLibs", extShLibs);
        env.put("libPaths", extLibPaths);
        env.put("frameworks", extFrameworks);
        env.put("frameworkPaths", extFrameworkPaths);
        env.put("jsLibs", extJsLibs);
        Map<String, Object> context = context(manifestContext);
        context.put("src", objects);
        context.put("tgt", ExtenderUtil.getRelativePath(jobDirectory, exe));
        context.put("ext", env);
        context.put("engineLibs", ExtenderUtil.pruneItems(ExtenderUtil.getStringList(context, "engineLibs"), ExtenderUtil.getStringList(mainContext, "includeLibs"), ExtenderUtil.getStringList(mainContext, "excludeLibs")) );
        context.put("engineJsLibs", ExtenderUtil.pruneItems(ExtenderUtil.getStringList(context, "engineJsLibs"), ExtenderUtil.getStringList(mainContext, "includeJsLibs"), ExtenderUtil.getStringList(mainContext, "excludeJsLibs")) );

        // WINE->clang transition pt1: in the transition period from link.exe -> lld, we want to make sure we can write "foo" as opposed to "foo.lib"
        context.put("libs", patchLibs((List<String>) context.get("libs")));
        context.put("extLibs", patchLibs((List<String>) context.get("extLibs")));
        context.put("engineLibs", patchLibs((List<String>) context.get("engineLibs")));

        String command = templateExecutor.execute(platformConfig.linkCmd, context);

        // WINE->clang transition pt2: Replace any redundant ".lib.lib"
        command = command.replace(".lib.lib", ".lib").replace(".Lib.lib", ".lib").replace(".LIB.lib", ".lib");

        processExecutor.execute(command);

        // Extract symbols
        if (this.withSymbols) {
            String symbolCmd = platformConfig.symbolCmd;
            if (symbolCmd != null && !symbolCmd.equals("")) {
                Map<String, Object> symbolContext = context(manifestContext);
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

    static private File getAndroidResourceFolder(File dir) {
        // In resource folders, we add packages in several ways:
        // 'project/extension/res/android/res/com.foo.name/res/<android folders>' (new)
        // 'project/extension/res/android/res/com.foo.name/<android folders>' (legacy)
        // 'project/extension/res/android/res/<android folders>' (legacy)
        if (dir.isDirectory() && ExtenderUtil.verifyAndroidAssetDirectory(dir)) {
            return dir;
        }

        for (File f : dir.listFiles()) {
            if (!f.isDirectory()) {
                continue;
            }

            File resDir = getAndroidResourceFolder(f);
            if (resDir != null) {
                return resDir;
            }
        }
        return null;
    }

    private List<File> getAndroidResourceFolders(String platform) {
            // New feature from 1.2.165
            File packageDir = new File(uploadDirectory, "packages");
            if (!packageDir.exists()) {
                return new ArrayList<>();
            }
            List<File> packageDirs = new ArrayList<>();

            for (File dir : packageDir.listFiles(File::isDirectory)) {

                File resDir = getAndroidResourceFolder(dir);
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
                    File resDir = getAndroidResourceFolder(f);
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
                            .collect(Collectors.toList());
    }

    // https://manpages.debian.org/jessie/aapt/aapt.1.en.html
    private File generateRJava(String platform, Map<String, Object> mergedAppContext) throws ExtenderException {
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
                return rJavaDir;
            }

            HashMap<String, Object> empty = new HashMap<>();
            Map<String, Object> context = context(empty);
            if (mergedAppContext.containsKey("aaptExtraPackages")) {
                context.put("extraPackages", String.join(":", (List<String>)mergedAppContext.get("aaptExtraPackages")));
            }

            // Use the merged manifest
            File manifestFile = new File(buildDirectory, MANIFEST_ANDROID);
            context.put("manifestFile", manifestFile.getAbsolutePath());
            context.put("outputDirectory", rJavaDir.getAbsolutePath());

            List<String> resourceDirectories = getAndroidResourceFolders(platform)
                                                                .stream()
                                                                .map(File::getAbsolutePath)
                                                                .collect(Collectors.toList());
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
                HashMap<String, Object> empty = new HashMap<>();
                Map<String, Object> context = context(empty);
                context.put("classesDir", classesDir.getAbsolutePath());
                context.put("classPath", classesDir.getAbsolutePath());
                context.put("sourcesListFile", sourcesListFile.getAbsolutePath());
                String command = templateExecutor.execute(platformConfig.javacCmd, context);
                processExecutor.execute(command);

                // Collect all classes into a Jar file
                context = context(empty);
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
                javaSrcFiles = Extender.filterFiles(javaSrcFiles, platformConfig.javaSourceRe);
            }

            File manifestDir = new File(extDir, "manifests/android/");
            Collection<File> proGuardSrcFiles = new ArrayList<>();
            if (manifestDir.isDirectory()) {
                proGuardSrcFiles = FileUtils.listFiles(manifestDir, null, true);
                proGuardSrcFiles = Extender.filterFiles(proGuardSrcFiles, platformConfig.proGuardSourceRe);
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
            Map<String, Object> context = context(manifestContext);
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
            context = context(manifestContext);
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

        try {
            Map<String, Map<String, Object>> manifestConfigs = new HashMap<>();
            for (File manifest : this.manifests) {
                ManifestConfiguration manifestConfig = Extender.loadYaml(this.jobDirectory, manifest, ManifestConfiguration.class);

                Map<String, Object> manifestContext = new HashMap<>();
                if (manifestConfig.platforms != null) {
                    manifestContext = getManifestContext(platform, config, manifestConfig);
                }

                this.manifestValidator.validate(manifestConfig.name, manifestContext);

                manifestConfigs.put(manifestConfig.name, manifestContext);

                Map.Entry<File, ProGuardContext> javaExtensionsEntry = buildJavaExtension(manifest, manifestContext, rJar);
                if (javaExtensionsEntry != null) {
                    builtJars.put(javaExtensionsEntry.getKey().getAbsolutePath(), javaExtensionsEntry.getValue());
                }
            }

        } catch (IOException e) {
            throw new ExtenderException(e, processExecutor.getOutput());
        }

        return builtJars;
    }

    // arguments:
    //   extensionJarMap - a mapping from a jar file to a list of its corresponding proGuard files
    // returns:
    //   all jar files from each extension, as well as the engine defined jar files
    private List<String> getAllJars(Map<String,ProGuardContext> extensionJarMap) throws ExtenderException {
        List<String> includeJars = ExtenderUtil.getStringList(appManifestContext, "includeJars");
        List<String> excludeJars = ExtenderUtil.getStringList(appManifestContext, "excludeJars");
        //exclude fake `jar` path for extensions without java code
        excludeJars.add("(.*)/proguard_files_without_jar");

        List<String> extensionJars = getAllExtensionsLibJars();

        for (Map.Entry<String,ProGuardContext> extensionJar : extensionJarMap.entrySet()) {
            extensionJars.add(extensionJar.getKey());
        }

        HashMap<String, Object> empty = new HashMap<>();
        Map<String, Object> context = context(empty);
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
        if (platformConfig.proGuardCmd == null || platformConfig.proGuardCmd.isEmpty() || DM_DEBUG_DISABLE_PROGUARD) {
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

        HashMap<String, Object> empty = new HashMap<>();
        Map<String, Object> context = context(empty);
        context.put("jars", jarList);
        context.put("libraryjars", jarLibrariesList);
        context.put("src", allPro);
        context.put("tgt", targetFile.getAbsolutePath());
        context.put("mapping", mappingFile.getAbsolutePath());

        String command = templateExecutor.execute(platformConfig.proGuardCmd, context);

        try {
            processExecutor.execute(command);
        } catch (IOException | InterruptedException e) {
            throw new ExtenderException(e, processExecutor.getOutput());
        }

        return new AbstractMap.SimpleEntry<File, File>(targetFile, mappingFile);
    }

    public static Map<String, Object> getManifestContext(String platform, Configuration config, ManifestConfiguration manifestConfig) throws ExtenderException {
        if (manifestConfig.platforms == null) {
            return new HashMap<>();
        }

        ManifestPlatformConfig manifestPlatformConfig = manifestConfig.platforms.get(platform);

        // Check that the manifest only contains valid platforms
        final String[] allowedPlatforms = new String[]{
            "common",
            "ios", "armv7-ios","arm64-ios","x86_64-ios",
            "android", "armv7-android","arm64-android",
            "osx", "x86-osx","x86_64-osx",
            "linux", "x86-linux","x86_64-linux",
            "win32", "x86-win32","x86_64-win32",
            "web", "js-web","wasm-web",
        };
        Set<String> manifestPlatforms = new HashSet<>(manifestConfig.platforms.keySet());
        manifestPlatforms.removeAll(Arrays.asList(allowedPlatforms));
        if (!manifestPlatforms.isEmpty()) {
            throw new ExtenderException(String.format("Extension %s contains invalid platform(s): %s. Allowed platforms: %s", manifestConfig.name, manifestPlatforms.toString(), Arrays.toString(allowedPlatforms)));
        }

        if (manifestPlatformConfig != null && manifestPlatformConfig.context != null) {
            return manifestPlatformConfig.context;
        }
        return new HashMap<>();
    }

    private File buildMainDexList(List<String> jars) throws ExtenderException {

        // Find the engine libraries (**/share/java/*.jar)
        List<String> mainListJars = filterStrings(jars, ENGINE_JAR_RE);

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
        mainClassNames = filterStrings(mainClassNames, "(?:.*)\\.class$");

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

        File classesDex = new File(buildDirectory, "classes.dex");

        // The empty list is also present for backwards compatability with older build.yml
        List<String> empty_list = new ArrayList<>();

        HashMap<String, Object> empty = new HashMap<>();
        Map<String, Object> context = context(empty);
        context.put("classes_dex", classesDex.getAbsolutePath());
        context.put("classes_dex_dir", buildDirectory.getAbsolutePath());
        context.put("jars", jars);
        context.put("engineJars", empty_list);
        context.put("mainDexList", mainDexList.getAbsolutePath());

        String command = platformConfig.dxCmd;

        // Until it's part of the build.yml
        if (command.indexOf("--main-dex-list") == -1) {
            command = command.replace("--multi-dex" , "--multi-dex --main-dex-list={{mainDexList}}");
        }

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
        Map<String, Object> context = context(mergedAppContext);
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
        manifestConfigs = new HashMap<>();
        manifestFiles = new HashMap<>();
        for (File manifest : this.manifests) {
            ManifestConfiguration manifestConfig = Extender.loadYaml(this.jobDirectory, manifest, ManifestConfiguration.class);

            if (manifestConfig == null) {
                throw new ExtenderException("Missing manifest file: " + manifest.getAbsolutePath());
            }

            Map<String, Object> manifestContext = new HashMap<>();
            for (String platformAlternative : ExtenderUtil.getPlatformAlternatives(platform)) {
                Map<String, Object> ctx = getManifestContext(platformAlternative, config, manifestConfig);
                manifestContext = Extender.mergeContexts(manifestContext, ctx);
            }

            String relativePath = ExtenderUtil.getRelativePath(this.uploadDirectory, manifest);
            this.manifestValidator.validate(relativePath, manifestContext);

            // Apply any global settings to the context
            manifestContext = Extender.mergeContexts(manifestContext, appManifestContext);
            manifestContext.put("extension_name", manifestConfig.name);
            manifestContext.put("extension_name_upper", manifestConfig.name.toUpperCase());

            manifestConfigs.put(manifestConfig.name, manifestContext);
            manifestFiles.put(manifestConfig.name, manifest);
        }

        mergedAppContext = Extender.createEmptyContext(platformConfig.context);

        Set<String> keys = manifestConfigs.keySet();
        for (String k : keys) {
            Map<String, Object> extensionContext = manifestConfigs.get(k);
            mergedAppContext = Extender.mergeContexts(mergedAppContext, extensionContext);
        }

        // The final link context is a merge of the app manifest and the extension contexts
        mergedAppContext = Extender.mergeContexts(mergedAppContext, appManifestContext);

        mergedAppContext.remove("extension_name");
        mergedAppContext.remove("extension_name_upper");
    }

    private List<File> buildEngine() throws ExtenderException {
        LOGGER.info("Building engine for platform {} with extension source {}", platform, uploadDirectory);

        try {
            List<String> symbols = new ArrayList<>();

            Set<String> keys = manifestConfigs.keySet();
            for (String extensionSymbol : keys) {
                symbols.add(extensionSymbol);

                Map<String, Object> extensionContext = manifestConfigs.get(extensionSymbol);
                File manifest = manifestFiles.get(extensionSymbol);

                // TODO: Thread this step
                buildExtension(manifest, extensionContext);
            }

            File resourceFile = null;
            if (platform.endsWith("win32")) {
                resourceFile = buildWin32Resources(mergedAppContext);
            }

            List<File> exes = linkEngine(symbols, mergedAppContext, resourceFile);

            return exes;
        } catch (IOException | InterruptedException e) {
            throw new ExtenderException(e, processExecutor.getOutput());
        }
    }

    private void putLog(String msg) {
        System.out.printf(msg);
        processExecutor.putLog(msg);
    }

    private List<File> copyAndroidResourceFolders(String platform) throws ExtenderException {
        List<File> resources = getAndroidResourceFolders(platform);
        if (resources.isEmpty()) {
            return new ArrayList<>();
        }
        File packagesDir = new File(buildDirectory, "packages");
        packagesDir.mkdir();

        List<String> packagesList = new ArrayList<>();

        try {
            for (File packageResourceDir : resources) {
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

    private List<File> buildAndroid(String platform) throws ExtenderException {
        LOGGER.info("Building Android specific code");

        List<File> outputFiles = new ArrayList<>();

        File rJavaDir = generateRJava(platform, mergedAppContext);
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

        outputFiles.addAll(copyAndroidResourceFolders(platform));

        return outputFiles;
    }

    // Called for each platform, to merge the manifests into one
    private List<File> buildManifests(String platform) throws ExtenderException {
        List<File> out = new ArrayList<>();

        // prior to 1.2.165
        if (platformConfig.manifestMergeCmd == null) {
            LOGGER.info("Manifest merging not supported by this sdk");
            return out;
        }

        String manifestName = null;
        String platformName = null;
        if (platform.contains("android")) {
            manifestName = MANIFEST_ANDROID;
            platformName = "android";
        }
        else if (platform.contains("ios")) {
            manifestName = MANIFEST_IOS;
            platformName = "ios";
        }
        else if (platform.contains("osx")) {
            manifestName = MANIFEST_OSX;
            platformName = "osx";
        }
        else if (platform.contains("web")) {
            manifestName = MANIFEST_HTML5;
            platformName = "html5";
        }

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
            HashMap<String, Object> empty = new HashMap<>();
            Map<String, Object> context = context(empty);
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

    private List<File> writeLog() {
        List<File> outputFiles = new ArrayList<>();
        File logFile = new File(buildDirectory, "log.txt");
        try {
            LOGGER.info("Writing log file");
            processExecutor.writeLog(logFile);
            outputFiles.add(logFile);
        } catch (IOException e) {
            LOGGER.error("Failed to write log file to {}", logFile.getAbsolutePath());
        }
        return outputFiles;
    }

    List<File> build() throws ExtenderException {
        List<File> outputFiles = new ArrayList<>();

        outputFiles.addAll(buildManifests(platform));

        // TODO: Thread this step
        if (platform.endsWith("android")) {
            outputFiles.addAll(buildAndroid(platform));
        }
        outputFiles.addAll(buildEngine());
        outputFiles.addAll(writeLog());
        return outputFiles;
    }
}
