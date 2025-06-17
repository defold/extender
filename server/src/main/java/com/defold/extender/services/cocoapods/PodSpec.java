package com.defold.extender.services.cocoapods;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public class PodSpec {
    public String name = "";
    public String moduleName = "";
    // https://github.com/CocoaPods/CocoaPods/blob/648ccdcaea2063fe63977a0146e1717aec3efa54/lib/cocoapods/target.rb#L157
    public String productModuleName = "";
    public String version = "";
    public String iosversion = "";
    public String osxversion = "";
    public String iosModuleMap = null;
    public String osxModuleMap = null;
    public String iosUmbrellaHeader = null;
    public String osxUmbrellaHeader = null;
    // Set to true if this Pod contains at least one .xcframework
    public Boolean containsFramework = false;
    // The Swift source file header (ModuleName-Swift.h)
    // This file is referenced from the modulemap and generated in
    // Extender.java as part of the process when building .swift files
    public File swiftModuleHeader = null;
    public String iosSwiftModuleMap = null;
    public String osxSwiftModuleMap = null;
    public Set<String> swiftSourceFilePaths = new LinkedHashSet<>();
    public Set<File> swiftSourceFiles = new LinkedHashSet<>();
    public Set<File> sourceFiles = new LinkedHashSet<>();
    public Set<File> headerFiles = new LinkedHashSet<>();
    public Set<File> includePaths = new LinkedHashSet<>();
    public PodSpec parentSpec = null;
    public List<String> defaultSubspecs = new ArrayList<>();
    public List<PodSpec> subspecs = new ArrayList<>();
    public List<String> dependencies = new ArrayList<>();
    public Map<String, List<String>> resourceBundles = new HashMap<>();

    public PlatformAndLanguageSet flags = new PlatformAndLanguageSet();
    public PlatformSet defines = new PlatformSet();
    public PlatformSet linkflags = new PlatformSet();
    public Set<String> vendoredFrameworks = new LinkedHashSet<>();
    public PlatformSet weakFrameworks = new PlatformSet();
    public PlatformSet resources = new PlatformSet();
    public PlatformSet frameworks = new PlatformSet();
    public PlatformSet libraries = new PlatformSet();
    public PlatformSet publicHeaders = new PlatformSet();
    public PlatformSet privateHeaders = new PlatformSet();
    public File dir;
    public File generatedDir;
    // true if this podspec was installed by Cocoapods
    public boolean installed;

    public PodSpec getSubspec(String name) {
        for (PodSpec spec : subspecs) {
            if (spec.name.equals(name)) {
                return spec;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(name + ":" + version + "\n");
        sb.append("  dir: " + dir + "\n");
        sb.append("  productModuleName" + productModuleName + "\n");
        sb.append("  moduleName: " + moduleName + "\n");
        sb.append("  generated dir: " + generatedDir + "\n");
        sb.append("  installed: " + installed + "\n");
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
        sb.append("  dependencies: " + dependencies + "\n");
        sb.append("  parentSpec: " + ((parentSpec != null) ? parentSpec.name : "null") + "\n");
        sb.append("  default_subspecs: " + defaultSubspecs + "\n");
        for (PodSpec sub : subspecs) {
            sb.append("  subspec: " + sub.name + "\n");
        }
        for (Map.Entry<String, List<String>> resourceBundleEntry : resourceBundles.entrySet()) {
            sb.append("  resourceBundle: "  + resourceBundleEntry.getKey() + " files: " + resourceBundleEntry.getValue() + "\n");
        }
        return sb.toString();
    }

    public Function<Map<String, Object>, String> umbrellaHeaderGenerator = null;
    public Function<Map<String, Object>, String> moduleMapGenerator = null;
}
