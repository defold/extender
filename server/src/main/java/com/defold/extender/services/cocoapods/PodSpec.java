package com.defold.extender.services.cocoapods;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PodSpec {
    public String name = "";
    public String moduleName = "";
    public String version = "";
    public String platformVersion = "";
    public Map<String, String> platforms = new HashMap<>();
    public Set<String> publicHeadersPatterns = new LinkedHashSet<>();
    public Set<String> sourceFilesPatterns = new LinkedHashSet<>();
    public Set<File> frameworkSearchPaths = new LinkedHashSet<>();
    public PodSpec parentSpec = null;
    public List<String> defaultSubspecs = new ArrayList<>();
    public List<PodSpec> subspecs = new ArrayList<>();
    public List<String> dependencies = new ArrayList<>();
    public Map<String, List<String>> resourceBundles = new HashMap<>();

    public LanguageSet flags = new LanguageSet();
    public Set<String> defines = new HashSet<>();
    public List<String> linkflags = new ArrayList<>();
    public Set<String> vendoredFrameworks = new LinkedHashSet<>();
    public Set<String> weakFrameworks = new HashSet<>();
    public Set<String> resources = new HashSet<>();
    public Set<String> frameworks = new HashSet<>();
    public Set<String> libraries = new HashSet<>();
    public Map<String, String> parsedXCConfig = null;

    public PodSpec() {}

    public PodSpec getSubspec(String name) {
        for (PodSpec spec : subspecs) {
            if (spec.name.equals(name)) {
                return spec;
            }
        }
        return null;
    }

    public String getPodName() {
        if (parentSpec != null) {
            return parentSpec.getPodName();
        }
        return name;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(name + ":" + version + "\n");
        sb.append("  moduleName: " + moduleName + "\n");
        sb.append("  src: " + sourceFilesPatterns + "\n");
        sb.append("  defines: " + defines + "\n");
        sb.append("  flags: " + flags + "\n");
        sb.append("  linkflags: " + linkflags + "\n");
        sb.append("  weak_frameworks: " + weakFrameworks + "\n");
        sb.append("  resources: " + resources + "\n");
        sb.append("  frameworks: " + frameworks + "\n");
        sb.append("  vendored_frameworks: " + vendoredFrameworks + "\n");
        sb.append("  libraries: " + libraries + "\n");
        sb.append("  parentSpec: " + ((parentSpec != null) ? parentSpec.name : "null") + "\n");
        for (Map.Entry<String, List<String>> resourceBundleEntry : resourceBundles.entrySet()) {
            sb.append("  resourceBundle: "  + resourceBundleEntry.getKey() + " files: " + resourceBundleEntry.getValue() + "\n");
        }
        return sb.toString();
    }
}
