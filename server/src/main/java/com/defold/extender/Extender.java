package com.defold.extender;

import com.google.common.collect.ImmutableMap;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.Collectors;

class Extender {
    private static final Logger LOGGER = LoggerFactory.getLogger(Extender.class);
    private final Configuration config;
    private final String platform;
    private final File sdk;
    private final File extensionSource;
    private final File build;
    private final PlatformConfig platformConfig;
    private final ExtensionManifestValidator manifestValidator;
    private final TemplateExecutor templateExecutor = new TemplateExecutor();
    private final ProcessExecutor processExecutor = new ProcessExecutor();

    private static final String FRAMEWORK_RE = "(.+).framework";

    private static final String ANDROID_NDK_PATH               = System.getenv("ANDROID_NDK_PATH");
    private static final String ANDROID_NDK_INCLUDE_PATH       = System.getenv("ANDROID_NDK_INCLUDE");
    private static final String ANDROID_STL_INCLUDE_PATH       = System.getenv("ANDROID_STL_INCLUDE");
    private static final String ANDROID_STL_ARCH_INCLUDE_PATH  = System.getenv("ANDROID_STL_ARCH_INCLUDE");
    private static final String ANDROID_STL_LIB_PATH           = System.getenv("ANDROID_STL_LIB");
    private static final String ANDROID_SYSROOT_PATH           = System.getenv("ANDROID_SYSROOT");

    Extender(String platform, File extensionSource, File sdk, String buildDirectory) throws IOException {
        // Read config from SDK
        InputStream configFileInputStream = Files.newInputStream(new File(sdk.getPath() + "/extender/build.yml").toPath());
        this.config = new Yaml().loadAs(configFileInputStream, Configuration.class);

        this.platform = platform;
        this.sdk = sdk;
        this.platformConfig = config.platforms.get(platform);
        this.extensionSource = extensionSource;

        Path buildPath = Paths.get(buildDirectory);
        Files.createDirectories(buildPath);
        this.build = Files.createTempDirectory(buildPath, "build").toFile();

        if (this.platformConfig == null) {
            throw new IllegalArgumentException(String.format("Unsupported platform %s", platform));
        }

        this.manifestValidator = new ExtensionManifestValidator(new WhitelistConfig(), this.platformConfig.allowedFlags, this.platformConfig.allowedLibs);
    }

    ExtensionManifestValidator getManifestValidator() {
        return manifestValidator;
    }

    static List<String> collectLibraries(File libDir, String re) {
        Pattern p = Pattern.compile(re);
        List<String> libs = new ArrayList<>();
        if (libDir.exists()) {
            File[] files = libDir.listFiles();
            for (File f : files) {
                Matcher m = p.matcher(f.getName());
                if (m.matches()) {
                    libs.add(m.group(1));
                }

            }
        }
        return libs;
    }

    private File uniqueTmpFile(File parent, String prefix, String suffix) {
        File file;
        do {
            file = new File(build, prefix + UUID.randomUUID().toString() + suffix);
        } while (file.exists());

        return file;
    }

    private ManifestConfiguration loadManifest(File manifest) throws IOException
    {
        return new Yaml().loadAs(FileUtils.readFileToString(manifest), ManifestConfiguration.class);
    }

    static List<File> filterFiles(Collection<File> files, String re)
    {
        Pattern p = Pattern.compile(re);
        List<File> filtered = files.stream().filter(f -> p.matcher( f.getName() ).matches() ).collect(Collectors.toList());
        return filtered;
    }

    // Merges two lists. Appends values of b to a. Only keeps unique values
    static List<String> mergeLists(List<String> a, List<String> b)
    {
        return Stream.concat(a.stream(), b.stream()).distinct().collect(Collectors.toList());
    }

    static boolean isListOfStrings(List<Object> list)
    {
        return list.stream().allMatch(o -> o instanceof String);
    }


    // Copies the original context, and appends the extra context's elements, if the keys and types are valid
    static Map<String, Object> mergeContexts(Map<String, Object> originalContext, Map<String, Object> extensionContext) throws ExtenderException {
        Map<String, Object> context = new HashMap<>( originalContext );

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

    static Map<String, Object> createEmptyContext(Map<String, Object> original)
    {
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
        context.put("dynamo_home", sdk.getAbsolutePath());
        context.put("platform", this.platform);

        if (this.platform.contains("android")) {
            context.put("android_ndk_path", ANDROID_NDK_PATH);
            context.put("android_ndk_include", ANDROID_NDK_INCLUDE_PATH);
            context.put("android_stl_include", ANDROID_STL_INCLUDE_PATH);
            context.put("android_stl_arch_include", ANDROID_STL_ARCH_INCLUDE_PATH);
            context.put("android_stl_lib", ANDROID_STL_LIB_PATH);
            context.put("android_sysroot", ANDROID_SYSROOT_PATH);
        }

        context.putAll(Extender.mergeContexts(platformConfig.context, manifestContext) );

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

    private List<String> getFrameworks(File extDir)
    {
        List<String> frameworks = new ArrayList<>();
        frameworks.addAll(Extender.collectLibraries(new File(extDir, "lib" + File.separator + this.platform), FRAMEWORK_RE)); // e.g. armv64-ios
        String[] platformParts = this.platform.split("-");
        if (platformParts.length == 2 ) {
            frameworks.addAll(Extender.collectLibraries(new File(extDir, "lib" + File.separator + platformParts[1]), FRAMEWORK_RE)); // e.g. "ios"
        }
        return frameworks;
    }

    private List<String> getFrameworkPaths(File extDir)
    {
        List<String> frameworkPaths = new ArrayList<>();
        File dir = new File(extDir, "lib" + File.separator + this.platform);
        if (dir.exists()) {
            frameworkPaths.add(dir.getAbsolutePath());
        }
        String[] platformParts = this.platform.split("-");
        if (platformParts.length == 2 ) {
            File dirShort = new File(extDir, "lib" + File.separator + platformParts[1]);
            if (dirShort.exists()) {
                frameworkPaths.add(dirShort.getAbsolutePath());
            }
        }
        return frameworkPaths;
    }

    private File compileFile(int index, File extDir, File src, Map<String, Object> manifestContext) throws IOException, InterruptedException, ExtenderException  {
        List<String> includes = new ArrayList<>();
        includes.add(extDir.getAbsolutePath() + File.separator + "include");
        File o = new File(build, String.format("%s_%d.o", src.getName(), index));

        List<String> frameworks = getFrameworks(extDir);
        List<String> frameworkPaths = getFrameworkPaths(extDir);

        Map<String, Object> context = context(manifestContext);
        context.put("src", src);
        context.put("tgt", o);
        context.put("ext", ImmutableMap.of("includes", includes, "frameworks", frameworks, "frameworkPaths", frameworkPaths));

        String command = templateExecutor.execute(platformConfig.compileCmd, context);
        processExecutor.execute(command);
        return o;
    }

    private File compileMain(File maincpp, Map<String, Object> manifestContext) throws IOException, InterruptedException, ExtenderException {
        Map<String, Object> context = context(manifestContext);
        File o = uniqueTmpFile(build, "main_tmp", ".o");
        context.put("src", maincpp);
        context.put("tgt", o);
        String command = templateExecutor.execute(platformConfig.compileCmd, context);
        processExecutor.execute(command);
        return o;
    }

    private File buildExtension(File manifest, Map<String, Object> manifestContext) throws IOException, InterruptedException, ExtenderException {
        File extDir = manifest.getParentFile();
        File srcDir = new File(extDir, "src");
        Collection<File> srcFiles = new ArrayList<>();
        if (srcDir.isDirectory()) {
            srcFiles = FileUtils.listFiles(srcDir, null, true);
            srcFiles = Extender.filterFiles(srcFiles, platformConfig.sourceRe);
        }
        List<String> objs = new ArrayList<>();

        int i = 0;
        for (File src : srcFiles) {
            File o = compileFile(i, extDir, src, manifestContext);
            objs.add(o.getAbsolutePath());
            i++;
        }

        File lib = uniqueTmpFile(build, "lib", ".a");
        Map<String, Object> context = context(manifestContext);
        context.put("tgt", lib);
        context.put("objs", objs);
        String command = templateExecutor.execute(platformConfig.libCmd, context);
        processExecutor.execute(command);
        return lib;
    }


    private File linkEngine(List<File> extDirs, List<String> symbols, Map<String, Object> manifestContext) throws IOException, InterruptedException, ExtenderException  {
        File maincpp = new File(build, "main.cpp");
        File exe = new File(build, String.format("%sdmengine%s", platformConfig.exePrefix, platformConfig.exeExt));

        List<String> extSymbols = new ArrayList<>();
        extSymbols.addAll(symbols);

        Map<String, Object> mainContext = context(manifestContext);
        mainContext.put("ext", ImmutableMap.of("symbols", extSymbols));

        String main = templateExecutor.execute(config.main, mainContext);
        FileUtils.writeStringToFile(maincpp, main);

        File mainObject = compileMain(maincpp, manifestContext);

        List<String> extLibs = new ArrayList<>();
        List<String> extLibPaths = new ArrayList<>(Arrays.asList(build.toString()));
        List<String> extFrameworks = new ArrayList<>();
        List<String> extFrameworkPaths = new ArrayList<>(Arrays.asList(build.toString()));

        extLibs.addAll(Extender.collectLibraries(build, platformConfig.stlibRe));
        for (File extDir : extDirs) {
            File libDir = new File(extDir, "lib" + File.separator + this.platform); // e.g. arm64-ios

            if (libDir.exists()) {
                extLibPaths.add(libDir.toString());
                extFrameworkPaths.add(libDir.toString());
            }

            extLibs.addAll(Extender.collectLibraries(libDir, platformConfig.shlibRe));
            extLibs.addAll(Extender.collectLibraries(libDir, platformConfig.stlibRe));
            extFrameworks.addAll( getFrameworks(extDir) );

            String[] platformParts = this.platform.split("-");
            if (platformParts.length == 2 ) {
                File libCommonDir = new File(extDir, "lib" + File.separator + platformParts[1]); // e.g. ios

                if (libCommonDir.exists()) {
                    extLibPaths.add(libCommonDir.toString());
                    extFrameworkPaths.add(libCommonDir.toString());
                }

                extLibs.addAll(Extender.collectLibraries(libCommonDir, platformConfig.shlibRe));
                extLibs.addAll(Extender.collectLibraries(libCommonDir, platformConfig.stlibRe));
                extFrameworkPaths.addAll( getFrameworkPaths(extDir) );
            }
        }

        Map<String, Object> context = context(manifestContext);
        context.put("src", mainObject);
        context.put("tgt", exe.getAbsolutePath());
        context.put("ext", ImmutableMap.of("libs", extLibs, "libPaths", extLibPaths, "frameworks", extFrameworks, "frameworkPaths", extFrameworkPaths));

        String command = templateExecutor.execute(platformConfig.linkCmd, context);
        processExecutor.execute(command);

        return exe;
    }

    private Map<String, Object> getManifestContext(ManifestConfiguration manifestConfig ) throws ExtenderException
    {
        ManifestPlatformConfig manifestPlatformConfig = manifestConfig.platforms.get(this.platform);

        // Check that the manifest only contains valid platforms
        Set<String> allowedPlatforms = config.platforms.keySet();
        Set<String> manifestPlatforms = manifestConfig.platforms.keySet();
        manifestPlatforms.removeAll(allowedPlatforms);
        if( !manifestPlatforms.isEmpty() ) {
            throw new ExtenderException(String.format("Extension %s contains invalid platform(s): %s. Allowed platforms: %s", manifestConfig.name, manifestPlatforms.toString(), allowedPlatforms.toString()) );
        }

        if( manifestPlatformConfig != null ) {
            return manifestPlatformConfig.context;
        }
        return new HashMap<>();
    }

    File buildEngine() throws ExtenderException {
        LOGGER.info("Building engine for platform {} with extension source {}", platform, extensionSource);

        try {
            Collection<File> allFiles = FileUtils.listFiles(extensionSource, null, true);
            List<File> manifests = allFiles.stream().filter(f -> f.getName().equals("ext.manifest")).collect(Collectors.toList());
            List<File> extDirs = manifests.stream().map(File::getParentFile).collect(Collectors.toList());

            List<String> symbols = new ArrayList<>();

            Map<String, Map<String, Object> > manifestConfigs = new HashMap<>();
            for (File manifest : manifests) {
                ManifestConfiguration manifestConfig = loadManifest(manifest);

                Map<String, Object> manifestContext = new HashMap<String, Object>();
                if( manifestConfig.platforms != null ) {
                    manifestContext = getManifestContext(manifestConfig);
                }

                this.manifestValidator.validate(manifestConfig.name, manifestContext);
                
                manifestConfigs.put(manifestConfig.name, manifestContext);

                symbols.add(manifestConfig.name);

                buildExtension(manifest, manifestContext);
            }

            Map<String, Object> mergedExtensionContext = Extender.createEmptyContext( platformConfig.context );

            Set<String> keys = manifestConfigs.keySet();
            for (String k : keys) {
                Map<String, Object> extensionContext = manifestConfigs.get(k);
                mergedExtensionContext = Extender.mergeContexts(mergedExtensionContext, extensionContext);
            }

            return linkEngine(extDirs, symbols, mergedExtensionContext);
        } catch (IOException | InterruptedException e) {
            throw new ExtenderException(e, processExecutor.getOutput());
        }
    }

    void dispose() throws IOException {
        FileUtils.deleteDirectory(build);
    }
}
