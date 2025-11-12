package com.defold.extender.services.cocoapods;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.text.StringEscapeUtils;

// similar to PodSpec but contains some runtime information that used during the build
public class PodBuildSpec {
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
    public Set<File> privateHeaders = new LinkedHashSet<>();
    public Set<File> includePaths = new LinkedHashSet<>();
    public Set<File> frameworkSearchPaths;
    public Map<String, List<String>> resourceBundles;

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
    public File intermediatedDir;
    public File headerMapFile;
    public File vfsOverlay;
    public Collection<PodBuildSpec> dependantSpecs = new HashSet<>();

    PodBuildSpec() {}

    public PodBuildSpec(CreateBuildSpecArgs args, PodSpec mainSpec) throws IOException {
        this.name = mainSpec.name;
        this.moduleName = mainSpec.moduleName;
        this.version = mainSpec.version;
        this.platformVersion = mainSpec.platformVersion;
        this.frameworkSearchPaths = new LinkedHashSet<>(mainSpec.frameworkSearchPaths);
        this.resourceBundles = new HashMap<>(mainSpec.resourceBundles);

        this.flags = new LanguageSet(mainSpec.flags);
        this.defines = new HashSet<>(mainSpec.defines);
        this.linkflags = new ArrayList<>(mainSpec.linkflags);
        this.vendoredFrameworks = new LinkedHashSet<>(mainSpec.vendoredFrameworks);
        this.weakFrameworks = new HashSet<>(mainSpec.weakFrameworks);
        this.resources = new HashSet<>(mainSpec.resources);
        this.frameworks = new HashSet<>(mainSpec.frameworks);
        this.libraries = new HashSet<>(mainSpec.libraries);


        String platformVersionKey = null;
        if (PodUtils.isIOS(args.selectedPlatform)) {
            platformVersionKey = "env.IOS_VERSION_MIN";
        } else if (PodUtils.isMacOS(args.selectedPlatform)) {
            platformVersionKey = "env.MACOS_VERSION_MIN";
        }

        this.platformVersion = !mainSpec.platformVersion.isEmpty() ? 
            mainSpec.platformVersion :
            (String)args.jobEnvContext.get(platformVersionKey);

        this.dir = new File(args.podsDir, this.name);

        // generated files relating to the pod
        // modulemap, swift header etc
        this.buildDir = Path.of(args.buildDir.toString(), String.format("%s%s", args.configuration, args.selectedPlatform.toString().toLowerCase()), mainSpec.getPodName()).toFile();
        this.buildDir.mkdirs();

        this.intermediatedDir = new File(this.buildDir, "intermediate");
        this.intermediatedDir.mkdirs();

        collectSourceFilesFromSpec(mainSpec);

        // parse generated xcconfig
        String configName = this.name;
        this.parsedXCConfig = args.configParser.parse(this.moduleName, configName, Path.of(args.podsDir.toString(), "Target Support Files", configName, String.format("%s.%s.xcconfig", configName, args.configuration.toLowerCase())).toFile());
        updateFlagsFromConfig(parsedXCConfig);

        this.headerMapFile = new File(this.intermediatedDir, String.format("%s.hmap", mainSpec.getPodName()));

        this.flags.add(String.format("-iquote %s", this.headerMapFile.toString()));
        this.flags.swift.add(String.format("-Xcc -iquote -Xcc %s", this.headerMapFile.toString()));

        this.vfsOverlay = new File(this.intermediatedDir, "all_files.yaml");
        this.flags.add(String.format("-ivfsoverlay %s", this.vfsOverlay.toString()));
        this.flags.swift.add(String.format("-Xcc -ivfsoverlay -Xcc %s", this.vfsOverlay.toString()));
    }

    public void addSubSpec(PodSpec subSpec) {
        collectSourceFilesFromSpec(subSpec);

        frameworkSearchPaths.addAll(subSpec.frameworkSearchPaths);
        resourceBundles.putAll(subSpec.resourceBundles);

        flags.addAll(subSpec.flags);
        defines.addAll(subSpec.defines);
        linkflags.addAll(subSpec.linkflags);
        vendoredFrameworks.addAll(subSpec.vendoredFrameworks);
        weakFrameworks.addAll(subSpec.weakFrameworks);
        resources.addAll(subSpec.resources);
        frameworks.addAll(subSpec.frameworks);
        libraries.addAll(subSpec.libraries);
    }

    void updateFlagsFromConfig(Map<String, String> parsedConfig) {
        // https://pewpewthespells.com/blog/buildsettings.html
        // defines
        List<String> defs = argumentsAsList(parsedConfig.getOrDefault("GCC_PREPROCESSOR_DEFINITIONS", null));
        if (defs != null) {
            this.defines.addAll(unescapeStrings(defs));
        }
        // linker flags
        // https://xcodebuildsettings.com/#other_ldflags
        List<String> ldFlags = argumentsAsList(parsedConfig.getOrDefault("OTHER_LDFLAGS", null));
        if (ldFlags != null) {
            this.linkflags.addAll(ldFlags);
        }
        // compiler flags for c and objc files
        // https://xcodebuildsettings.com/#other_cflags
        List<String> cFlags = argumentsAsList(parsedConfig.getOrDefault("OTHER_CFLAGS", null));
        if (cFlags != null) {
            this.flags.c.addAll(cFlags);
            this.flags.objc.addAll(cFlags);
        }
        // compiler flags
        // https://developer.apple.com/documentation/xcode/build-settings-reference#C++-Language-Dialect
        if (hasString(parsedConfig, "CLANG_CXX_LANGUAGE_STANDARD")) {
            String cppStandard = parsedConfig.getOrDefault("CLANG_CXX_LANGUAGE_STANDARD", "compiler-default");
            String compilerFlag = "";
            switch (cppStandard) {
                case "c++98":   compilerFlag = "-std=c++98"; break;
                case "c++11":
                case "c++0x":   compilerFlag = "-std=c++11"; break;
                case "gnu++11":
                case "gnu++0x": compilerFlag = "-std=gnu++11"; break;
                case "c++14":   compilerFlag = "-std=c++14"; break;
                case "gnu++14": compilerFlag = "-std=gnu++17"; break;
                case "c++17":   compilerFlag = "-std=c++17"; break;
                case "gnu++17": compilerFlag = "-std=gnu++17"; break;
                case "c++20":   compilerFlag = "-std=c++20"; break;
                case "gnu++20": compilerFlag = "-std=gnu++20"; break;
                case "gnu++98": 
                case "compiler-default":
                default:  compilerFlag = "-std=gnu++98"; break;
            }
            this.flags.cpp.add(compilerFlag);
            this.flags.objcpp.add(compilerFlag);
        }
        if (hasString(parsedConfig, "GCC_C_LANGUAGE_STANDARD")) {
            String cStandard = parsedConfig.getOrDefault("GCC_C_LANGUAGE_STANDARD", "compiler-default");
            String compilerFlag = "";
            switch (cStandard) {
                case "ansi":  compilerFlag = "-ansi"; break;
                case "c89":   compilerFlag = "-std=c89"; break;
                case "gnu89": compilerFlag = "-std=gnu89"; break;
                case "c99":   compilerFlag = "-std=c99"; break;
                case "c11":   compilerFlag = "-std=c11"; break;
                case "gnu11": compilerFlag = "-std=gnu11"; break;
                case "gnu99":
                case "compiler-default": 
                default:  compilerFlag = "-std=gnu99"; break;
            }
            this.flags.c.add(compilerFlag);
        }
        if (hasString(parsedConfig, "CLANG_CXX_LIBRARY")) {
            String stdLib = parsedConfig.getOrDefault("CLANG_CXX_LIBRARY", "compiler-default");
            String stdLibFlag = "";
            switch (stdLib) {
                case "libc++": stdLibFlag = "-stdlib=libc++"; break;
                case "libstdc++":
                case "compiler-default":
                default: stdLibFlag = "-stdlib=libstdlibc++"; break;
            }
            this.flags.cpp.add(stdLibFlag);
            this.flags.objcpp.add(stdLibFlag);
        }
        if (compareString(parsedConfig, "GCC_ENABLE_CPP_EXCEPTIONS", "YES")) {
            this.flags.cpp.add("-fcxx-exceptions");
        }
        if (compareString(parsedConfig, "GCC_ENABLE_CPP_EXCEPTIONS", "NO")) {
            this.flags.cpp.add("-fno-cxx-exceptions");
        }
        if (compareString(parsedConfig, "GCC_ENABLE_EXCEPTIONS", "YES")) {
            this.flags.add("-fexceptions");
        }
        if (compareString(parsedConfig, "GCC_ENABLE_EXCEPTIONS", "NO")) {
            this.flags.add("-fno-exceptions");
        }
        if (compareString(parsedConfig, "GCC_ENABLE_OBJC_EXCEPTIONS", "YES")) {
            this.flags.objc.add("-fobjc-exceptions");
            this.flags.objcpp.add("-fobjc-exceptions");
        }
        if (compareString(parsedConfig, "GCC_ENABLE_OBJC_EXCEPTIONS", "NO")) {
            this.flags.objc.add("-fno-objc-exceptions");
            this.flags.objcpp.add("-fno-objc-exceptions");
        }
        if (compareString(parsedConfig, "GCC_ENABLE_CPP_RTTI", "YES")) {
            this.flags.cpp.add("-frtti");
        }
        if (compareString(parsedConfig, "GCC_ENABLE_CPP_RTTI", "NO")) {
            this.flags.cpp.add("-fno-rtti");
        }
        if (compareString(parsedConfig, "GCC_ENABLE_OBJC_GC", "supported")) {
            this.flags.objc.add("-fobjc-gc");
            this.flags.objcpp.add("-fobjc-gc");
        }
        if (compareString(parsedConfig, "GCC_ENABLE_OBJC_GC", "required")) {
            this.flags.objc.add("-fobjc-gc-only");
            this.flags.objcpp.add("-fobjc-gc-only");
        }
        if (compareString(parsedConfig, "GCC_ENABLE_ASM_KEYWORD", "YES")) {
            this.flags.add("-fasm");
        }
        if (compareString(parsedConfig, "GCC_ENABLE_ASM_KEYWORD", "NO")) {
            this.flags.add("-fno-asm");
        }
        if (compareString(parsedConfig, "APPLICATION_EXTENSION_API_ONLY", "YES")) {
            this.flags.add("-fapplication-extension");
            this.flags.swift.add("-application-extension");
        }
        if (hasString(parsedConfig, "SWIFT_INCLUDE_PATHS")) {
            List<String> l = argumentsAsList(parsedConfig.getOrDefault("SWIFT_INCLUDE_PATHS", null));
            if (l != null) {
                for (String path : l) {
                    this.flags.swift.add(String.format("-I%s", path));
                    this.flags.swift.add(String.format("-Xcc -I%s", path));
                }
            }
        }
        if (hasString(parsedConfig, "OTHER_SWIFT_FLAGS")) {
            List<String> l = argumentsAsList(parsedConfig.getOrDefault("OTHER_SWIFT_FLAGS", null));
            if (l != null) {
                this.flags.swift.addAll(l);
            }
        }
        if (hasString(parsedConfig, "HEADER_SEARCH_PATHS")) {
            List<String> l = argumentsAsList(parsedConfig.getOrDefault("HEADER_SEARCH_PATHS", null));
            if (l != null) {
                for (String path : l) {
                    this.includePaths.add(new File(path));
                }
            }
        }
        if (hasString(parsedConfig, "FRAMEWORK_SEARCH_PATHS")) {
            List<String> l = argumentsAsList(parsedConfig.getOrDefault("FRAMEWORK_SEARCH_PATHS", null));
            if (l != null) {
                for (String path : l) {
                    this.frameworkSearchPaths.add(new File(path));
                }
            }
        }
        String compileModuleName = parsedConfig.getOrDefault("PRODUCT_MODULE_NAME", this.name);
        this.flags.add(String.format("-fmodule-name=%s", compileModuleName));
    }

    // check if a string value exists
    // will return false if the value doesn't exist or is an empty string
    static boolean hasString(Map<String, String> parsedConfig, String key) {
        String value = parsedConfig.get(key);
        return value != null && !value.trim().isEmpty();
    }

    static List<String> unescapeStrings(List<String> strings) {
        List<String> unescapedStrings = new ArrayList<>();
        for (String s : strings) {
            unescapedStrings.add(StringEscapeUtils.unescapeJava(s));
        }
        return unescapedStrings;
    }

    // check if the value for a specific key matches an expected value
    static boolean compareString(Map<String, String> config, String key, String expected) {
        String value = config.get(key);
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        return value.equals(expected);
    }

    static List<String> argumentsAsList(String arguments) {
        arguments = arguments != null ? arguments.trim() : null;
        if (arguments == null || arguments.isEmpty()) {
            return null;
        }
        return new ArrayList<>(Arrays.asList(arguments.split(" ")));
    }

    /**
     * Add source files matching a pattern to a pod
     * @param pattern Source file pattern (glob format)
     */
    void addPodSourceFiles(String pattern) {
        List<File> podSrcFiles = PodUtils.listFilesGlob(this.dir, pattern);
        for (File podSrcFile : podSrcFiles) {
            final String filename = podSrcFile.getName();
            if (filename.endsWith(".swift")) {
                this.swiftSourceFiles.add(podSrcFile);
                this.swiftSourceFilePaths.add(podSrcFile.getAbsolutePath());
            }
            else if (!PodUtils.isHeaderFile(filename)) {
                this.sourceFiles.add(podSrcFile);
            }
        }
    }

    /**
     * Add a list of include paths matching a pattern to a pod
     * @param pattern Source file pattern (glob format)
     */
    void addPodIncludePaths(String pattern) {
        List<File> podSrcFiles = PodUtils.listFilesGlob(this.dir, pattern);
        for (File podSrcFile : podSrcFiles) {
            final String filename = podSrcFile.getName();
            if (PodUtils.isHeaderFile(filename)) {
                if (!this.publicHeaders.contains(podSrcFile)) {
                    this.privateHeaders.add(podSrcFile);
                }
            }
        }
    }

    void collectSourceFilesFromSpec(PodSpec spec) {
        for (String pattern : spec.publicHeadersPatterns) {
            this.publicHeaders.addAll(PodUtils.listFilesGlob(this.dir, pattern));
        }

        Iterator<String> it = spec.sourceFilesPatterns.iterator();
        while (it.hasNext()) {
            String path = it.next();
            // don't copy header (and source) files from paths in xcframeworks
            // framework headers are copied in a separate step in copyPodFrameworks()
            if (!path.contains(".xcframework/")) {
                addPodSourceFiles(path);
                addPodIncludePaths(path);
            }
        }

        // add swift libs to the runtime search path
        if (!this.swiftSourceFiles.isEmpty()) {
            this.linkflags.add("-Wl,-rpath,/usr/lib/swift");
// ******************************** Added for backward comapatibility *****************************************
// ******************************** Remove after 6 month ******************************************************
            this.flags.swift.add("-import-underlying-module");
// ************************************************************************************************************
        }

        // add ObjC link flag if pod contains Objective-C code
        for (File sourceFile : this.sourceFiles) {
            String filename = sourceFile.getName();
            if (filename.endsWith(".m") || filename.endsWith(".mm")) {
                this.linkflags.add("-ObjC");
                break;
            }
        }


        // swift compatability header (just path where to store header)
        if (this.swiftModuleHeader == null && !this.swiftSourceFiles.isEmpty()) {
            this.swiftModuleHeader = Path.of(this.buildDir.toString(), "SwiftCompatibilityHeader", this.moduleName + "-Swift.h").toFile();
        }
    }
}
