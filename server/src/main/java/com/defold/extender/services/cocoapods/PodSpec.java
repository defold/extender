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
    public List<String> linkflags = new ArrayList<>();
    public Set<String> vendoredFrameworks = new LinkedHashSet<>();
    public Set<String> weakFrameworks = new HashSet<>();
    public Set<String> resources = new HashSet<>();
    public Set<String> frameworks = new HashSet<>();
    public Set<String> libraries = new HashSet<>();
    public Map<String, String> parsedXCConfig = null;
    public File dir;
    public File buildDir;

    public PodSpec() {}

    public PodSpec(PodSpec spec) {
        this.name = spec.name;
        this.moduleName = spec.moduleName;
        this.version = spec.version;
        this.platformVersion = spec.platformVersion;
        this.swiftModuleHeader = spec.swiftModuleHeader;
        this.swiftModuleDefinition = spec.swiftModuleDefinition;
        this.swiftSourceFilePaths = new LinkedHashSet<>(spec.swiftSourceFilePaths);
        this.swiftSourceFiles = new LinkedHashSet<>(spec.swiftSourceFiles);
        this.sourceFiles = new LinkedHashSet<>(spec.sourceFiles);
        this.publicHeaders = new LinkedHashSet<>(spec.publicHeaders);
        this.includePaths = new LinkedHashSet<>(spec.includePaths);
        this.frameworkSearchPaths = new LinkedHashSet<>(spec.frameworkSearchPaths);
        this.dependencies = new ArrayList<>(spec.dependencies);
        this.resourceBundles = new HashMap<>(spec.resourceBundles);

        this.flags = new LanguageSet(spec.flags);
        this.defines = new HashSet<>(spec.defines);
        this.linkflags = new ArrayList<>(spec.linkflags);
        this.vendoredFrameworks = new LinkedHashSet<>(spec.vendoredFrameworks);
        this.weakFrameworks = new HashSet<>(spec.weakFrameworks);
        this.resources = new HashSet<>(spec.resources);
        this.frameworks = new HashSet<>(spec.frameworks);
        this.libraries = new HashSet<>(spec.libraries);
        this.dir = spec.dir;
        this.buildDir = spec.buildDir;
    }

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

    // merge specB into specA and return updated specA
    static PodSpec mergeSpecs(PodSpec specA, PodSpec specB) {
        specA.swiftSourceFilePaths.addAll(specB.swiftSourceFilePaths);
        specA.swiftSourceFiles.addAll(specB.swiftSourceFiles);
        specA.sourceFiles.addAll(specB.sourceFiles);
        specA.publicHeaders.addAll(specB.publicHeaders);
        specA.includePaths.addAll(specB.includePaths);
        specA.frameworkSearchPaths.addAll(specB.frameworkSearchPaths);
        specA.dependencies.addAll(specB.dependencies);
        specA.resourceBundles.putAll(specB.resourceBundles);

        specA.flags.addAll(specB.flags);
        specA.defines.addAll(specB.defines);
        specA.linkflags.addAll(specB.linkflags);
        specA.vendoredFrameworks.addAll(specB.vendoredFrameworks);
        specA.weakFrameworks.addAll(specB.weakFrameworks);
        specA.resources.addAll(specB.resources);
        specA.frameworks.addAll(specB.frameworks);
        specA.libraries.addAll(specB.libraries);
        if (specA.swiftModuleHeader == null) {
            specA.swiftModuleHeader = specB.swiftModuleHeader;
        }
        if (specA.swiftModuleDefinition == null) {
            specA.swiftModuleDefinition = specB.swiftModuleDefinition;
        }

        return specA;
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
