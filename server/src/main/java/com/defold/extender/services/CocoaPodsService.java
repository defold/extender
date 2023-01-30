package com.defold.extender.services;

import com.defold.extender.ExtenderException;
import com.defold.extender.ExtenderUtil;
import com.defold.extender.ProcessExecutor;
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

    public class PodSpec {
        public String name = "";
        public String version = "";
        public String originalJSON = "";
        public Set<File> sourceFiles = new HashSet<>();
        public Set<File> includePaths = new HashSet<>();
        public List<PodSpec> subspecs = new ArrayList<>();
        public Set<String> defines = new HashSet<>();
        public Set<String> flags = new HashSet<>();
        public Set<String> vendoredframeworks = new HashSet<>();
        public Set<String> frameworks = new HashSet<>();
        public Set<String> iosframeworks = new HashSet<>();
        public Set<String> osxframeworks = new HashSet<>();
        public File dir;

        public String toString(String indentation) {
            StringBuilder sb = new StringBuilder();
            sb.append(indentation + name + ":" + version + "\n");
            sb.append(indentation + "  dir: " + dir + "\n");
            sb.append(indentation + "  src: " + sourceFiles + "\n");
            sb.append(indentation + "  includes: " + includePaths + "\n");
            sb.append(indentation + "  defines: " + flags + "\n");
            sb.append(indentation + "  flags: " + flags + "\n");
            sb.append(indentation + "  frameworks: " + frameworks + "\n");
            sb.append(indentation + "  iosframeworks: " + iosframeworks + "\n");
            sb.append(indentation + "  osxframeworks: " + osxframeworks + "\n");
            sb.append(indentation + "  vendoredframeworks: " + vendoredframeworks + "\n");
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


    private static final Logger LOGGER = LoggerFactory.getLogger(CocoaPodsService.class);

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

    /**
     * Create the main Podfile with a list of all dependencies for all uploaded extensions
     * @param jobDirectory
     * @param workingDir
     * @return Path to main podfile
     */
    private File createMainPodFile(File jobDirectory, File workingDir) throws IOException {
        File mainPodFile = new File(workingDir, "Podfile");

        List<File> podFiles = ExtenderUtil.listFilesMatchingRecursive(jobDirectory, "Podfile");

        // This file might exist when testing and debugging the extender using a debug job folder
        podFiles.remove(mainPodFile);

        // Create main Podfile contents
        String mainPodFileContents = "";
        mainPodFileContents += "platform :ios, '9.0'\n";
        mainPodFileContents += "install! 'cocoapods', integrate_targets: false, skip_pods_project_generation: true\n";
        mainPodFileContents += "use_frameworks!\n";
        for (File podFile : podFiles) {
            String pod = readFile(podFile.getAbsolutePath());
            mainPodFileContents += pod + "\n";
        }

        LOGGER.info("Created main Podfile: {}", mainPodFileContents);

        Files.write(mainPodFile.toPath(), mainPodFileContents.getBytes());

        return mainPodFile;
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

    private File getWorkingDir(File jobDirectory) {
        return new File(jobDirectory, "CocoaPodsService");
    }

    public File getResolvedPodsDir(File jobDirectory) {
        return new File(getWorkingDir(jobDirectory), "Pods");
    }
    public File getResolvedFrameworksDir(File jobDirectory) {
        return new File(getWorkingDir(jobDirectory), "frameworks");
    }

    private void processVendoredFrameworks(PodSpec pod, File armDir, File x86Dir) throws IOException {
        for (String framework : pod.vendoredframeworks) {
            LOGGER.info("Pod {} has framework {}", pod.name, framework);
            File frameworkDir = new File(pod.dir, framework);
            String frameworkName = frameworkDir.getName().replace(".xcframework", "");
            
            File armFrameworkDir = new File(frameworkDir, "ios-arm64_armv7");
            if (armFrameworkDir.exists()) {
                LOGGER.info("Moving framework {}", armFrameworkDir);
                Files.move(new File(armFrameworkDir, frameworkName + ".framework").toPath(), new File(armDir, frameworkName + ".framework").toPath(), StandardCopyOption.ATOMIC_MOVE);
            }
            
            File x86Framework = new File(frameworkDir, "ios-arm64_i386_x86_64-simulator");
            if (x86Framework.exists()) {
                LOGGER.info("Moving framework {}", x86Framework);
                Files.move(new File(x86Framework, frameworkName + ".framework").toPath(), new File(x86Dir, frameworkName + ".framework").toPath(), StandardCopyOption.ATOMIC_MOVE);
            }
        }
    }

    private void processPods(List<PodSpec> pods, File jobDirectory, File workingDir) throws IOException {
        LOGGER.info("Processing pod files");
        File podsDir = getResolvedPodsDir(jobDirectory);
        File frameworksDir = getResolvedFrameworksDir(jobDirectory);

        LOGGER.info("Moving frameworks");
        File libDir = new File(frameworksDir, "lib");
        File armDir = new File(libDir, "arm64-ios");
        File x86Dir = new File(libDir, "x86_64-ios");
        armDir.mkdirs();
        x86Dir.mkdirs();

        for (PodSpec pod : pods) {
            processVendoredFrameworks(pod, armDir, x86Dir);
        }
    }

    private JSONObject parseJson(String json) throws ExtenderException {
        LOGGER.info("parseJson {}", json);
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

        LOGGER.info("createPodSpec {} v {}", spec.name, spec.version);

        // flags
        Boolean requiresArc = (Boolean)specJson.get("requires_arc");
        spec.flags.add((requiresArc == null || requiresArc == true) ? "-fobjc-arc" : "-fno-objc-arc");
        // compiler_flags = '-DOS_OBJECT_USE_OBJC=0', '-Wno-format'

        // defines
        JSONObject podTargetConfig = (JSONObject)specJson.get("pod_target_xcconfig");
        JSONObject userTargetConfig = (JSONObject)specJson.get("user_target_xcconfig"); // not recommended for use but we need to handle it
        JSONObject config = (JSONObject)specJson.get("xcconfig");  // undocumented but used by some pods
        if (podTargetConfig != null) spec.defines.addAll(getAsSplitString(podTargetConfig, "GCC_PREPROCESSOR_DEFINITIONS"));
        if (userTargetConfig != null) spec.defines.addAll(getAsSplitString(userTargetConfig, "GCC_PREPROCESSOR_DEFINITIONS"));
        if (config != null) spec.defines.addAll(getAsSplitString(config, "GCC_PREPROCESSOR_DEFINITIONS"));

        // frameworks
        JSONObject ios = (JSONObject)specJson.get("ios");
        JSONObject osx = (JSONObject)specJson.get("osx");
        spec.frameworks.addAll(getAsJSONArray(specJson, "frameworks"));
        if (ios != null) spec.iosframeworks.addAll(getAsJSONArray(ios, "frameworks"));
        if (osx != null) spec.osxframeworks.addAll(getAsJSONArray(osx, "frameworks"));

        // vendored_frameworks
        JSONArray vendored = getAsJSONArray(specJson, "vendored_frameworks");
        if (vendored != null) {
            spec.vendoredframeworks.addAll(vendored);
        }

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
     * Get pod specs as json. Use list of pods from the Podfile.lock. Example output:
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
    private List<PodSpec> parsePods(File dir) throws IOException, ExtenderException {
        File podFileLock = new File(dir, "Podfile.lock");
        if (!podFileLock.exists()) {
            throw new ExtenderException("Unable to find Podfile.lock in directory " + dir);
        }
        LOGGER.info("Podfile.lock: {}", FileUtils.readFileToString(podFileLock));

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

        File podsDir = new File(dir, "Pods");
        List<PodSpec> specs = new ArrayList<>();
        Map<String, PodSpec> specsMap = new HashMap<>();
        for (String pod : pods) {
            // 'GoogleUtilities/Environment (7.10.0)'  -> 'GoogleUtilities/Environment'
            String podnameparts[] = pod.replaceFirst(" \\(.*\\)", "").split("/");
            String mainpodname = podnameparts[0];
            if (!specsMap.containsKey(mainpodname)) {
                // 'GoogleUtilities/Environment (7.10.0)'   ->   'GoogleUtilities --version=7.10.0'
                String args = pod.replaceFirst("/.*?\\s", " ").replace("(", "--version=").replace(")", "");
                String cmd = "pod spec cat " + args;
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

        return specs;
    }

    // install pods from podfile
    private void installPods(File dir) throws IOException, ExtenderException {
        LOGGER.info("Installing pods");
        File podFile = new File(dir, "Podfile");
        if (!podFile.exists()) {
            throw new ExtenderException("Unable to find Podfile in directory " + dir);
        }
        String log = execCommand("pod install --verbose", dir);
        LOGGER.info("\n" + log);
    }

    public List<PodSpec> resolveDependencies(File jobDirectory) throws IOException, ExtenderException {
        long methodStart = System.currentTimeMillis();
        LOGGER.info("Resolving Cocoapod dependencies");

        File workingDir = new File(jobDirectory, "CocoaPodsService");
        workingDir.mkdirs();

        createMainPodFile(jobDirectory, workingDir);
        installPods(workingDir);
        List<PodSpec> pods = parsePods(workingDir);
        processPods(pods, jobDirectory, workingDir);
        
        // dumpDir(jobDirectory, 0);

        MetricsWriter.metricsTimer(meterRegistry, "gauge.service.cocoapods.get", System.currentTimeMillis() - methodStart);

        return pods;
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
