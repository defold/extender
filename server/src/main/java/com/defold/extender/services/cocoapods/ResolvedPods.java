package com.defold.extender.services.cocoapods;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.defold.extender.ExtenderException;
import com.defold.extender.ExtenderUtil;
import com.defold.extender.services.cocoapods.PlistBuddyWrapper.CreateBundlePlistArgs;
import com.defold.extender.utils.FrameworkUtil;

public class ResolvedPods {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResolvedPods.class);
    static final String FRAMEWORK_RE = "(.+)\\.framework";
    private List<PodSpec> pods = new ArrayList<>();
    private File podsDir;
    private File frameworksDir;
    private File targetSupportFilesDir;
    private String platformMinVersion;
    private File podFileLock;
    private List<String> additionIncludePaths;
    private List<String> librarySearchPaths;
    private List<String> frameworkSearchPaths;
    private List<String> staticLibraries;
    private List<String> frameworks;
    private List<File> dynamicFrameworks;
    private List<String> weakFrameworks;
    private boolean useFrameworks = false;

    public ResolvedPods(CocoaPodsServiceBuildState cocoapodsBuildState, List<PodSpec> specs, File podfileLock, MainPodfile mainPodfile) throws IOException {
        this.platformMinVersion = mainPodfile.platformMinVersion;
        this.podsDir = cocoapodsBuildState.getPodsDir();
        this.targetSupportFilesDir = new File(this.podsDir, "Target Support Files");
        this.frameworksDir = cocoapodsBuildState.getUnpackedFrameworksDir();
        this.podFileLock = podfileLock;
        this.useFrameworks = mainPodfile.useFrameworks;

        setPodsSpecs(specs);
    }

    // In the functions below we also get the values from the parent spec
    // if one exists. A parent spec inherits all of its subspecs (unless a 
    // default_spec is set). And the subspecs inherit the values of their
    // parent
    // https://guides.cocoapods.org/syntax/podspec.html#subspec

    void addPodLibs(PodSpec pod, Set<String> libs) {
        libs.addAll(pod.libraries);
        if (pod.parentSpec != null) addPodLibs(pod.parentSpec, libs);
    }

    List<String> collectPodLibs() throws IOException {
        Set<String> libs = new LinkedHashSet<>();
        for (PodSpec pod : pods) {
            addPodLibs(pod, libs);
        }
        if (!useFrameworks) {
            libs.addAll(ExtenderUtil.collectStaticLibsByName(frameworksDir));
        }
        return new ArrayList<>(libs);
    }

    void addPodLinkFlags(PodSpec pod, List<String> flags) {
        flags.addAll(pod.linkflags);
        if (pod.parentSpec != null) addPodLinkFlags(pod.parentSpec, flags);
    }

    public List<String> getAllPodLinkFlags() {
        List<String> flags = new ArrayList<>();
        for (PodSpec pod : pods) {
            addPodLinkFlags(pod, flags);
        }
        return new ArrayList<String>(flags);
    }

    public static void addPodResources(PodSpec pod, Set<File> resources) {
        File podDir = pod.dir;
        for (String resource : pod.resources) {
            resources.addAll(PodUtils.listFilesAndDirsGlob(podDir, resource));
        }
        if (pod.parentSpec != null) {
            addPodResources(pod.parentSpec, resources);
        }
    }

    public List<File> getAllPodResources() {
        Set<File> resources = new LinkedHashSet<>();
        for (PodSpec pod : pods) {
            addPodResources(pod, resources);
        }
        return new ArrayList<File>(resources);
    }

    void addPodFrameworks(PodSpec pod, Set<String> frameworks) {
        frameworks.addAll(pod.frameworks);
        if (pod.parentSpec != null) addPodFrameworks(pod.parentSpec, frameworks);
    }

    List<String> collectFrameworkPaths() {
        Set<String> frameworkPaths = new HashSet<>();
        for (PodSpec spec : pods) {
            for (File f : spec.frameworkSearchPaths) {
                frameworkPaths.add(f.toString());
            }
            if (useFrameworks) {
                frameworkPaths.add(spec.buildDir.toString());
            }
        }
        return new ArrayList<>(frameworkPaths);
    }

    List<String> collectFrameworkStaticLibPaths() throws IOException {
        Set<String> staticLibs = new HashSet<>(ExtenderUtil.collectStaticLibSearchPaths(frameworksDir));
        return new ArrayList<>(staticLibs);
    }

    List<String> collectAllPodFrameworks() throws IOException {
        Set<String> frameworks = new LinkedHashSet<>();
        for (PodSpec pod : pods) {
            addPodFrameworks(pod, frameworks);
        }

        // collect unpacked xcframeworks
        Pattern pattern = Pattern.compile(FRAMEWORK_RE);
        Files.walk(frameworksDir.toPath())
            .filter(Files::isDirectory)
            .forEach(path -> {
                Matcher m = pattern.matcher(path.getFileName().toString());
                if (m.matches()) {
                    frameworks.add(m.group(1));
            }
        });

        return new ArrayList<String>(frameworks);
    }

    List<File> collectAllPodsDynamicFrameworks() throws IOException {
        Set<File> dynamicFrameworks = new HashSet<>();
        // collect unpacked xcframeworks
        Pattern pattern = Pattern.compile(FRAMEWORK_RE);
        Files.walk(frameworksDir.toPath())
            .filter(Files::isDirectory)
            .forEach(path -> {
                Matcher m = pattern.matcher(path.getFileName().toString());
                if (m.matches()) {
                    File framework = path.toFile();
                    try {
                        if (FrameworkUtil.isDynamicallyLinked(framework)) {
                            dynamicFrameworks.add(framework);
                        }
                    } catch (ExtenderException e) {
                        LOGGER.warn("Exception when check framework linkage type", e);
                    }
                }
        });

        return new ArrayList<File>(dynamicFrameworks);
    }

    List<String> collectAdditionalIncludePaths() {
        Set<String> includePaths = new HashSet<>();
        for (PodSpec spec: pods) {
            for (File path : spec.includePaths) {
                includePaths.add(path.toString());
            }
            includePaths.add(spec.headerMapFile.toString());
        }
        return new ArrayList<String>(includePaths);
    }

    void addPodWeakFrameworks(PodSpec pod, Set<String> weakFrameworks) {
        weakFrameworks.addAll(pod.weakFrameworks);
        if (pod.parentSpec != null) addPodWeakFrameworks(pod.parentSpec, weakFrameworks);
    }

    List<String> collectPodWeakFrameworks() {
        Set<String> weakFrameworks = new LinkedHashSet<>();
        for (PodSpec pod : pods) {
            addPodWeakFrameworks(pod, weakFrameworks);
        }
        return new ArrayList<String>(weakFrameworks);
    }

    public static List<File> createPodResourceBundles(PodSpec spec, File targetDir, String platform) throws IOException, ExtenderException {
        List<File> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : spec.resourceBundles.entrySet()) {
            result.add(createResourceBundle(targetDir, platform, spec, entry.getKey(), entry.getValue()));
        }
        return result;
    }

    public List<File> createResourceBundles(File targetDir, String platform) throws IOException, ExtenderException {
        List<File> result = new ArrayList<>();
        for (PodSpec spec : pods) {
            result.addAll(createPodResourceBundles(spec, targetDir, platform));
        }
        return result;
    }

    static File createResourceBundle(File targetDir, String platform, PodSpec pod, String bundleName, List<String> content) throws IOException, ExtenderException {
        File resultFolder = new File(targetDir, bundleName + ".bundle");
        resultFolder.mkdirs();
        for (String contentElement : content) {
            // contentElement can be regex so expand it
            for (File f : PodUtils.listFilesGlob(pod.dir, contentElement)) {
                FileUtils.copyFileToDirectory(f, resultFolder);
            }
        }
        File infoPlist = new File(resultFolder, "Info.plist");
        PlistBuddyWrapper.CreateBundlePlistArgs args = new CreateBundlePlistArgs();
        args.bundleId = "com.defold.extender." + bundleName;
        args.bundleName = bundleName;
        args.version = "1";
        args.shortVersion = pod.version;
        args.minVersion = pod.platformVersion;
        // TODO: if build several archs we need to merge supported platforms
        args.supportedPlatforms = PodUtils.toPlistPlatforms(new String[] { platform });
        PlistBuddyWrapper.createBundleInfoPlist(infoPlist, args);
        return resultFolder;
    }

    public void setPodsSpecs(List<PodSpec> specs) throws IOException {
        // iterate over all spec selected for build
        // then we need to union all ubspces with parent spec and build it as one artifact

        // this map we will use for fast access to parent spec
        Map<String, PodSpec> unitedSpecs = new HashMap<>();
        pods = new ArrayList<>();

        for (PodSpec spec : specs) {
            String podName = spec.getPodName();
            if (!unitedSpecs.containsKey(podName)) {
                PodSpec s = new PodSpec(spec.parentSpec == null ? spec : spec.parentSpec);
                unitedSpecs.put(podName, s);
                pods.add(s);
            }

            PodSpec parentSpec = unitedSpecs.get(podName);
            PodSpec.mergeSpecs(parentSpec, spec);
        }

        frameworkSearchPaths = collectFrameworkPaths();
        librarySearchPaths = collectFrameworkStaticLibPaths();
        staticLibraries = collectPodLibs();
        frameworks = collectAllPodFrameworks();
        dynamicFrameworks = collectAllPodsDynamicFrameworks();
        weakFrameworks = collectPodWeakFrameworks();
        additionIncludePaths = collectAdditionalIncludePaths();
    }

    public List<String> getFrameworks() {
        return frameworks;
    }

    public Set<String> getBuiltFrameworks() {
        if (!useFrameworks) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        for (PodSpec spec : pods) {
            if (PodUtils.hasSourceFiles(spec)) {
                result.add(spec.moduleName);
            }
        }
        return result;
    }

    public List<String> getWeakFrameworks() {
        return weakFrameworks;
    }

    public List<String> getFrameworksSearchPaths() {
        return frameworkSearchPaths;
    }

    public List<String> getLibrarySearchPaths() {
        return librarySearchPaths;
    }

    public List<String> getAdditionalIncludePaths() {
        return additionIncludePaths;
    }

    public List<String> getStaticLibraries() {
        return staticLibraries;
    }

    public List<PodSpec> getPodSpecs() {
        return pods;
    }

    public String getPlatformMinVersion() {
        return platformMinVersion;
    }

    public File getPodfileLock() {
        return podFileLock;
    }

    public List<File> getDynamicFrameworks() {
        return dynamicFrameworks;
    }

    public File getCurrentPodsDirectory() {
        return podsDir;
    }

    public boolean useFrameworks() {
        return useFrameworks;
    }

    @Deprecated
    public List<File> getPodsPrivacyManifests() {
        return ExtenderUtil.listFilesMatchingRecursive(podsDir, "PrivacyInfo.xcprivacy");
    }

    public File getTargetSupportFilesDir() {
        return targetSupportFilesDir;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("pod count: " + pods.size() + "\n");
        sb.append("pods dir: " + podsDir + "\n");
        sb.append("frameworks dir: " + frameworksDir + "\n");
        sb.append("platform min version: " + platformMinVersion + "\n");
        sb.append("podfile.lock: " + podFileLock);
        return sb.toString();
    }
}
