package com.defold.extender.services.cocoapods;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FileUtils;

import com.defold.extender.ExtenderException;
import com.defold.extender.services.cocoapods.PlistBuddyWrapper.CreateBundlePlistArgs;

public class ResolvedPods {
    public List<PodSpec> pods = new ArrayList<>();
    public File podsDir;
    public File frameworksDir;
    public File generatedDir;
    public String platformMinVersion;
    public File podFileLock;
    public File publicHeadersDir;
    public File privateHeadersDir;

    // In the functions below we also get the values from the parent spec
    // if one exists. A parent spec inherits all of its subspecs (unless a 
    // default_spec is set). And the subspecs inherit the values of their
    // parent
    // https://guides.cocoapods.org/syntax/podspec.html#subspec

    private void addPodLibs(PodSpec pod, Set<String> libs) {
        libs.addAll(pod.libraries);
        if (pod.parentSpec != null) addPodLibs(pod.parentSpec, libs);
    }
    public List<String> getAllPodLibs() {
        Set<String> libs = new LinkedHashSet<>();
        for (PodSpec pod : pods) {
            addPodLibs(pod, libs);
        }
        return new ArrayList<String>(libs);
    }

    private void addPodLinkFlags(PodSpec pod, Set<String> flags) {
        flags.addAll(pod.linkflags);
        if (pod.parentSpec != null) addPodLinkFlags(pod.parentSpec, flags);
    }
    public List<String> getAllPodLinkFlags() {
        Set<String> flags = new LinkedHashSet<>();
        for (PodSpec pod : pods) {
            addPodLinkFlags(pod, flags);
        }
        return new ArrayList<String>(flags);
    }

    private void addPodResources(PodSpec pod, Set<File> resources) {
        File podDir = pod.dir;
        for (String resource : pod.resources) {
            resources.addAll(PodUtils.listFilesGlob(podDir, resource));
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

    private void addPodFrameworks(PodSpec pod, Set<String> frameworks) {
        frameworks.addAll(pod.frameworks);
        if (pod.parentSpec != null) addPodFrameworks(pod.parentSpec, frameworks);
    }
    public List<String> getAllPodFrameworks() {
        Set<String> frameworks = new LinkedHashSet<>();
        for (PodSpec pod : pods) {
            addPodFrameworks(pod, frameworks);
        }
        return new ArrayList<String>(frameworks);
    }

    private void addPodWeakFrameworks(PodSpec pod, Set<String> weakFrameworks) {
        weakFrameworks.addAll(pod.weakFrameworks);
        if (pod.parentSpec != null) addPodWeakFrameworks(pod.parentSpec, weakFrameworks);
    }

    public List<String> getAllPodWeakFrameworks() {
        Set<String> weakFrameworks = new LinkedHashSet<>();
        for (PodSpec pod : pods) {
            addPodWeakFrameworks(pod, weakFrameworks);
        }
        return new ArrayList<String>(weakFrameworks);
    }

    public List<File> createResourceBundles(File targetDir, String platform) throws IOException, ExtenderException {
        List<File> result = new ArrayList<>();
        for (PodSpec spec : pods) {
            for (Map.Entry<String, List<String>> entry : spec.resourceBundles.entrySet()) {
                result.add(createResourceBundle(targetDir, platform, spec, entry.getKey(), entry.getValue()));
            }
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

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("pod count: " + pods.size() + "\n");
        sb.append("pods dir: " + podsDir + "\n");
        sb.append("frameworks Dir: " + frameworksDir + "\n");
        sb.append("generated dir: " + generatedDir + "\n");
        sb.append("platform min version: " + platformMinVersion + "\n");
        sb.append("podfile.lock: " + podFileLock);
        return sb.toString();
    }
}
