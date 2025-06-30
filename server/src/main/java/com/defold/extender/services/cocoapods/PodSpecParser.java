package com.defold.extender.services.cocoapods;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.text.StringEscapeUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import com.defold.extender.ExtenderException;

public final class PodSpecParser {
    private static final Pattern BRACE_PATTERN = Pattern.compile("\\{([^{}]*)\\}");
    private static final String SWIFT_PARALLEL_FLAG = String.format("-j%d", Runtime.getRuntime().availableProcessors());

    public enum Platform {
        IPHONEOS,
        IPHONESIMULATOR,
        MACOSX,
        UNKNOWN
    }

    static boolean isIOS(Platform platform) {
        return platform == Platform.IPHONEOS || platform == Platform.IPHONESIMULATOR;
    }

    static boolean isMacOS(Platform platform) {
        return platform == Platform.MACOSX;
    }

    public static class CreatePodSpecArgs {
        public static class Builder {
            private JSONObject specJson;
            private File podsDir;
            private File buildDir;
            private PodSpec parentSpec;
            private Platform selectedPlatform;
            private String arch;
            private Map<String, Object> jobEnvContext;

            public Builder() { }

            public Builder setSpecJson(String specJson) throws ExtenderException {
                this.specJson = PodSpecParser.parseJson(specJson);
                return this;
            }

            public Builder setPodsDir(File podsDir) {
                this.podsDir = podsDir;
                return this;
            }

            public Builder setBuildDir(File buildDir) {
                this.buildDir = buildDir;
                return this;
            }

            public Builder setParentSpec(PodSpec parent) {
                this.parentSpec = parent;
                return this;
            }

            public Builder setJobContext(Map<String, Object> context) {
                this.jobEnvContext = context;
                return this;
            }

            public Builder setSelectedPlatform(Platform platform) {
                this.selectedPlatform = platform;
                return this;
            }

            public Builder setArch(String arch) {
                this.arch = arch;
                return this;
            }

            public CreatePodSpecArgs build() {
                return new CreatePodSpecArgs(this);
            }
        }

        private CreatePodSpecArgs(Builder builder) {
            this.specJson = builder.specJson;
            this.podsDir = builder.podsDir;
            this.buildDir = builder.buildDir;
            this.parentSpec = builder.parentSpec;
            this.jobEnvContext = builder.jobEnvContext;
            this.selectedPlatform = builder.selectedPlatform;
            this.arch = builder.arch;
        }

        private CreatePodSpecArgs(CreatePodSpecArgs copy) {
            this.specJson = copy.specJson;
            this.podsDir = copy.podsDir;
            this.buildDir = copy.buildDir;
            this.parentSpec = copy.parentSpec;
            this.jobEnvContext = copy.jobEnvContext;
            this.selectedPlatform = copy.selectedPlatform;
            this.arch = copy.arch;
        }

        protected JSONObject specJson;
        protected File podsDir;
        protected File buildDir;
        protected PodSpec parentSpec;
        protected Platform selectedPlatform;
        protected String arch;
        protected Map<String, Object> jobEnvContext;
    }

    // It seems like some exceptions such as KSCrash use a module name
    // which doesn't separate parent and subspec by an underscore
    // https://github.com/kstenerud/KSCrash/blob/master/KSCrash.podspec#L21
    private static Set<String> NAME_EXCEPTIONS = new HashSet<>(Arrays.asList("KSCrash"));


    // https://guides.cocoapods.org/syntax/podspec.html
    public static PodSpec createPodSpec(CreatePodSpecArgs args) throws ExtenderException, IOException {
        PodSpec spec = new PodSpec();
        JSONObject specJson = args.specJson;
        // spec.umbrellaHeaderGenerator = args.umbrellaHeaderGenerator;
        // spec.moduleMapGenerator = args.moduleMapGenerator;
        spec.name = (String)specJson.get("name");
        spec.moduleName = getModuleName(specJson, args.parentSpec);
        spec.version = (args.parentSpec == null) ? (String)specJson.get("version") : args.parentSpec.version;
        spec.dir = (args.parentSpec == null) ? new File(args.podsDir, spec.name) : args.parentSpec.dir;
        spec.parentSpec = args.parentSpec;

        // generated files relating to the pod
        // modulemap, swift header etc
        String configuration = "Debug";
        spec.buildDir = Path.of(args.buildDir.toString(), String.format("%s%s", configuration, args.selectedPlatform.toString().toLowerCase()), spec.name).toFile();
        spec.buildDir.mkdirs();

        // inherit flags and defines from the parent
        if (args.parentSpec != null) {
            spec.flags.addAll(args.parentSpec.flags);
            spec.defines.addAll(args.parentSpec.defines);
            spec.linkflags.addAll(args.parentSpec.linkflags);
            spec.flags.remove("-fmodule-name=" + args.parentSpec.moduleName);
        }

        // platform versions
        JSONObject platforms = (JSONObject)specJson.get("platforms");
        if (platforms != null) {
            if (isIOS(args.selectedPlatform)) {
                spec.platformVersion = (String)platforms.getOrDefault("ios", args.jobEnvContext.get("env.IOS_VERSION_MIN"));
            } else if (isMacOS(args.selectedPlatform)) {
                spec.platformVersion = (String)platforms.getOrDefault("osx", args.jobEnvContext.get("env.MACOS_VERSION_MIN"));
            }
        }

        // for multi platform settings
        JSONObject platformSettings = (JSONObject)specJson.get(isIOS(args.selectedPlatform) ? "ios" : "osx");

        // requires_arc flag
        // The 'requires_arc' option can also be a file pattern string or array
        // of files where arc should be enabled. See:
        // https://guides.cocoapods.org/syntax/podspec.html#requires_arc
        //
        // This is currently not supported and the presence of a string or array
        // will be treated as the default value (ie true)
        Boolean requiresArc = true;
        Object requiresArcObject = specJson.get("requires_arc");
        if (requiresArcObject instanceof Boolean) requiresArc = (Boolean)requiresArcObject;
        spec.flags.objc.add((requiresArc == null || requiresArc == true) ? "-fobjc-arc" : "-fno-objc-arc");
        spec.flags.objcpp.add((requiresArc == null || requiresArc == true) ? "-fobjc-arc" : "-fno-objc-arc");

        // compiler flags
        // https://guides.cocoapods.org/syntax/podspec.html#compiler_flags
        spec.flags.addAll(getAsSplitString(specJson, "compiler_flags"));
        if (platformSettings != null) spec.flags.addAll(getAsSplitString(platformSettings, "compiler_flags"));
        spec.flags.c.add("--language=c");
        spec.flags.cpp.add("--language=c++");
        spec.flags.objc.add("--language=objective-c");
        spec.flags.objcpp.add("--language=objective-c++");
        // CocoaPods sets CLANG_ENABLE_MODULES when creating an XCode project
        // https://xcodebuildsettings.com/#clang_enable_modules
        spec.flags.c.add("-fmodules");
        spec.flags.objc.add("-fmodules");
        spec.flags.objcpp.add("-fcxx-modules");
        // spec.flags.c.add("-fmodule-name=" + spec.moduleName);
        // spec.flags.objc.add("-fmodule-name=" + spec.moduleName);
        // spec.flags.objcpp.add("-fmodule-name=" + spec.moduleName);


        // resources
        // https://guides.cocoapods.org/syntax/podspec.html#resources
        spec.resources.addAll(getAsList(specJson, "resource"));
        if (platformSettings != null) spec.resources.addAll(getAsList(platformSettings, "resource"));

        // resource bundles
        // https://guides.cocoapods.org/syntax/podspec.html#resource_bundles
        spec.resourceBundles = getAsMapList(specJson, "resource_bundles");

        // frameworks
        // https://guides.cocoapods.org/syntax/podspec.html#frameworks
        spec.frameworks.addAll(getAsList(specJson, "frameworks"));
        if (platformSettings != null) spec.frameworks.addAll(getAsList(platformSettings, "frameworks"));

        // weak frameworks
        // https://guides.cocoapods.org/syntax/podspec.html#weak_frameworks
        spec.weakFrameworks.addAll(getAsList(specJson, "weak_frameworks"));
        if (platformSettings != null) spec.weakFrameworks.addAll(getAsList(platformSettings, "weak_frameworks"));

        // vendored frameworks
        // https://guides.cocoapods.org/syntax/podspec.html#vendored_frameworks
        List<String> vendored = getAsList(specJson, "vendored_frameworks");
        if (vendored != null) {
            spec.vendoredFrameworks.addAll(vendored);
        }
        if (platformSettings != null) {
            List<String> ios_vendored = getAsList(platformSettings, "vendored_frameworks");
            if (ios_vendored != null) {
                spec.vendoredFrameworks.addAll(ios_vendored);
            }
        }

        // libraries
        // https://guides.cocoapods.org/syntax/podspec.html#libraries
        spec.libraries.addAll(getAsList(specJson, "libraries"));
        if (platformSettings != null) spec.libraries.addAll(getAsList(platformSettings, "libraries"));
        if (spec.libraries.contains("c++")) {
            spec.flags.cpp.add("-std=c++11");
        }

        // parse subspecs
        // https://guides.cocoapods.org/syntax/podspec.html#subspec
        List<String> defaultSubSpecs = getStringListValues(specJson, "default_subspecs");
        if (defaultSubSpecs != null) {
            spec.defaultSubspecs.addAll(defaultSubSpecs);
        }
        JSONArray subspecs = getAsJSONArray(specJson, "subspecs");
        if (subspecs != null) {
            Iterator<JSONObject> it = subspecs.iterator();
            while (it.hasNext()) {
                JSONObject o = it.next();
                CreatePodSpecArgs innerSpecArgs = new CreatePodSpecArgs(args);
                innerSpecArgs.specJson = o;
                innerSpecArgs.parentSpec = spec;
                PodSpec subSpec = createPodSpec(innerSpecArgs);
                spec.subspecs.add(subSpec);
            }
        }

        // parse dependencies
        Map<String, List<Object>> dependencies = (Map<String, List<Object>>)specJson.get("dependencies");
        if (dependencies != null) {
            for (String dependency : dependencies.keySet()) {
                spec.dependencies.add(dependency);
            }
        }

        // find source and header files
        // https://guides.cocoapods.org/syntax/podspec.html#source_files
        List<String> sourceFiles = getAsList(specJson, "source_files");
        if (sourceFiles != null) {
            Iterator<String> it = sourceFiles.iterator();
            while (it.hasNext()) {
                String path = it.next();
                // don't copy header (and source) files from paths in xcframeworks
                // framework headers are copied in a separate step in copyPodFrameworks()
                if (!path.contains(".xcframework/")) {
                    addPodSourceFiles(spec, path);
                    addPodIncludePaths(spec, path);
                }
            }
        }

        // add swift libs to the runtime search path
        if (!spec.swiftSourceFiles.isEmpty()) {
            spec.linkflags.add("-Wl,-rpath,/usr/lib/swift");
            // spec.flags.swift.add(SWIFT_PARALLEL_FLAG);
        }

        // add ObjC link flag if pod contains Objective-C code
        for (File sourceFile : spec.sourceFiles) {
            String filename = sourceFile.getName();
            if (filename.endsWith(".m") || filename.endsWith(".mm")) {
                spec.linkflags.add("-ObjC");
                break;
            }
        }

        // parse generated xcconfig
        String configName = (args.parentSpec == null) ? spec.name : spec.parentSpec.name;
        XCConfigParser parser = new XCConfigParser(args.buildDir, args.podsDir, args.selectedPlatform.toString().toLowerCase(), configuration, args.arch);
        spec.parsedXCConfig = parser.parse(spec.moduleName, configName, Path.of(args.podsDir.toString(), "Target Support Files", configName, String.format("%s.debug.xcconfig", configName)).toFile());
        updateFlagsFromConfig(spec, spec.parsedXCConfig);

        // swift compatability header (just path where to store header)
        if (!spec.swiftSourceFiles.isEmpty()) {
            spec.swiftModuleHeader = Path.of(spec.buildDir.toString(), "SwiftCompatibilityHeader", spec.moduleName + "-Swift.h").toFile();
        }

        return spec;
    }

    static JSONObject parseJson(String json) throws ExtenderException {
        try {
            JSONParser parser = new JSONParser();
            return (JSONObject)parser.parse(json);
        }
        catch (ParseException e) {
            e.printStackTrace();
            throw new ExtenderException(e, "Failed to parse json. " + e);
        }
    }

    // https://github.com/CocoaPods/Core/blob/master/lib/cocoapods-core/specification.rb#L187
    static String getModuleName(JSONObject specJson, PodSpec parent) {
        if (specJson.containsKey("module_name")) {
            return (String)specJson.get("module_name");
        }

        if (specJson.containsKey("header_dir")) {
            return toC99extIdentifier((String)specJson.get("header_dir"));
        }

        String name = (String)specJson.get("name");
        String fixedName = toC99extIdentifier(name);
        if (parent == null) {
            return fixedName;
        }

        return parent.name + (NAME_EXCEPTIONS.contains(parent.name) ? "" : "_") + fixedName;
    }
    
    static String toC99extIdentifier(String s) {
        return s.replaceAll("^([0-9])", "_$1")  // 123FooBar -> _123FooBar
            .replaceAll("[\\+].*", "")          // NSData+zlib -> NSData
            .replaceAll("[^a-zA-Z0-9_]", "_")   // Foo-Bar -> Foo_Bar
            .replaceAll("_+", "_");             // Foo__Bar -> Foo_Bar
    }

    static void updateFlagsFromConfig(PodSpec spec, Map<String, String> parsedConfig) {
        // https://pewpewthespells.com/blog/buildsettings.html
        // defines
        List<String> defs = argumentsAsList(parsedConfig.getOrDefault("GCC_PREPROCESSOR_DEFINITIONS", null));
        if (defs != null) {
            spec.defines.addAll(unescapeStrings(defs));
        }
        // linker flags
        // https://xcodebuildsettings.com/#other_ldflags
        List<String> ldFlags = argumentsAsList(parsedConfig.getOrDefault("OTHER_LDFLAGS", null));
        if (ldFlags != null) {
            spec.linkflags.addAll(ldFlags);
        }
        // compiler flags for c and objc files
        // https://xcodebuildsettings.com/#other_cflags
        List<String> cFlags = argumentsAsList(parsedConfig.getOrDefault("OTHER_CFLAGS", null));
        if (cFlags != null) {
            spec.flags.c.addAll(cFlags);
            spec.flags.objc.addAll(cFlags);
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
            spec.flags.cpp.add(compilerFlag);
            spec.flags.objcpp.add(compilerFlag);
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
            spec.flags.c.add(compilerFlag);
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
            spec.flags.cpp.add(stdLibFlag);
            spec.flags.objcpp.add(stdLibFlag);
        }
        if (compareString(parsedConfig, "GCC_ENABLE_CPP_EXCEPTIONS", "YES")) {
            spec.flags.cpp.add("-fcxx-exceptions");
        }
        if (compareString(parsedConfig, "GCC_ENABLE_CPP_EXCEPTIONS", "NO")) {
            spec.flags.cpp.add("-fno-cxx-exceptions");
        }
        if (compareString(parsedConfig, "GCC_ENABLE_EXCEPTIONS", "YES")) {
            spec.flags.add("-fexceptions");
        }
        if (compareString(parsedConfig, "GCC_ENABLE_EXCEPTIONS", "NO")) {
            spec.flags.add("-fno-exceptions");
        }
        if (compareString(parsedConfig, "GCC_ENABLE_OBJC_EXCEPTIONS", "YES")) {
            spec.flags.objc.add("-fobjc-exceptions");
            spec.flags.objcpp.add("-fobjc-exceptions");
        }
        if (compareString(parsedConfig, "GCC_ENABLE_OBJC_EXCEPTIONS", "NO")) {
            spec.flags.objc.add("-fno-objc-exceptions");
            spec.flags.objcpp.add("-fno-objc-exceptions");
        }
        if (compareString(parsedConfig, "GCC_ENABLE_CPP_RTTI", "YES")) {
            spec.flags.cpp.add("-frtti");
        }
        if (compareString(parsedConfig, "GCC_ENABLE_CPP_RTTI", "NO")) {
            spec.flags.cpp.add("-fno-rtti");
        }
        if (compareString(parsedConfig, "GCC_ENABLE_OBJC_GC", "supported")) {
            spec.flags.objc.add("-fobjc-gc");
            spec.flags.objcpp.add("-fobjc-gc");
        }
        if (compareString(parsedConfig, "GCC_ENABLE_OBJC_GC", "required")) {
            spec.flags.objc.add("-fobjc-gc-only");
            spec.flags.objcpp.add("-fobjc-gc-only");
        }
        if (compareString(parsedConfig, "GCC_ENABLE_ASM_KEYWORD", "YES")) {
            spec.flags.add("-fasm");
        }
        if (compareString(parsedConfig, "GCC_ENABLE_ASM_KEYWORD", "NO")) {
            spec.flags.add("-fno-asm");
        }
        if (compareString(parsedConfig, "APPLICATION_EXTENSION_API_ONLY", "YES")) {
            spec.flags.add("-fapplication-extension");
            spec.flags.swift.add("-application-extension");
        }
        if (hasString(parsedConfig, "SWIFT_INCLUDE_PATHS")) {
            List<String> l = argumentsAsList(parsedConfig.getOrDefault("SWIFT_INCLUDE_PATHS", null));
            if (l != null) {
                for (String path : l) {
                    spec.flags.swift.add(String.format("-I%s", path));
                    spec.flags.swift.add(String.format("-Xcc -I%s", path));
                }
            }
        }
        if (hasString(parsedConfig, "OTHER_SWIFT_FLAGS")) {
            List<String> l = argumentsAsList(parsedConfig.getOrDefault("OTHER_SWIFT_FLAGS", null));
            if (l != null) {
                spec.flags.swift.addAll(l);
            }
        }
        if (hasString(parsedConfig, "HEADER_SEARCH_PATHS")) {
            List<String> l = argumentsAsList(parsedConfig.getOrDefault("HEADER_SEARCH_PATHS", null));
            if (l != null) {
                for (String path : l) {
                    spec.includePaths.add(new File(path));
                }
            }
        }
    }

    static List<String> argumentsAsList(String arguments) {
        arguments = arguments != null ? arguments.trim() : null;
        if (arguments == null || arguments.isEmpty()) {
            return null;
        }
        return new ArrayList<>(Arrays.asList(arguments.split(" ")));
    }

    // get values as List in case if value is List or String with ' ' delimeter
    static List<String> getStringListValues(JSONObject o, String key) {
        if (o.containsKey(key)) {
            Object value = o.get(key);
            List<String> result = null;
            if (value instanceof String) {
                result = getAsSplitString(o, key);
            } else if (value instanceof JSONArray) {
                result = (JSONArray)value;
            }
            if (result != null) {
                result.remove("$(inherited)");
            }

            Pattern p = Pattern.compile("\\$\\((\\w+)\\)");

            // substitute values if any placeholders are presented
            for (int idx = 0 ; idx < result.size(); ++idx) {
                String element = result.get(idx);
                Matcher matcher = p.matcher(element);
                if (matcher.find()) {
                    String replaceKey = matcher.group(1);
                    String replaceValue = (String)o.get(replaceKey);
                    element = element.replace(matcher.group(0), replaceValue);
                    result.set(idx, element);
                }
            }
            return result;
        }
        return null;
    }

    // check if a string value exists
    // will return false if the value doesn't exist or is an empty string
    static boolean hasString(Map<String, String> parsedConfig, String key) {
        String value = parsedConfig.get(key);
        return value != null && !value.trim().isEmpty();
    }

    // get a string value from a JSON object and split it into a list using space character as delimiter
    // will return an empty list if the value does not exist
    static List<String> getAsSplitString(JSONObject o, String key) {
        String value = (String)o.get(key);
        if (value == null || value.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(value.split(" ")));
    }

    static List<String> unescapeStrings(List<String> strings) {
        List<String> unescapedStrings = new ArrayList<>();
        for (String s : strings) {
            unescapedStrings.add(StringEscapeUtils.unescapeJava(s));
        }
        return unescapedStrings;
    }

    // get a string value from a JSON object
    // will return a default value if the value doesn't exist or is an empty string
    static String getAsString(JSONObject o, String key, String defaultValue) {
        String value = (String)o.get(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value;
    }

    // check if the value for a specific key matches an expected value
    static boolean compareString(Map<String, String> config, String key, String expected) {
        String value = config.get(key);
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        return value.equals(expected);
    }

    // get the value of a key as a JSONArray even it is a single value
    static JSONArray getAsJSONArray(JSONObject o, String key) {
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

    // get a string value from a JSON object
    // will return null if the value doesn't exist or is an empty string
    static String getAsString(JSONObject o, String key) {
        return getAsString(o, key, null);
    }

    public static List<String> expandBraces(String input) {
        List<String> results = new ArrayList<>();
        Matcher matcher = BRACE_PATTERN.matcher(input);

        if (matcher.find()) {
            String before = input.substring(0, matcher.start());
            String after = input.substring(matcher.end());
            String[] options = matcher.group(1).split(",");

            for (String option : options) {
                String combined = before + option + after;
                results.addAll(expandBraces(combined));
            }
        } else {
            results.add(input);
        }

        return results;
    }

    // get value as string list. If value contains {} - expand it into separate values
    static List<String> getAsList(JSONObject o, String key) {
        List<String> result = new ArrayList<>();
        Object value = o.get(key);
        if (value instanceof JSONArray) {
            List<String> tmp = (JSONArray)value;
            for (String element : tmp) {
                result.addAll(expandBraces(element));
            }
        } else if (value instanceof String) {
            result.addAll(expandBraces((String)value));
        }
        return result;
    }

    // get value as map. If value contains {} - expand it into separate values
    static Map<String, List<String>> getAsMapList(JSONObject o, String key) {
        Map<String, List<String>> result = new HashMap<>();
        Object value = o.get(key);
        if (value instanceof JSONObject) {
            JSONObject tmp = (JSONObject)value;
            for (Object innerKey : tmp.keySet()) {
                String innerKeyStr = (String)innerKey;
                result.put(innerKeyStr, getAsList(tmp, innerKeyStr));
            }
        }
        return result;
    }
    
    /**
     * Add source files matching a pattern to a pod
     * @param pod
     * @param pattern Source file pattern (glob format)
     */
    static void addPodSourceFiles(PodSpec pod, String pattern) {
        List<File> podSrcFiles = PodUtils.listFilesGlob(pod.dir, pattern);
        for (File podSrcFile : podSrcFiles) {
            final String filename = podSrcFile.getName();
            if (filename.endsWith(".swift")) {
                pod.swiftSourceFiles.add(podSrcFile);
                pod.swiftSourceFilePaths.add(podSrcFile.getAbsolutePath());
            }
            else {
                if (!PodUtils.isHeaderFile(filename)) {
                    pod.sourceFiles.add(podSrcFile);
                } else {
                    pod.headerFiles.add(podSrcFile);
                }
            }
        }
    }


    /**
     * Add a list of include paths matching a pattern to a pod
     * @param pod
     * @param pattern Source file pattern (glob format)
     */
    static void addPodIncludePaths(PodSpec pod, String pattern) {
        List<File> podSrcFiles = PodUtils.listFilesGlob(pod.dir, pattern);
        for (File podSrcFile : podSrcFiles) {
            final String filename = podSrcFile.getName();
            if (PodUtils.isHeaderFile(filename)) {
                File podIncludeDir = podSrcFile.getParentFile();
                if (podIncludeDir != null) {
                    pod.includePaths.add(podIncludeDir);
                    File podIncludeParentDir = podIncludeDir.getParentFile();
                    if (podIncludeParentDir != null) {
                        pod.includePaths.add(podIncludeParentDir);
                    }
                }
            }
        }
    }
}
