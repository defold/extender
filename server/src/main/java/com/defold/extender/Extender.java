package com.defold.extender;

import com.google.common.collect.ImmutableMap;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.RegexFileFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class Extender {
    private static final Logger LOGGER = LoggerFactory.getLogger(Extender.class);
    private final Configuration config;
    private final AppManifestConfiguration appManifest;
    private final String platform;
    private final File sdk;
    private final File uploadDirectory;
    private final File jobDirectory;
    private final File buildDirectory;
    private final PlatformConfig platformConfig;
    private final ExtensionManifestValidator manifestValidator;
    private final TemplateExecutor templateExecutor = new TemplateExecutor();
    private final ProcessExecutor processExecutor = new ProcessExecutor();

    private List<File> extDirs = new ArrayList<>();
    private List<File> manifests = new ArrayList<>();

    static final String FRAMEWORK_RE = "(.+)\\.framework";
    static final String JAR_RE = "(.+\\.jar)";
    static final String JS_RE = "(.+\\.js)";

    private static final String ANDROID_NDK_PATH = System.getenv("ANDROID_NDK_PATH");
    private static final String ANDROID_NDK_INCLUDE_PATH = System.getenv("ANDROID_NDK_INCLUDE");
    private static final String ANDROID_STL_INCLUDE_PATH = System.getenv("ANDROID_STL_INCLUDE");
    private static final String ANDROID_STL_ARCH_INCLUDE_PATH = System.getenv("ANDROID_STL_ARCH_INCLUDE");
    private static final String ANDROID_STL_LIB_PATH = System.getenv("ANDROID_STL_LIB");
    private static final String ANDROID_SYSROOT_PATH = System.getenv("ANDROID_SYSROOT");

    Extender(String platform, File sdk, File jobDirectory, File uploadDirectory, File buildDirectory) throws IOException, ExtenderException {
        this.jobDirectory = jobDirectory;
        this.uploadDirectory = uploadDirectory;
        this.buildDirectory = buildDirectory;

        // Read config from SDK
        this.config = Extender.loadYaml(uploadDirectory, new File(sdk.getPath() + "/extender/build.yml"), Configuration.class);

        this.platform = platform;
        this.sdk = sdk;
        this.platformConfig = getPlatformConfig();
        
        // LEGACY: Make sure the Emscripten compiler doesn't pollute the environment
        processExecutor.putEnv("EM_CACHE", buildDirectory.toString());

        processExecutor.setCwd(jobDirectory);

        if (this.platformConfig != null && this.platformConfig.env != null) {

            HashMap<String, Object> envContext = new HashMap<>();
            envContext.put("build_folder", buildDirectory);

            Set<String> keys = this.platformConfig.env.keySet();
            for (String k : keys) {
                String v = this.platformConfig.env.get(k);
                v = templateExecutor.execute(v, envContext);

                processExecutor.putEnv(k, v);
            }
        }

        Collection<File> allFiles = FileUtils.listFiles(uploadDirectory, null, true);

        List<File> appManifests = allFiles.stream().filter(f -> f.getName().equals("app.manifest")).collect(Collectors.toList());
        if (appManifests.size() > 1 ) {
            throw new ExtenderException("Only one app.manifest allowed!");
        }
        if (appManifests.isEmpty()) {
            this.appManifest = new AppManifestConfiguration();
        } else {
            this.appManifest = Extender.loadYaml(this.uploadDirectory, appManifests.get(0), AppManifestConfiguration.class);
        }

        this.manifestValidator = new ExtensionManifestValidator(new WhitelistConfig(), this.platformConfig.allowedFlags, this.platformConfig.allowedLibs);

        // Collect extension directories (used by both buildEngine and buildClassesDex)
        this.manifests = allFiles.stream().filter(f -> f.getName().equals("ext.manifest")).collect(Collectors.toList());
        this.extDirs = this.manifests.stream().map(File::getParentFile).collect(Collectors.toList());
    }

    private PlatformConfig getPlatformConfig() throws ExtenderException {
        PlatformConfig platformConfig = config.platforms.get(platform);

        if (platformConfig == null) {
            throw new ExtenderException(String.format("Unsupported platform %s", platform));
        }

        return platformConfig;
    }

    // Does a regexp match on the filename for each file found in a directory
    static List<String> collectFilesByName(File dir, String re) {
        List<String> libs = new ArrayList<>();
        if (re == null) {
            return libs;
        }
        Pattern p = Pattern.compile(re);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            for (File f : files) {
                Matcher m = p.matcher(f.getName());
                if (m.matches()) {
                    libs.add(m.group(1));
                }

            }
        }
        return libs;
    }

    // Does a regexp match on the absolute path for each file found in a directory
    static List<String> collectFilesByPath(File dir, String re) {
        List<String> libs = new ArrayList<>();
        if (re == null) {
            return libs;
        }
        Pattern p = Pattern.compile(re);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            for (File f : files) {
                Matcher m = p.matcher(f.getAbsolutePath());
                if (m.matches()) {
                    libs.add(m.group(1));
                }

            }
        }
        return libs;
    }

    private File uniqueTmpFile(String prefix, String suffix) {
        File file;
        do {
            file = new File(buildDirectory, prefix + UUID.randomUUID().toString() + suffix);
        } while (file.exists());

        return file;
    }

    private File uniqueTmpFile(String pattern) {
        File file;
        do {
            file = new File(buildDirectory, String.format(pattern, UUID.randomUUID().toString()));
        } while (file.exists());

        return file;
    }

    static <T> T loadYaml(File root, File manifest, Class<T> type) throws IOException, ExtenderException {
        String yaml = FileUtils.readFileToString(manifest);

        if (yaml.contains("\t")) {
            throw new ExtenderException("Manifest files (ext.manifest) are YAML files and cannot contain tabs. " +
                    "Indentation should be done with spaces.");
        }

        try {
            return new Yaml().loadAs(yaml, type);
        } catch(YAMLException e) {
            throw new ExtenderException(String.format("Error in file '%s': %s", ExtenderUtil.getRelativePath(root, manifest), e.toString()));
        }
    }

    static List<File> filterFiles(Collection<File> files, String re) {
        Pattern p = Pattern.compile(re);
        return files.stream().filter(f -> p.matcher(f.getName()).matches()).collect(Collectors.toList());
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
            Object v1 = context.get(k);
            Object v2 = extensionContext.get(k);

            if (!v1.getClass().equals(v2.getClass())) {
                throw new ExtenderException(String.format("Wrong manifest context variable type for %s: Expected %s, got %s: %s", k, v1.getClass().toString(), v2.getClass().toString(), v2.toString()));
            }
            if (!Extender.isListOfStrings((List<Object>) v2)) {
                throw new ExtenderException(String.format("The context variables only support strings or lists of strings. Got %s (type %s)", v2.toString(), v2.getClass().getCanonicalName()));
            }

            if (v1 instanceof List) {
                v1 = Extender.mergeLists((List<String>) v1, (List<String>) v2);
            }
            context.put(k, v1);
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
        context.put("dynamo_home", ExtenderUtil.getRelativePath(jobDirectory, sdk));
        context.put("platform", this.platform);

        if (this.platform.contains("android")) {
            context.put("android_ndk_path", ANDROID_NDK_PATH);
            context.put("android_ndk_include", ANDROID_NDK_INCLUDE_PATH);
            context.put("android_stl_include", ANDROID_STL_INCLUDE_PATH);
            context.put("android_stl_arch_include", ANDROID_STL_ARCH_INCLUDE_PATH);
            context.put("android_stl_lib", ANDROID_STL_LIB_PATH);
            context.put("android_sysroot", ANDROID_SYSROOT_PATH);
        }

        context.putAll(Extender.mergeContexts(platformConfig.context, manifestContext));

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
        return context;
    }

    private List<String> getFrameworks(File extDir) {
        List<String> frameworks = new ArrayList<>();
        frameworks.addAll(Extender.collectFilesByName(new File(extDir, "lib" + File.separator + this.platform), FRAMEWORK_RE)); // e.g. armv64-ios
        String[] platformParts = this.platform.split("-");
        if (platformParts.length == 2) {
            frameworks.addAll(Extender.collectFilesByName(new File(extDir, "lib" + File.separator + platformParts[1]), FRAMEWORK_RE)); // e.g. "ios"
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

    private List<String> getJars(File extDir) {
        List<String> jars = new ArrayList<>();
        jars.addAll(Extender.collectFilesByPath(new File(extDir, "lib" + File.separator + this.platform), JAR_RE)); // e.g. armv7-android
        String[] platformParts = this.platform.split("-");
        if (platformParts.length == 2) {
            jars.addAll(Extender.collectFilesByPath(new File(extDir, "lib" + File.separator + platformParts[1]), JAR_RE)); // e.g. "android"
        }
        return jars;
    }

    private File compileFile(int index, File extDir, File src, Map<String, Object> manifestContext) throws IOException, InterruptedException, ExtenderException {
        List<String> includes = new ArrayList<>();
        includes.add( ExtenderUtil.getRelativePath(jobDirectory, new File(extDir, "include") ) );
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
        Map<String, Object> context = context(manifestContext);
        File o = uniqueTmpFile("main_tmp", ".o");
        context.put("src", ExtenderUtil.getRelativePath(jobDirectory, maincpp));
        context.put("tgt", ExtenderUtil.getRelativePath(jobDirectory, o));
        String command = templateExecutor.execute(platformConfig.compileCmd, context);
        processExecutor.execute(command);
        return o;
    }

    private void buildExtension(File manifest, Map<String, Object> manifestContext) throws IOException, InterruptedException, ExtenderException {
        File extDir = manifest.getParentFile();
        File srcDir = new File(extDir, "src");
        Collection<File> srcFiles = new ArrayList<>();
        if (srcDir.isDirectory()) {
            srcFiles = FileUtils.listFiles(srcDir, null, true);
            srcFiles = Extender.filterFiles(srcFiles, platformConfig.sourceRe);
        }

        if (srcFiles.isEmpty()) {
            throw new ExtenderException(String.format("Extension '%s' has no source!", ExtenderUtil.getRelativePath(this.uploadDirectory, manifest) ));
        }

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

    static List<String> getAppManifestItems(AppManifestConfiguration manifest, String platform, String name) throws ExtenderException {
        List<String> items = new ArrayList<>();

        if( manifest == null || manifest.platforms == null )
            return items;

        if (manifest.platforms.containsKey("common")) {
            Object v = manifest.platforms.get("common").context.get(name);
            if( v != null ) {
                if (!Extender.isListOfStrings((List<Object>) v)) {
                    throw new ExtenderException(String.format("The context variables only support lists of strings. Got %s (type %s)", v.toString(), v.getClass().getCanonicalName()));
                }
                items.addAll((List<String>) v);
            }
        }

        if (manifest.platforms.containsKey(platform)) {
            Object v = manifest.platforms.get(platform).context.get(name);
            if( v != null ) {
                if (!Extender.isListOfStrings((List<Object>) v)) {
                    throw new ExtenderException(String.format("The context variables only support lists of strings. Got %s (type %s)", v.toString(), v.getClass().getCanonicalName()));
                }
                items.addAll((List<String>) v);
            }
        }
        return items;
    }

    private File linkEngine(List<String> symbols, Map<String, Object> manifestContext) throws IOException, InterruptedException, ExtenderException {
        File maincpp = new File(buildDirectory , "main.cpp");

        List<String> extSymbols = new ArrayList<>();
        extSymbols.addAll(symbols);

        Map<String, Object> mainContext = context(manifestContext);

        extSymbols = ExtenderUtil.pruneItems( extSymbols, getAppManifestItems(appManifest, platform, "includeSymbols"), getAppManifestItems(appManifest, platform, "excludeSymbols") );
        mainContext.put("symbols", ExtenderUtil.pruneItems( (List<String>)mainContext.get("symbols"), getAppManifestItems(appManifest, platform, "includeSymbols"), getAppManifestItems(appManifest, platform, "excludeSymbols")));
        mainContext.put("ext", ImmutableMap.of("symbols", extSymbols));

        String main = templateExecutor.execute(config.main, mainContext);
        FileUtils.writeStringToFile(maincpp, main);

        File mainObject = compileMain(maincpp, manifestContext);

        List<String> extLibs = new ArrayList<>();
        List<String> extLibPaths = new ArrayList<>(Arrays.asList(buildDirectory.toString()));
        List<String> extFrameworks = new ArrayList<>();
        List<String> extFrameworkPaths = new ArrayList<>(Arrays.asList(buildDirectory.toString()));
        List<String> extJsLibs = new ArrayList<>();

        extLibs.addAll(Extender.collectFilesByName(buildDirectory, platformConfig.stlibRe));
        for (File extDir : this.extDirs) {
            File libDir = new File(extDir, "lib" + File.separator + this.platform); // e.g. arm64-ios

            if (libDir.exists()) {
                extLibPaths.add(libDir.toString());
                extFrameworkPaths.add(libDir.toString());
            }

            extLibs.addAll(Extender.collectFilesByName(libDir, platformConfig.shlibRe));
            extLibs.addAll(Extender.collectFilesByName(libDir, platformConfig.stlibRe));
            extJsLibs.addAll(Extender.collectFilesByPath(libDir, JS_RE));

            extFrameworks.addAll(getFrameworks(extDir));

            String[] platformParts = this.platform.split("-");
            if (platformParts.length == 2) {
                File libCommonDir = new File(extDir, "lib" + File.separator + platformParts[1]); // e.g. ios

                if (libCommonDir.exists()) {
                    extLibPaths.add(libCommonDir.toString());
                    extFrameworkPaths.add(libCommonDir.toString());
                }

                extLibs.addAll(Extender.collectFilesByName(libCommonDir, platformConfig.shlibRe));
                extLibs.addAll(Extender.collectFilesByName(libCommonDir, platformConfig.stlibRe));
                extFrameworkPaths.addAll(getFrameworkPaths(extDir));
            }
        }

        extLibs = ExtenderUtil.pruneItems( extLibs, getAppManifestItems(appManifest, platform, "includeLibs"), getAppManifestItems(appManifest, platform, "excludeLibs"));
        extJsLibs = ExtenderUtil.pruneItems( extJsLibs, getAppManifestItems(appManifest, platform, "includeJsLibs"), getAppManifestItems(appManifest, platform, "excludeJsLibs"));

        File exe;
        if (platformConfig.writeExePattern != null ) {
            exe = new File(buildDirectory, platformConfig.writeExePattern);
        } else {
            exe = new File(buildDirectory, String.format("%sdmengine%s", platformConfig.exePrefix, platformConfig.exeExt)); // Legacy, remove in a few versions!
        }

        Map<String, Object> context = context(manifestContext);
        context.put("src", ExtenderUtil.getRelativePath(jobDirectory, mainObject));
        context.put("tgt", ExtenderUtil.getRelativePath(jobDirectory, exe));
        context.put("ext", ImmutableMap.of("libs", extLibs, "libPaths", extLibPaths, "frameworks", extFrameworks, "frameworkPaths", extFrameworkPaths, "jsLibs", extJsLibs));
        context.put("engineLibs", ExtenderUtil.pruneItems((List<String>) context.getOrDefault("engineLibs", new ArrayList<>()), getAppManifestItems(appManifest, platform, "includeLibs"), getAppManifestItems(appManifest, platform, "excludeLibs")) );
        context.put("engineJsLibs", ExtenderUtil.pruneItems((List<String>) context.getOrDefault("engineJsLibs", new ArrayList<>()), getAppManifestItems(appManifest, platform, "includeJsLibs"), getAppManifestItems(appManifest, platform, "excludeJsLibs")) );

        String command = templateExecutor.execute(platformConfig.linkCmd, context);
        processExecutor.execute(command);
        return exe;
    }

    private File buildRJar() throws ExtenderException {
        try {
            File rJavaDir = new File(uploadDirectory, "_app/rjava/");
            if (rJavaDir.exists() && rJavaDir.isDirectory()) {
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

                // Collect all *.java files
                Collection<File> files = FileUtils.listFiles(
                    rJavaDir,
                    new RegexFileFilter(".+\\.java"),
                    DirectoryFileFilter.DIRECTORY
                );

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

    private File buildJavaExtension(File manifest, Map<String, Object> manifestContext, File rJar) throws ExtenderException {

        if (platformConfig.javaSourceRe == null || platformConfig.javacCmd == null || platformConfig.jarCmd == null) {
            return null;
        }

        try {

            LOGGER.info("Building Java sources with extension source {}", uploadDirectory);

            // Collect all Java source files
            File extDir = manifest.getParentFile();
            File srcDir = new File(extDir, "src");
            Collection<File> javaSrcFiles = new ArrayList<>();
            if (srcDir.isDirectory()) {
                javaSrcFiles = FileUtils.listFiles(srcDir, null, true);
                javaSrcFiles = Extender.filterFiles(javaSrcFiles, platformConfig.javaSourceRe);
            }

            if (javaSrcFiles.size() == 0) {
                return null;
            }

            // Create temp working directory, which will include;
            // * classes/    - Output directory of javac compilation
            // * sources.txt - Text file with list of Java sources
            // * output.jar  - Resulting Jar file with all compiled Java classes
            // The temporary working directory should be removed when done.
            File tmpDir = uniqueTmpFile("tmp", "javac");
            tmpDir.delete();
            tmpDir.mkdir();

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

            // Get extension supplied Jar libraries
            List<String> extJars = getJars(extDir);
            for (String jarPath : extJars) {
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

            return outputJar;

        } catch (IOException | InterruptedException e) {
            throw new ExtenderException(e, processExecutor.getOutput());
        }
    }

    private List<File> buildJava(File rJar) throws ExtenderException {
        List<File> builtJars = new ArrayList<>();
        if (rJar != null) {
            builtJars.add(rJar);
        }

        try {
            Map<String, Map<String, Object>> manifestConfigs = new HashMap<>();
            for (File manifest : this.manifests) {
                ManifestConfiguration manifestConfig = Extender.loadYaml(this.uploadDirectory, manifest, ManifestConfiguration.class);

                Map<String, Object> manifestContext = new HashMap<>();
                if (manifestConfig.platforms != null) {
                    manifestContext = getManifestContext(manifestConfig);
                }

                this.manifestValidator.validate(manifestConfig.name, manifestContext);

                manifestConfigs.put(manifestConfig.name, manifestContext);

                File extensionJar = buildJavaExtension(manifest, manifestContext, rJar);
                if (extensionJar != null) {
                    builtJars.add(extensionJar);
                }
            }

        } catch (IOException e) {
            throw new ExtenderException(e, processExecutor.getOutput());
        }

        return builtJars;
    }

    private Map<String, Object> getManifestContext(ManifestConfiguration manifestConfig) throws ExtenderException {
        ManifestPlatformConfig manifestPlatformConfig = manifestConfig.platforms.get(this.platform);

        // Check that the manifest only contains valid platforms
        Set<String> allowedPlatforms = config.platforms.keySet();
        Set<String> manifestPlatforms = manifestConfig.platforms.keySet();
        manifestPlatforms.removeAll(allowedPlatforms);
        if (!manifestPlatforms.isEmpty()) {
            throw new ExtenderException(String.format("Extension %s contains invalid platform(s): %s. Allowed platforms: %s", manifestConfig.name, manifestPlatforms.toString(), allowedPlatforms.toString()));
        }

        if (manifestPlatformConfig != null) {
            return manifestPlatformConfig.context;
        }
        return new HashMap<>();
    }

    private File buildClassesDex(List<File> extraJars) throws ExtenderException {
        LOGGER.info("Building classes.dex with extension source {}", uploadDirectory);

        // To support older versions of build.yml where dxCmd is not defined:
        if (platformConfig.dxCmd == null || platformConfig.dxCmd.isEmpty()) {
            return null;
        }

        File classesDex = new File(buildDirectory, "classes.dex");

        List<String> extJars = new ArrayList<>();
        for (File extDir : this.extDirs) {
            extJars.addAll(getJars(extDir));
        }
        for (File extraJar : extraJars) {
            extJars.add(extraJar.getAbsolutePath());
        }

        HashMap<String, Object> empty = new HashMap<>();
        Map<String, Object> context = context(empty);
        context.put("classes_dex", classesDex.getAbsolutePath());
        context.put("jars", ExtenderUtil.pruneItems( extJars, getAppManifestItems(appManifest, platform, "includeJars"), getAppManifestItems(appManifest, platform, "excludeJars")));
        context.put("engineJars", ExtenderUtil.pruneItems( (List<String>)context.get("engineJars"), getAppManifestItems(appManifest, platform, "includeJars"), getAppManifestItems(appManifest, platform, "excludeJars")) );

        String command = templateExecutor.execute(platformConfig.dxCmd, context);
        try {
            processExecutor.execute(command);
        } catch (IOException | InterruptedException e) {
            throw new ExtenderException(e, processExecutor.getOutput());
        }

        return classesDex;
    }

    private void buildWin32Manifest(File exe, Map<String, Object> mergedExtensionContext) throws ExtenderException {
        LOGGER.info("Adding manifest file to engine");

        Map<String, Object> context = context(mergedExtensionContext);
        context.put("tgt", ExtenderUtil.getRelativePath(jobDirectory, exe));

        String command = templateExecutor.execute(platformConfig.mtCmd, context);
        try {
            processExecutor.execute(command);
        } catch (IOException | InterruptedException e) {
            throw new ExtenderException(e, processExecutor.getOutput());
        }
    }

    private File buildEngine() throws ExtenderException {
        LOGGER.info("Building engine for platform {} with extension source {}", platform, uploadDirectory);

        try {
            List<String> symbols = new ArrayList<>();

            Map<String, Map<String, Object>> manifestConfigs = new HashMap<>();
            for (File manifest : this.manifests) {
                ManifestConfiguration manifestConfig = Extender.loadYaml(this.uploadDirectory, manifest, ManifestConfiguration.class);

                Map<String, Object> manifestContext = new HashMap<>();
                if (manifestConfig.platforms != null) {
                    manifestContext = getManifestContext(manifestConfig);
                }

                String relativePath = ExtenderUtil.getRelativePath(this.uploadDirectory, manifest);
                this.manifestValidator.validate(relativePath, manifestContext);

                manifestConfigs.put(manifestConfig.name, manifestContext);

                symbols.add(manifestConfig.name);

                buildExtension(manifest, manifestContext);
            }

            Map<String, Object> mergedExtensionContext = Extender.createEmptyContext(platformConfig.context);

            Set<String> keys = manifestConfigs.keySet();
            for (String k : keys) {
                Map<String, Object> extensionContext = manifestConfigs.get(k);
                mergedExtensionContext = Extender.mergeContexts(mergedExtensionContext, extensionContext);
            }

            File exe = linkEngine(symbols, mergedExtensionContext);

            if (platform.endsWith("win32")) {
                buildWin32Manifest(exe, mergedExtensionContext);
            }

            return exe;
        } catch (IOException | InterruptedException e) {
            throw new ExtenderException(e, processExecutor.getOutput());
        }
    }

    List<File> build() throws ExtenderException {
        List<File> outputFiles = new ArrayList<>();

        if (platform.endsWith("android")) {
            File rJar = buildRJar();
            List<File> extraJars = buildJava(rJar);

            File classesDex = buildClassesDex(extraJars);
            if (classesDex != null) {
                outputFiles.add(classesDex);
            }
        }

        File exe = buildEngine();
        outputFiles.add(exe);

        return outputFiles;
    }
}
