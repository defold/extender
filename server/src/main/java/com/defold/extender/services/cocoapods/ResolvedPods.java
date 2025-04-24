package com.defold.extender.services.cocoapods;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
