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
    // https://github.com/CocoaPods/CocoaPods/blob/648ccdcaea2063fe63977a0146e1717aec3efa54/lib/cocoapods/target.rb#L157
    public String version = "";
    public String platformVersion = "";
    // The Swift source file header (ModuleName-Swift.h)
    // This file is referenced from the modulemap and generated in
    // Extender.java as part of the process when building .swift files
    public File swiftModuleHeader = null;
    public String swiftModuleDefinition = null;
    public Set<String> swiftSourceFilePaths = new LinkedHashSet<>(); // list of absolute file path which used to create temprorary file with paths to include into comile command as @tmp_path
    public Set<File> swiftSourceFiles = new LinkedHashSet<>();
    public Set<File> sourceFiles = new LinkedHashSet<>();
    public Set<File> publicHeaders = new LinkedHashSet<>();
    public Set<File> includePaths = new LinkedHashSet<>();
    public Set<File> frameworkSearchPaths = new LinkedHashSet<>();
    public PodSpec parentSpec = null;
    public List<String> defaultSubspecs = new ArrayList<>();
    public List<PodSpec> subspecs = new ArrayList<>();
    public List<String> dependencies = new ArrayList<>();
    public Map<String, List<String>> resourceBundles = new HashMap<>();

    public LanguageSet flags = new LanguageSet();
    public Set<String> defines = new HashSet<>();
    public Set<String> linkflags = new HashSet<>();
    public Set<String> vendoredFrameworks = new LinkedHashSet<>();
    public Set<String> weakFrameworks = new HashSet<>();
    public Set<String> resources = new HashSet<>();
    public Set<String> frameworks = new HashSet<>();
    public Set<String> libraries = new HashSet<>();
    public Map<String, String> parsedXCConfig = null;
    public File dir;
    public File buildDir;

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
        sb.append("  dir: " + dir + "\n");
        sb.append("  moduleName: " + moduleName + "\n");
        sb.append("  generated dir: " + buildDir + "\n");
        sb.append("  src: " + sourceFiles + "\n");
        sb.append("  swift src: " + swiftSourceFiles + "\n");
        sb.append("  swift module header: " + swiftModuleHeader + "\n");
        sb.append("  includes: " + includePaths + "\n");
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
