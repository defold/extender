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
import java.util.HashSet;
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

        public List<String> getAllPodLibs(String platform) {
            Set<String> libs = new HashSet<>();
            for (PodSpec pod : pods) {
                libs.addAll(pod.libraries);
                if (platform.contains("ios")) {
                    libs.addAll(pod.ios_libraries);
                }
                else if (platform.contains("osx")) {
                    libs.addAll(pod.osx_libraries);
                }
            }
            return new ArrayList<String>(libs);
        }

        public List<String> getAllPodLinkFlags(String platform) {
            Set<String> flags = new HashSet<>();
            for (PodSpec pod : pods) {
                flags.addAll(pod.linkflags);
            }
            return new ArrayList<String>(flags);
        }

        public List<String> getAllPodFrameworks(String platform) {
            Set<String> frameworks = new HashSet<>();
            for (PodSpec pod : pods) {
                frameworks.addAll(pod.frameworks);
                if (platform.contains("ios")) {
                    frameworks.addAll(pod.ios_frameworks);
                }
                else if (platform.contains("osx")) {
                    frameworks.addAll(pod.osx_frameworks);
                }
            }
            return new ArrayList<String>(frameworks);
        }

        public List<String> getAllPodWeakFrameworks(String platform) {
            Set<String> weakFrameworks = new HashSet<>();
            for (PodSpec pod : pods) {
                weakFrameworks.addAll(pod.weak_frameworks);
                if (platform.contains("ios")) {
                    weakFrameworks.addAll(pod.ios_weak_frameworks);
                }
                else if (platform.contains("osx")) {
                    weakFrameworks.addAll(pod.osx_weak_frameworks);
                }
            }
            return new ArrayList<String>(weakFrameworks);
        }
    }

    public class PodSpec {
        public String name = "";
        public String version = "";
        public String originalJSON = "";
        public String iosversion = "";
        public String osxversion = "";
        public Set<File> sourceFiles = new HashSet<>();
        public Set<File> includePaths = new HashSet<>();
        public List<PodSpec> subspecs = new ArrayList<>();
        public Set<String> defines = new HashSet<>();
        public Set<String> flags = new HashSet<>();
        public Set<String> ios_flags = new HashSet<>();
        public Set<String> osx_flags = new HashSet<>();
        public Set<String> linkflags = new HashSet<>();
        public Set<String> vendoredframeworks = new HashSet<>();
        public Set<String> weak_frameworks = new HashSet<>();
        public Set<String> ios_weak_frameworks = new HashSet<>();
        public Set<String> osx_weak_frameworks = new HashSet<>();
        public Set<String> frameworks = new HashSet<>();
        public Set<String> ios_frameworks = new HashSet<>();
        public Set<String> osx_frameworks = new HashSet<>();
        public Set<String> libraries = new HashSet<>();
        public Set<String> ios_libraries = new HashSet<>();
        public Set<String> osx_libraries = new HashSet<>();
        public File dir;

        public String toString(String indentation) {
            StringBuilder sb = new StringBuilder();
            sb.append(indentation + name + ":" + version + "\n");
            sb.append(indentation + "  dir: " + dir + "\n");
            sb.append(indentation + "  src: " + sourceFiles + "\n");
            sb.append(indentation + "  includes: " + includePaths + "\n");
            sb.append(indentation + "  defines: " + defines + "\n");
            sb.append(indentation + "  flags: " + flags + "\n");
            sb.append(indentation + "  ios_flags: " + ios_flags + "\n");
            sb.append(indentation + "  osx_flags: " + osx_flags + "\n");
            sb.append(indentation + "  linkflags: " + linkflags + "\n");
            sb.append(indentation + "  iosversion: " + iosversion + "\n");
            sb.append(indentation + "  osxversion: " + osxversion + "\n");
            sb.append(indentation + "  weak_frameworks: " + weak_frameworks + "\n");
            sb.append(indentation + "  ios_weak_frameworks: " + ios_weak_frameworks + "\n");
            sb.append(indentation + "  osx_weak_frameworks: " + osx_weak_frameworks + "\n");
            sb.append(indentation + "  frameworks: " + frameworks + "\n");
            sb.append(indentation + "  ios_frameworks: " + ios_frameworks + "\n");
            sb.append(indentation + "  osx_frameworks: " + osx_frameworks + "\n");
            sb.append(indentation + "  vendoredframeworks: " + vendoredframeworks + "\n");
            sb.append(indentation + "  libraries: " + libraries + "\n");
            sb.append(indentation + "  ios_libraries: " + ios_libraries + "\n");
            sb.append(indentation + "  osx_libraries: " + osx_libraries + "\n");
            for (PodSpec sub : subspecs) {
                sb.append(sub.toString(indentation + "  "));
            }
            return sb.toString();
        }
        @Override
        public String toString() {
            return toString("");
        }
    }

    private static final String IOS_VERSION = System.getenv("IOS_VERSION_MIN");
    private static final String OSX_VERSION = System.getenv("MACOS_VERSION_MIN");
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
    private String createMainPodFile(List<File> podFiles, File jobDirectory, File workingDir, String platform) throws IOException {
        File mainPodFile = new File(workingDir, "Podfile");

        // This file might exist when testing and debugging the extender using a debug job folder
        podFiles.remove(mainPodFile);

        String mainPodfilePlatformVersion = (platform.contains("ios") ? IOS_VERSION : OSX_VERSION);
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
        Set<File> includePaths = new HashSet<>();

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

    /**
     * Find all .xcframeworks (vendored frameworks) in a list of pods and copy any arm
     * and x86 .framworks for use later when building extensions using the pods
     * @param pods The pods to process
     * @param frameworksDir Directory where to copy frameworks
     */
    private void copyPodFrameworks(List<PodSpec> pods, File frameworksDir) throws IOException {
        LOGGER.info("Copying pod frameworks");
        File libDir = new File(frameworksDir, "lib");
        File armDir = new File(libDir, "arm64-ios");
        File x86Dir = new File(libDir, "x86_64-ios");
        armDir.mkdirs();
        x86Dir.mkdirs();

        for (PodSpec pod : pods) {
            for (String framework : pod.vendoredframeworks) {
                File frameworkDir = new File(pod.dir, framework);
                String frameworkName = frameworkDir.getName().replace(".xcframework", "");
                
                File arm64_armv7FrameworkDir = new File(frameworkDir, "ios-arm64_armv7");
                File arm64FrameworkDir = new File(frameworkDir, "ios-arm64");
                if (arm64_armv7FrameworkDir.exists()) {
                    Path from = new File(arm64_armv7FrameworkDir, frameworkName + ".framework").toPath();
                    Path to = new File(armDir, frameworkName + ".framework").toPath();
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
                }
                else if (arm64FrameworkDir.exists()) {
                    Path from = new File(arm64FrameworkDir, frameworkName + ".framework").toPath();
                    Path to = new File(armDir, frameworkName + ".framework").toPath();
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
                }
                
                File arm64_i386_x86Framework = new File(frameworkDir, "ios-arm64_i386_x86_64-simulator");
                File arm64_x86Framework = new File(frameworkDir, "ios-arm64_x86_64-simulator");
                if (arm64_i386_x86Framework.exists()) {
                    Path from = new File(arm64_i386_x86Framework, frameworkName + ".framework").toPath();
                    Path to = new File(x86Dir, frameworkName + ".framework").toPath();
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
                }
                else if (arm64_x86Framework.exists()) {
                    Path from = new File(arm64_x86Framework, frameworkName + ".framework").toPath();
                    Path to = new File(x86Dir, frameworkName + ".framework").toPath();
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
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

    // https://guides.cocoapods.org/syntax/podspec.html
    private PodSpec createPodSpec(JSONObject specJson, PodSpec parent, File podsDir) throws ExtenderException {
        PodSpec spec = new PodSpec();
        spec.name = (String)specJson.get("name");
        spec.version = (parent == null) ? (String)specJson.get("version") : parent.version;
        spec.dir = (parent == null) ? new File(podsDir, spec.name) : parent.dir;

        // platform versions
        JSONObject platforms = (JSONObject)specJson.get("platforms");
        if (platforms != null) spec.iosversion = (String)platforms.getOrDefault("ios", IOS_VERSION);
        if (platforms != null) spec.osxversion = (String)platforms.getOrDefault("osx", OSX_VERSION);

        // for multi platform settings
        JSONObject ios = (JSONObject)specJson.get("ios");
        JSONObject osx = (JSONObject)specJson.get("osx");

        // defines
        JSONObject podTargetConfig = (JSONObject)specJson.get("pod_target_xcconfig");
        JSONObject userTargetConfig = (JSONObject)specJson.get("user_target_xcconfig"); // not recommended for use but we need to handle it
        JSONObject config = (JSONObject)specJson.get("xcconfig");  // undocumented but used by some pods
        if (podTargetConfig != null) spec.defines.addAll(getAsSplitString(podTargetConfig, "GCC_PREPROCESSOR_DEFINITIONS"));
        if (userTargetConfig != null) spec.defines.addAll(getAsSplitString(userTargetConfig, "GCC_PREPROCESSOR_DEFINITIONS"));
        if (config != null) spec.defines.addAll(getAsSplitString(config, "GCC_PREPROCESSOR_DEFINITIONS"));

        // linker flags
        if (userTargetConfig != null) spec.linkflags.addAll(getAsSplitString(userTargetConfig, "OTHER_LDFLAGS"));

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
        spec.flags.add((requiresArc == null || requiresArc == true) ? "-fobjc-arc" : "-fno-objc-arc");
        spec.flags.addAll(getAsSplitString(specJson, "compiler_flags"));

        // platform specific flags
        if (ios != null) spec.ios_flags.addAll(getAsJSONArray(ios, "compiler_flags"));
        if (osx != null) spec.osx_flags.addAll(getAsJSONArray(osx, "compiler_flags"));

        // frameworks
        spec.frameworks.addAll(getAsJSONArray(specJson, "frameworks"));
        if (ios != null) spec.ios_frameworks.addAll(getAsJSONArray(ios, "frameworks"));
        if (osx != null) spec.osx_frameworks.addAll(getAsJSONArray(osx, "frameworks"));

        // weak frameworks
        spec.weak_frameworks.addAll(getAsJSONArray(specJson, "weak_frameworks"));
        if (ios != null) spec.ios_weak_frameworks.addAll(getAsJSONArray(ios, "weak_frameworks"));
        if (osx != null) spec.osx_weak_frameworks.addAll(getAsJSONArray(osx, "weak_frameworks"));

        // vendored_frameworks
        JSONArray vendored = getAsJSONArray(specJson, "vendored_frameworks");
        if (vendored != null) {
            spec.vendoredframeworks.addAll(vendored);
        }

        // libraries
        spec.libraries.addAll(getAsJSONArray(specJson, "libraries"));
        if (ios != null) spec.ios_libraries.addAll(getAsJSONArray(ios, "libraries"));
        if (osx != null) spec.osx_libraries.addAll(getAsJSONArray(osx, "libraries"));

        // parse subspecs
        JSONArray subspecs = getAsJSONArray(specJson, "subspecs");
        if (subspecs != null) {
            Iterator<JSONObject> it = subspecs.iterator();
            while (it.hasNext()) {
                JSONObject o = it.next();
                PodSpec subSpec = createPodSpec(o, spec, podsDir);
                spec.subspecs.add(subSpec);
            }
        }

        // find source and header files
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

    private PodSpec createPodSpec(String specJson, File podsDir) throws ExtenderException {
        PodSpec spec = createPodSpec(parseJson(specJson), null, podsDir);
        spec.originalJSON = specJson;
        return spec;
    }

    /**
     * Install pods from a podfile and create PodSpec instances for each installed pod.
     * @param workingDir Directory where to install pods. The directory must contain a valid Podfile
     * @return A list of PodSpec instances for the installed pods
     */
    private List<PodSpec> installPods(File workingDir) throws IOException, ExtenderException {
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
        Set<String> pods = new HashSet<>();
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

                specsMap.put(mainpodname, createPodSpec(specJson, podsDir));
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

    /**
     * Entry point for Cocoapod dependency resolution.
     * @param jobDirectory Root directory of the job to resolve
     * @param platform Which platform to resolve pods for
     * @return ResolvedPods instance with list of pods, install directory etc
     */
    public ResolvedPods resolveDependencies(File jobDirectory, String platform) throws IOException, ExtenderException {
        if (!platform.contains("ios") && !platform.contains("osx")) {
            throw new ExtenderException("Unsupported platform " + platform);
        }

        List<File> podFiles = ExtenderUtil.listFilesMatchingRecursive(jobDirectory, "Podfile");
        if (podFiles.isEmpty()) {
            LOGGER.info("Project has no Cocoapod dependencies");
            return null;
        }

        long methodStart = System.currentTimeMillis();
        LOGGER.info("Resolving Cocoapod dependencies");

        File workingDir = new File(jobDirectory, "CocoaPodsService");
        File frameworksDir = new File(workingDir, "frameworks");
        workingDir.mkdirs();

        String platformMinVersion = createMainPodFile(podFiles, jobDirectory, workingDir, platform);
        List<PodSpec> pods = installPods(workingDir);
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
