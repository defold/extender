package com.defold.extender.services;

import com.defold.extender.ExtenderException;
import com.defold.extender.ExtenderUtil;
import com.defold.extender.ProcessExecutor;
import com.defold.extender.TemplateExecutor;
import com.defold.extender.metrics.MetricsWriter;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.lang.StringBuilder;


@Service
public class CocoaPodsService {

    public class ResolvedPods {
        public List<PodSpec> pods = new ArrayList<>();
        public File podsDir;
        public File frameworksDir;
        public String platformMinVersion;

        // In the functions beloe we also get the values from the parent spec
        // if one exists. A parent spec inherits all of its subspecs (unless a 
        // default_spec is set). And the subspecs inherit the values of their
        // parent
        // https://guides.cocoapods.org/syntax/podspec.html#subspec

        private void addPodLibs(String platform, PodSpec pod, Set<String> libs) {
            libs.addAll(pod.libraries.get(platform));
            if (pod.parentSpec != null) addPodLibs(platform, pod.parentSpec, libs);
        }
        public List<String> getAllPodLibs(String platform) {
            Set<String> libs = new LinkedHashSet<>();
            for (PodSpec pod : pods) {
                addPodLibs(platform, pod, libs);
            }
            return new ArrayList<String>(libs);
        }

        private void addPodLinkFlags(String platform, PodSpec pod, Set<String> flags) {
            flags.addAll(pod.linkflags.get(platform));
            if (pod.parentSpec != null) addPodLinkFlags(platform, pod.parentSpec, flags);
        }
        public List<String> getAllPodLinkFlags(String platform) {
            Set<String> flags = new LinkedHashSet<>();
            for (PodSpec pod : pods) {
                addPodLinkFlags(platform, pod, flags);
            }
            return new ArrayList<String>(flags);
        }

        private void addPodResources(String platform, PodSpec pod, Set<String> resources) {
            String podDir = pod.dir.getAbsolutePath();
            for (String resource : pod.resources.get(platform)) {
                resources.add(podDir + "/" + resource);
            }
            if (pod.parentSpec != null) addPodResources(platform, pod.parentSpec, resources);
        }
        public List<String> getAllPodResources(String platform) {
            Set<String> resources = new LinkedHashSet<>();
            for (PodSpec pod : pods) {
                addPodResources(platform, pod, resources);
            }
            return new ArrayList<String>(resources);
        }

        private void addPodFrameworks(String platform, PodSpec pod, Set<String> frameworks) {
            frameworks.addAll(pod.frameworks.get(platform));
            if (pod.parentSpec != null) addPodFrameworks(platform, pod.parentSpec, frameworks);
        }
        public List<String> getAllPodFrameworks(String platform) {
            Set<String> frameworks = new LinkedHashSet<>();
            for (PodSpec pod : pods) {
                addPodFrameworks(platform, pod, frameworks);
            }
            return new ArrayList<String>(frameworks);
        }

        private void addPodWeakFrameworks(String platform, PodSpec pod, Set<String> weakFrameworks) {
            weakFrameworks.addAll(pod.weak_frameworks.get(platform));
            if (pod.parentSpec != null) addPodWeakFrameworks(platform, pod.parentSpec, weakFrameworks);
        }
        public List<String> getAllPodWeakFrameworks(String platform) {
            Set<String> weakFrameworks = new LinkedHashSet<>();
            for (PodSpec pod : pods) {
                addPodWeakFrameworks(platform, pod, weakFrameworks);
            }
            return new ArrayList<String>(weakFrameworks);
        }
    }

    public class LanguageSet {
        public Set<String> c = new LinkedHashSet<>();
        public Set<String> cpp = new LinkedHashSet<>();
        public Set<String> objc = new LinkedHashSet<>();
        public Set<String> objcpp = new LinkedHashSet<>();

        public void add(String value) {
            c.add(value);
            cpp.add(value);
            objc.add(value);
            objcpp.add(value);
        }

        public void addAll(List<String> values) {
            for (String v : values) {
                add(v);
            }
        }
        public void addAll(LanguageSet set) {
            c.addAll(set.c);
            cpp.addAll(set.cpp);
            objc.addAll(set.objc);
            objcpp.addAll(set.objcpp);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("c: " + c);
            sb.append("cpp: " + cpp);
            sb.append("objc: " + objc);
            sb.append("objcpp: " + objcpp);
            return sb.toString();
        }
    }

    public class PlatformAndLanguageSet {
        public LanguageSet ios = new LanguageSet();
        public LanguageSet osx = new LanguageSet();

        public void addAll(PlatformAndLanguageSet v) {
            ios.addAll(v.ios);
            osx.addAll(v.osx);
        }
        public void addAll(List<String> values) {
            for (String v :  values) {
                ios.add(v);
                osx.add(v);
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("ios:\n" + ios.toString());
            sb.append("osx:\n" + osx.toString());
            return sb.toString();
        }
    }

    public class PlatformSet {
        public Set<String> ios = new LinkedHashSet<>();
        public Set<String> osx = new LinkedHashSet<>();

        public void addAll(PlatformSet v) {
            ios.addAll(v.ios);
            osx.addAll(v.osx);
        }

        public void addAll(List<String> values) {
            for (String v :  values) {
                ios.add(v);
                osx.add(v);
            }
        }

        public Set<String> get(String platform) {
            if (platform.contains("ios")) {
                return ios;
            }
            else if (platform.contains("osx")) {
                return osx;
            }
            return new LinkedHashSet<String>();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("ios:\n" + ios.toString());
            sb.append("osx:\n" + osx.toString());
            return sb.toString();
        }
    }

    public class PodSpec {
        public String name = "";
        public String version = "";
        public String originalJSON = "";
        public String iosversion = "";
        public String osxversion = "";
        public Set<File> sourceFiles = new LinkedHashSet<>();
        public Set<File> includePaths = new LinkedHashSet<>();
        public PodSpec parentSpec = null;
        public List<PodSpec> subspecs = new ArrayList<>();

        public PlatformAndLanguageSet flags = new PlatformAndLanguageSet();
        public PlatformSet defines = new PlatformSet();
        public PlatformSet linkflags = new PlatformSet();
        public Set<String> vendoredframeworks = new LinkedHashSet<>();
        public PlatformSet weak_frameworks = new PlatformSet();
        public PlatformSet resources = new PlatformSet();
        public PlatformSet frameworks = new PlatformSet();
        public PlatformSet libraries = new PlatformSet();
        public File dir;

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(name + ":" + version + "\n");
            sb.append("  dir: " + dir + "\n");
            sb.append("  src: " + sourceFiles + "\n");
            sb.append("  includes: " + includePaths + "\n");
            sb.append("  defines: " + defines + "\n");
            sb.append("  flags: " + flags + "\n");
            sb.append("  linkflags: " + linkflags + "\n");
            sb.append("  weak_frameworks: " + weak_frameworks + "\n");
            sb.append("  resources: " + resources + "\n");
            sb.append("  frameworks: " + frameworks + "\n");
            sb.append("  vendoredframeworks: " + vendoredframeworks + "\n");
            sb.append("  libraries: " + libraries + "\n");
            sb.append("  parentSpec: " + ((parentSpec != null) ? parentSpec.name : "null") + "\n");
            for (PodSpec sub : subspecs) {
                sb.append("  subspec: " + sub.name + "\n");
            }
            return sb.toString();
   
        }
    }

    private static final String PODFILE_TEMPLATE_PATH = System.getenv("EXTENSION_PODFILE_TEMPLATE");
    private static final Logger LOGGER = LoggerFactory.getLogger(CocoaPodsService.class);
    private final TemplateExecutor templateExecutor = new TemplateExecutor();

    private final String podfileTemplateContents;

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
        this.podfileTemplateContents = readFile(PODFILE_TEMPLATE_PATH);

        LOGGER.info("CocoaPodsService service");
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
    private int compareVersions(String version1, String version2) {
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
     * @return Minimum platform version
     */
    private String createMainPodFile(List<File> podFiles, File jobDirectory, File workingDir, String platform, Map<String, Object> jobEnvContext) throws IOException {
        File mainPodFile = new File(workingDir, "Podfile");

        // This file might exist when testing and debugging the extender using a debug job folder
        podFiles.remove(mainPodFile);

        String mainPodfilePlatformVersion = (platform.contains("ios") ? 
            jobEnvContext.get("env.IOS_VERSION_MIN").toString(): 
            jobEnvContext.get("env.MACOS_VERSION_MIN").toString());
        String mainPodfilePlatform = (platform.contains("ios") ? "ios" : "osx");

        // Load all Podfiles
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
                if (line.startsWith("platform :")) {
                    String version = line.replaceFirst("platform :ios|osx", "").replace(",", "").replace("'", "").trim();
                    if (!version.isEmpty() && (compareVersions(version, mainPodfilePlatformVersion) > 0)) {
                        mainPodfilePlatformVersion = version;
                    }
                }
                else {
                    pods.add(line);
                }
            }
        }

        // Create main Podfile contents
        HashMap<String, Object> envContext = new HashMap<>();
        envContext.put("PLATFORM", mainPodfilePlatform);
        envContext.put("PLATFORM_VERSION", mainPodfilePlatformVersion);
        envContext.put("PODS", pods);
        String mainPodFileContents = templateExecutor.execute(podfileTemplateContents, envContext);
        LOGGER.info("Created main Podfile:\n{}", mainPodFileContents);

        Files.write(mainPodFile.toPath(), mainPodFileContents.getBytes());

        return mainPodfilePlatformVersion;
    }

    /**
     * Return a list of source files for a pod
     * @param pod
     * @param pattern Source file pattern (glob format)
     * @return List of files
     */
    private List<File> findPodSourceFiles(PodSpec pod, String pattern) {
        List<File> srcFiles = new ArrayList<>();

        String absolutePatternPath = pod.dir.getAbsolutePath() + File.separator + pattern;
        List<File> podSrcFiles = ExtenderUtil.listFilesGlob(pod.dir, absolutePatternPath);
        for (File podSrcFile : podSrcFiles) {
            if (!podSrcFile.getName().endsWith(".h")) {
                srcFiles.add(podSrcFile);
            }
        }

        return srcFiles;
    }    

    /**
     * Return a list of include paths for a pod
     * @param pod
     * @param pattern Source file pattern (glob format)
     * @return List of include paths
     */
    private List<File> findPodIncludePaths(PodSpec pod, String pattern) {
        Set<File> includePaths = new LinkedHashSet<>();

        String absolutePatternPath = pod.dir.getAbsolutePath() + File.separator + pattern;
        List<File> podSrcFiles = ExtenderUtil.listFilesGlob(pod.dir, absolutePatternPath);
        for (File podSrcFile : podSrcFiles) {
            if (podSrcFile.getName().endsWith(".h")) {
                File podIncludeDir = podSrcFile.getParentFile();
                if (podIncludeDir != null) {
                    includePaths.add(podIncludeDir);
                    File podIncludeParentDir = podIncludeDir.getParentFile();
                    if (podIncludeParentDir != null) {
                        includePaths.add(podIncludeParentDir);
                    }
                }
            }
        }

        return new ArrayList<File>(includePaths);
    }

    // copy the files and folders of a directory recursively
    // the function will also resolve symlinks while copying files and folders
    private void copyDirectoryRecursively(File fromDir, File toDir) throws IOException {
        toDir.mkdirs();
        File resolved = fromDir.toPath().toRealPath().toFile();
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

    // helper function to copy the frameworks, static libs and headers from a
    // platform architecture folder (inside an .xcframework)
    private void copyPodFrameworksFromArchitectureDir(File architectureDir, File destFrameworkDir, File destHeaderDir) throws IOException {
        File[] files = architectureDir.listFiles();
        for (File file : files) {
            String filename = file.getName();
            if (filename.endsWith(".framework")) {
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
     */
    private void copyPodFrameworks(List<PodSpec> pods, File frameworksDir) throws IOException {
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
            for (String framework : pod.vendoredframeworks) {
                File frameworkDir = new File(pod.dir, framework);
                String frameworkName = frameworkDir.getName().replace(".xcframework", "");
                
                File arm64_armv7FrameworkDir = new File(frameworkDir, "ios-arm64_armv7");
                File arm64FrameworkDir = new File(frameworkDir, "ios-arm64");
                if (arm64_armv7FrameworkDir.exists()) {
                    copyPodFrameworksFromArchitectureDir(arm64_armv7FrameworkDir, armLibDir, armHeaderDir);
                }
                else if (arm64FrameworkDir.exists()) {
                    copyPodFrameworksFromArchitectureDir(arm64FrameworkDir, armLibDir, armHeaderDir);
                }
                
                File arm64_i386_x86Framework = new File(frameworkDir, "ios-arm64_i386_x86_64-simulator");
                File arm64_x86Framework = new File(frameworkDir, "ios-arm64_x86_64-simulator");
                if (arm64_i386_x86Framework.exists()) {
                    copyPodFrameworksFromArchitectureDir(arm64_i386_x86Framework, x86LibDir, x86HeaderDir);
                }
                else if (arm64_x86Framework.exists()) {
                    copyPodFrameworksFromArchitectureDir(arm64_x86Framework, x86LibDir, x86HeaderDir);
                }
            }
        }
    }

    private JSONObject parseJson(String json) throws ExtenderException {
        try {
            JSONParser parser = new JSONParser();
            return (JSONObject)parser.parse(json);
        }
        catch (ParseException e) {
            e.printStackTrace();
            throw new ExtenderException(e, "Failed to parse json. " + e);
        }
    }

    // get the value of a key as a JSONArray even it is a single value
    private JSONArray getAsJSONArray(JSONObject o, String key) {
        Object value = o.get(key);
        if (value instanceof JSONArray) {
            return (JSONArray)value;
        }
        JSONArray a = new JSONArray();
        if (value != null) {
            a.add(value.toString());
        }
        return a;
    }

    // get a string value from a JSON object and split it into a list using space character as delimiter
    // will return an empty list if the value does not exist
    private List<String> getAsSplitString(JSONObject o, String key) {
        String value = (String)o.get(key);
        if (value == null || value.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.asList(value.split(" "));
    }

    // check if a string value on a JSON object exists
    // will return false if the value doesn't exist or is an empty string
    private boolean hasString(JSONObject o, String key) {
        String value = (String)o.get(key);
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        return true;
    }

    // get a string value from a JSON object
    // will return a default value if the value doesn't exist or is an empty string
    private String getAsString(JSONObject o, String key, String defaultValue) {
        String value = (String)o.get(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value;
    }
    // get a string value from a JSON object
    // will return null if the value doesn't exist or is an empty string
    private String getAsString(JSONObject o, String key) {
        return getAsString(o, key, null);
    }

    // check if the value for a specific key on a json object matches an expected value
    private boolean compareString(JSONObject o, String key, String expected) {
        String value = (String)o.get(key);
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        return value.equals(expected);
    }

    private void parseConfig(JSONObject config, LanguageSet flags, Set<String> linkflags, Set<String> defines) {
        // https://pewpewthespells.com/blog/buildsettings.html
        // defines
        if (hasString(config, "GCC_PREPROCESSOR_DEFINITIONS")) {
            defines.addAll(getAsSplitString(config, "GCC_PREPROCESSOR_DEFINITIONS"));
        }
        // linker flags
        if (hasString(config, "OTHER_LDFLAGS")) {
            linkflags.addAll(getAsSplitString(config, "OTHER_LDFLAGS"));
        }
        // compiler flags
        if (hasString(config, "CLANG_CXX_LANGUAGE_STANDARD")) {
            String cppStandard = getAsString(config, "CLANG_CXX_LANGUAGE_STANDARD", "compiler-default");
            String compilerFlag = "";
            switch (cppStandard) {
                case "c++98":   compilerFlag = "-std=c++98"; break;
                case "c++0x":   compilerFlag = "-std=c++11"; break;
                case "gnu++0x": compilerFlag = "-std=gnu++11"; break;
                case "c++14":   compilerFlag = "-std=c++1y"; break;
                case "gnu++14": compilerFlag = "-std=gnu++1y"; break;
                case "gnu++98": 
                case "compiler-default": 
                default:  compilerFlag = "-std=gnu++98"; break;
            }
            flags.cpp.add(compilerFlag);
            flags.objcpp.add(compilerFlag);
        }
        if (hasString(config, "GCC_C_LANGUAGE_STANDARD")) {
            String cStandard = getAsString(config, "GCC_C_LANGUAGE_STANDARD", "compiler-default");
            String compilerFlag = "";
            switch (cStandard) {
                case "ansi":  compilerFlag = "-ansi"; break;
                case "c89":   compilerFlag = "-std=c89"; break;
                case "gnu89": compilerFlag = "-std=gnu89"; break;
                case "c99":   compilerFlag = "-std=c99"; break;
                case "c11":   compilerFlag = "-std=c11"; break;
                case "gnu11": compilerFlag = "-std=gnu11"; break;
                case "gnu99":
                case "compiler-default": 
                default:  compilerFlag = "-std=gnu99"; break;
            }
            flags.c.add(compilerFlag);
        }
        if (hasString(config, "CLANG_CXX_LIBRARY")) {
            String stdLib = getAsString(config, "CLANG_CXX_LIBRARY", "compiler-default");
            String stdLibFlag = "";
            switch (stdLib) {
                case "libc++": stdLibFlag = "-stdlib=libc++"; break;
                case "libstdc++":
                case "compiler-default":
                default: stdLibFlag = "-stdlib=libstdlibc++"; break;
            }
            flags.cpp.add(stdLibFlag);
            flags.objcpp.add(stdLibFlag);
        }
        if (compareString(config, "GCC_ENABLE_CPP_EXCEPTIONS", "YES")) {
            flags.cpp.add("-fcxx-exceptions");
        }
        if (compareString(config, "GCC_ENABLE_CPP_EXCEPTIONS", "NO")) {
            flags.cpp.add("-fno-cxx-exceptions");
        }
        if (compareString(config, "GCC_ENABLE_EXCEPTIONS", "YES")) {
            flags.add("-fexceptions");
        }
        if (compareString(config, "GCC_ENABLE_EXCEPTIONS", "NO")) {
            flags.add("-fno-exceptions");
        }
        if (compareString(config, "GCC_ENABLE_OBJC_EXCEPTIONS", "YES")) {
            flags.objc.add("-fobjc-exceptions");
            flags.objcpp.add("-fobjc-exceptions");
        }
        if (compareString(config, "GCC_ENABLE_OBJC_EXCEPTIONS", "NO")) {
            flags.objc.add("-fno-objc-exceptions");
            flags.objcpp.add("-fno-objc-exceptions");
        }
        if (compareString(config, "GCC_ENABLE_CPP_RTTI", "YES")) {
            flags.cpp.add("-frtti");
        }
        if (compareString(config, "GCC_ENABLE_CPP_RTTI", "NO")) {
            flags.cpp.add("-fno-rtti");
        }
        if (compareString(config, "GCC_ENABLE_OBJC_GC", "supported")) {
            flags.objc.add("-fobjc-gc");
            flags.objcpp.add("-fobjc-gc");
        }
        if (compareString(config, "GCC_ENABLE_OBJC_GC", "required")) {
            flags.objc.add("-fobjc-gc-only");
            flags.objcpp.add("-fobjc-gc-only");
        }
        if (compareString(config, "GCC_ENABLE_ASM_KEYWORD", "YES")) {
            flags.add("-fasm");
        }
        if (compareString(config, "GCC_ENABLE_ASM_KEYWORD", "NO")) {
            flags.add("-fno-asm");
        }
    }

    private void parseMultiPlatformConfig(PodSpec spec, JSONObject config) {
        if (config != null) {
            parseConfig(config, spec.flags.ios, spec.linkflags.ios, spec.defines.ios);
            parseConfig(config, spec.flags.osx, spec.linkflags.osx, spec.defines.osx);
            JSONObject iosConfig = (JSONObject)config.get("ios");
            JSONObject osxConfig = (JSONObject)config.get("ios");
            if (iosConfig != null) parseConfig(iosConfig, spec.flags.ios, spec.linkflags.ios, spec.defines.ios);
            if (osxConfig != null) parseConfig(osxConfig, spec.flags.osx, spec.linkflags.osx, spec.defines.osx);
        }
    }

    // https://guides.cocoapods.org/syntax/podspec.html
    private PodSpec createPodSpec(JSONObject specJson, PodSpec parent, File podsDir, Map<String, Object> jobEnvContext) throws ExtenderException {
        PodSpec spec = new PodSpec();
        spec.name = (String)specJson.get("name");
        spec.version = (parent == null) ? (String)specJson.get("version") : parent.version;
        spec.dir = (parent == null) ? new File(podsDir, spec.name) : parent.dir;
        spec.parentSpec = parent;

        // inherit flags and defines from the parent
        if (parent != null) {
            spec.flags.ios.addAll(parent.flags.ios);
            spec.flags.osx.addAll(parent.flags.osx);
            spec.defines.ios.addAll(parent.defines.ios);
            spec.defines.osx.addAll(parent.defines.osx);
            spec.linkflags.ios.addAll(parent.linkflags.ios);
            spec.linkflags.ios.addAll(parent.linkflags.ios);
        }

        // platform versions
        JSONObject platforms = (JSONObject)specJson.get("platforms");
        if (platforms != null) {
            spec.iosversion = (String)platforms.getOrDefault("ios", jobEnvContext.get("env.IOS_VERSION_MIN"));
        }
        if (platforms != null) {
            spec.osxversion = (String)platforms.getOrDefault("osx", jobEnvContext.get("env.MACOS_VERSION_MIN"));
        }

        // for multi platform settings
        JSONObject ios = (JSONObject)specJson.get("ios");
        JSONObject osx = (JSONObject)specJson.get("osx");

        // flags and defines
        // https://guides.cocoapods.org/syntax/podspec.html#pod_target_xcconfig
        // https://guides.cocoapods.org/syntax/podspec.html#user_target_xcconfig
        parseMultiPlatformConfig(spec, (JSONObject)specJson.get("pod_target_xcconfig"));
        parseMultiPlatformConfig(spec, (JSONObject)specJson.get("user_target_xcconfig")); // not recommended for use but we need to handle it
        parseMultiPlatformConfig(spec, (JSONObject)specJson.get("xcconfig"));  // undocumented but used by some pods

        // requires_arc flag
        // The 'requires_arc' option can also be a file pattern string or array
        // of files where arc should be enabled. See:
        // https://guides.cocoapods.org/syntax/podspec.html#requires_arc
        //
        // This is currently not supported and the presence of a string or array
        // will be treated as the default value (ie true)
        Boolean requiresArc = true;
        Object requiresArcObject = specJson.get("requires_arc");
        if (requiresArcObject instanceof Boolean) requiresArc = (Boolean)requiresArcObject;
        spec.flags.ios.objc.add((requiresArc == null || requiresArc == true) ? "-fobjc-arc" : "-fno-objc-arc");
        spec.flags.ios.objcpp.add((requiresArc == null || requiresArc == true) ? "-fobjc-arc" : "-fno-objc-arc");
        spec.flags.osx.objc.add((requiresArc == null || requiresArc == true) ? "-fobjc-arc" : "-fno-objc-arc");
        spec.flags.osx.objcpp.add((requiresArc == null || requiresArc == true) ? "-fobjc-arc" : "-fno-objc-arc");
        
        // compiler flags
        // https://guides.cocoapods.org/syntax/podspec.html#compiler_flags
        spec.flags.ios.addAll(getAsSplitString(specJson, "compiler_flags"));
        spec.flags.osx.addAll(getAsSplitString(specJson, "compiler_flags"));
        spec.flags.ios.c.add("--language=c");
        spec.flags.osx.c.add("--language=c");
        spec.flags.ios.cpp.add("--language=c++");
        spec.flags.osx.cpp.add("--language=c++");
        spec.flags.ios.objc.add("--language=objective-c");
        spec.flags.osx.objc.add("--language=objective-c");
        spec.flags.ios.objcpp.add("--language=objective-c++");
        spec.flags.osx.objcpp.add("--language=objective-c++");
        if (ios != null) spec.flags.ios.addAll(getAsJSONArray(ios, "compiler_flags"));
        if (osx != null) spec.flags.osx.addAll(getAsJSONArray(osx, "compiler_flags"));

        // resources
        // https://guides.cocoapods.org/syntax/podspec.html#resources
        spec.resources.addAll(getAsJSONArray(specJson, "resource"));
        spec.resources.addAll(getAsJSONArray(specJson, "resources"));
        if (ios != null) spec.resources.ios.addAll(getAsJSONArray(ios, "resource"));
        if (osx != null) spec.resources.osx.addAll(getAsJSONArray(osx, "resource"));
        if (ios != null) spec.resources.ios.addAll(getAsJSONArray(ios, "resources"));
        if (osx != null) spec.resources.osx.addAll(getAsJSONArray(osx, "resources"));

        // frameworks
        // https://guides.cocoapods.org/syntax/podspec.html#frameworks
        spec.frameworks.addAll(getAsJSONArray(specJson, "frameworks"));
        if (ios != null) spec.frameworks.ios.addAll(getAsJSONArray(ios, "frameworks"));
        if (osx != null) spec.frameworks.osx.addAll(getAsJSONArray(osx, "frameworks"));

        // weak frameworks
        // https://guides.cocoapods.org/syntax/podspec.html#weak_frameworks
        spec.weak_frameworks.addAll(getAsJSONArray(specJson, "weak_frameworks"));
        if (ios != null) spec.weak_frameworks.ios.addAll(getAsJSONArray(ios, "weak_frameworks"));
        if (osx != null) spec.weak_frameworks.osx.addAll(getAsJSONArray(osx, "weak_frameworks"));

        // vendored_frameworks
        // https://guides.cocoapods.org/syntax/podspec.html#vendored_frameworks
        JSONArray vendored = getAsJSONArray(specJson, "vendored_frameworks");
        if (vendored != null) {
            spec.vendoredframeworks.addAll(vendored);
        }
        if (ios != null) {
            JSONArray ios_vendored = getAsJSONArray(ios, "vendored_frameworks");
            if (ios_vendored != null) {
                spec.vendoredframeworks.addAll(ios_vendored);
            }
        }
        if (osx != null) {
            JSONArray osx_vendored = getAsJSONArray(osx, "vendored_frameworks");
            if (osx_vendored != null) {
                spec.vendoredframeworks.addAll(osx_vendored);
            }
        }

        // libraries
        // https://guides.cocoapods.org/syntax/podspec.html#libraries
        spec.libraries.addAll(getAsJSONArray(specJson, "libraries"));
        if (ios != null) spec.libraries.ios.addAll(getAsJSONArray(ios, "libraries"));
        if (osx != null) spec.libraries.osx.addAll(getAsJSONArray(osx, "libraries"));

        // parse subspecs
        // https://guides.cocoapods.org/syntax/podspec.html#subspec
        JSONArray defaultSubspecs = getAsJSONArray(specJson, "default_subspecs");
        JSONArray subspecs = getAsJSONArray(specJson, "subspecs");
        if (subspecs != null) {
            Iterator<JSONObject> it = subspecs.iterator();
            while (it.hasNext()) {
                JSONObject o = it.next();
                PodSpec subSpec = createPodSpec(o, spec, podsDir, jobEnvContext);
                if ((defaultSubspecs != null) && defaultSubspecs.contains(subSpec.name)) {
                    spec.subspecs.add(subSpec);
                }
                else {
                    spec.subspecs.add(subSpec);
                }
            }
        }

        // find source and header files
        // https://guides.cocoapods.org/syntax/podspec.html#source_files
        List<String> sourceFilePatterns = new ArrayList<>();
        JSONArray sourceFiles = getAsJSONArray(specJson, "source_files");
        if (sourceFiles != null) {
            Iterator<String> it = sourceFiles.iterator();
            while (it.hasNext()) {
                String path = it.next();
                spec.sourceFiles.addAll(findPodSourceFiles(spec, path));
                spec.includePaths.addAll(findPodIncludePaths(spec, path));
                // Cocoapods uses Ruby where glob patterns are treated slightly differently:
                // Ruby: foo/**/*.h will find .h files in any subdirectory of foo AND in foo/
                // Java: foo/**/*.h will find .h files in any subdirectory of foo but NOT in foo/
                if (path.contains("/**/")) {
                    path = path.replaceFirst("\\/\\*\\*\\/", "/");
                    spec.sourceFiles.addAll(findPodSourceFiles(spec, path));
                    spec.includePaths.addAll(findPodIncludePaths(spec, path));
                }
            }
        }

        return spec;
    }

    private PodSpec createPodSpec(String specJson, File podsDir, Map<String, Object> jobEnvContext) throws ExtenderException {
        PodSpec spec = createPodSpec(parseJson(specJson), null, podsDir, jobEnvContext);
        spec.originalJSON = specJson;
        return spec;
    }

    /**
     * Install pods from a podfile and create PodSpec instances for each installed pod.
     * @param workingDir Directory where to install pods. The directory must contain a valid Podfile
     * @param jobEnvContext Job environment context which contains all the job environment variables with `env.*` keys
     * @return A list of PodSpec instances for the installed pods
     */
    private List<PodSpec> installPods(File workingDir, Map<String, Object> jobEnvContext) throws IOException, ExtenderException {
        LOGGER.info("Installing pods");
        File podFile = new File(workingDir, "Podfile");
        if (!podFile.exists()) {
            throw new ExtenderException("Unable to find Podfile " + podFile);
        }
        File dir = podFile.getParentFile();
        String log = execCommand("pod install  --repo-update --verbose", workingDir);
        LOGGER.info("\n" + log);

        File podFileLock = new File(workingDir, "Podfile.lock");
        if (!podFileLock.exists()) {
            throw new ExtenderException("Unable to find Podfile.lock in directory " + dir);
        }
        LOGGER.info("Podfile.lock:\n{}", FileUtils.readFileToString(podFileLock));

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
        Set<String> pods = new LinkedHashSet<>();
        while (!lines.isEmpty()) if (lines.remove(0).startsWith("PODS:")) break;
        while (!lines.isEmpty()) {
            String line = lines.remove(0);
            if (line.trim().isEmpty()) break;
            // - FirebaseCore (8.13.0):
            if (line.startsWith("  -")) {
                // '- "GoogleUtilities/Environment (7.10.0)"":'   ->   'GoogleUtilities/Environment (7.10.0)'
                String pod = line.trim().replace("- ", "").replace(":", "").replace("\"","");
                pods.add(pod);
            }
        }

        File podsDir = new File(workingDir, "Pods");
        List<PodSpec> specs = new ArrayList<>();
        Map<String, PodSpec> specsMap = new HashMap<>();
        for (String pod : pods) {
            // 'GoogleUtilities/Environment (7.10.0)'  -> 'GoogleUtilities/Environment' -> ['GoogleUtilities', 'Environment']
            String podnameparts[] = pod.replaceFirst(" \\(.*\\)", "").split("/");
            // 'GoogleUtilities'
            String mainpodname = podnameparts[0];
            // 'GoogleUtilities/Environment (7.10.0)'  -> '7.10.0'
            String podversion = pod.replaceFirst(".*\\(", "").replace(")", "");
            if (!specsMap.containsKey(mainpodname)) {
                String cmd = "pod spec cat --regex ^" + mainpodname + "$ --version=" + podversion;
                String specJson = execCommand(cmd).replace(cmd, "");
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

                specsMap.put(mainpodname, createPodSpec(specJson, podsDir, jobEnvContext));
            }

            PodSpec mainpod = specsMap.get(mainpodname);
            if (podnameparts.length == 1) {
                specs.add(mainpod);
            }
            else {
                String subspecname = podnameparts[1];
                for (PodSpec subspec : mainpod.subspecs) {
                    if (subspec.name.equals(subspecname)) {
                        specs.add(subspec);
                        break;
                    }
                }
            }
        }

        LOGGER.info("Installed pods");
        return specs;
    }

    private Map<String, Object> createJobEnvContext(Map<String, Object> env) {
        Map<String, Object> context = new HashMap<>(env);
        context.putIfAbsent("env.IOS_VERSION_MIN", System.getenv("IOS_VERSION_MIN"));
        context.putIfAbsent("env.MACOS_VERSION_MIN", System.getenv("MACOS_VERSION_MIN"));
        return context;
    }

    /**
     * Entry point for Cocoapod dependency resolution.
     * @param jobDirectory Root directory of the job to resolve
     * @param platform Which platform to resolve pods for
     * @return ResolvedPods instance with list of pods, install directory etc
     */
    public ResolvedPods resolveDependencies(Map<String, Object> env, File jobDirectory, String platform) throws IOException, ExtenderException {
        if (!platform.contains("ios") && !platform.contains("osx")) {
            throw new ExtenderException("Unsupported platform " + platform);
        }

        Map<String, Object> jobEnvContext = createJobEnvContext(env);

        // find all podfiles and filter down to a list of podfiles specifically
        // for the platform we are resolving pods for
        List<File> allPodFiles = ExtenderUtil.listFilesMatchingRecursive(jobDirectory, "Podfile");
        List<File> platformPodFiles = new ArrayList<>();
        for (File podFile : allPodFiles) {
            String parentFolder = podFile.getParentFile().getName();
            if ((platform.contains("ios") && parentFolder.contains("ios")) ||
                (platform.contains("osx") && parentFolder.contains("osx"))) {
                platformPodFiles.add(podFile);
            }
            else {
                LOGGER.warn("Unexpected Podfile found in " + podFile);
            }
        }
        if (platformPodFiles.isEmpty()) {
            LOGGER.info("Project has no Cocoapod dependencies");
            return null;
        }

        long methodStart = System.currentTimeMillis();
        LOGGER.info("Resolving Cocoapod dependencies");

        File workingDir = new File(jobDirectory, "CocoaPodsService");
        File frameworksDir = new File(workingDir, "frameworks");
        workingDir.mkdirs();

        String platformMinVersion = createMainPodFile(platformPodFiles, jobDirectory, workingDir, platform, jobEnvContext);
        List<PodSpec> pods = installPods(workingDir, jobEnvContext);
        copyPodFrameworks(pods, frameworksDir);
        
        // dumpDir(jobDirectory, 0);

        MetricsWriter.metricsTimer(meterRegistry, "gauge.service.cocoapods.get", System.currentTimeMillis() - methodStart);

        ResolvedPods resolvedPods = new ResolvedPods();
        resolvedPods.pods = pods;
        resolvedPods.platformMinVersion = platformMinVersion;
        resolvedPods.podsDir = new File(workingDir, "Pods");
        resolvedPods.frameworksDir = frameworksDir;
        return resolvedPods;
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
    private String execCommand(String command) throws ExtenderException {
        return execCommand(command, null);
    }


}
