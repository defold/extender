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

    // In the functions below we also get the values from the parent spec
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

    private void addPodResources(String platform, PodSpec pod, Set<File> resources) {
        File podDir = pod.dir;
        for (String resource : pod.resources.get(platform)) {
            resources.addAll(PodUtils.listFilesGlob(podDir, resource));
        }
        if (pod.parentSpec != null) {
            addPodResources(platform, pod.parentSpec, resources);
        }
    }
    public List<File> getAllPodResources(String platform) {
        Set<File> resources = new LinkedHashSet<>();
        for (PodSpec pod : pods) {
            addPodResources(platform, pod, resources);
        }
        return new ArrayList<File>(resources);
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
        weakFrameworks.addAll(pod.weakFrameworks.get(platform));
        if (pod.parentSpec != null) addPodWeakFrameworks(platform, pod.parentSpec, weakFrameworks);
    }

    public List<String> getAllPodWeakFrameworks(String platform) {
        Set<String> weakFrameworks = new LinkedHashSet<>();
        for (PodSpec pod : pods) {
            addPodWeakFrameworks(platform, pod, weakFrameworks);
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
        if (platform.contains("ios")) {
            args.minVersion = pod.iosversion;
        } else if (platform.contains("osx")) {
            args.minVersion = pod.osxversion;
        } else {
            throw new IllegalArgumentException(String.format("Platform %s is not supported", platform));
        }
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
