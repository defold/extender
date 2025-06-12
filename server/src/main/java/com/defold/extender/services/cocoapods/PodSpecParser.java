package com.defold.extender.services.cocoapods;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.text.StringEscapeUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import com.defold.extender.ExtenderException;
import com.defold.extender.ExtenderUtil;

public final class PodSpecParser {

    private static final Pattern BRACE_PATTERN = Pattern.compile("\\{([^{}]*)\\}");

    public static class CreatePodSpecArgs {
        public static class Builder {
            private JSONObject specJson;
            private File podsDir;
            private File generatedDir;
            private File workingDir;
            private PodSpec parentSpec;
            private Map<String, Object> jobEnvContext;
            private Function<Map<String, Object>, String> umbrellaHeaderGenerator;
            private Function<Map<String, Object>, String> moduleMapGenerator;

            public Builder() { }

            public Builder setSpecJson(String specJson) throws ExtenderException {
                this.specJson = PodSpecParser.parseJson(specJson);
                return this;
            }

            public Builder setPodsDir(File podsDir) {
                this.podsDir = podsDir;
                return this;
            }

            public Builder setGeneratedDir(File generatedDir) {
                this.generatedDir = generatedDir;
                return this;
            }

            public Builder setWorkingDir(File workingDir) {
                this.workingDir = workingDir;
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

            public Builder setUmbrellaHeaderGenerator(Function<Map<String, Object>, String> generator) {
                this.umbrellaHeaderGenerator = generator;
                return this;
            }

            public Builder setModuleMapGenerator(Function<Map<String, Object>, String> generator) {
                this.moduleMapGenerator = generator;
                return this;
            }

            public CreatePodSpecArgs build() {
                return new CreatePodSpecArgs(this);
            }
        }

        private CreatePodSpecArgs(Builder builder) {
            this.specJson = builder.specJson;
            this.podsDir = builder.podsDir;
            this.generatedDir = builder.generatedDir;
            this.workingDir = builder.workingDir;
            this.parentSpec = builder.parentSpec;
            this.jobEnvContext = builder.jobEnvContext;
            this.umbrellaHeaderGenerator = builder.umbrellaHeaderGenerator;
            this.moduleMapGenerator = builder.moduleMapGenerator;
        }

        private CreatePodSpecArgs(CreatePodSpecArgs copy) {
            this.specJson = copy.specJson;
            this.podsDir = copy.podsDir;
            this.generatedDir = copy.generatedDir;
            this.workingDir = copy.workingDir;
            this.parentSpec = copy.parentSpec;
            this.jobEnvContext = copy.jobEnvContext;
            this.umbrellaHeaderGenerator = copy.umbrellaHeaderGenerator;
            this.moduleMapGenerator = copy.moduleMapGenerator;
        }

        protected JSONObject specJson;
        protected File podsDir;
        protected File generatedDir;
        protected File workingDir;
        protected PodSpec parentSpec;
        protected Map<String, Object> jobEnvContext;
        protected Function<Map<String, Object>, String> umbrellaHeaderGenerator;
        protected Function<Map<String, Object>, String> moduleMapGenerator;
    }

    // It seems like some exceptions such as KSCrash use a module name
    // which doesn't separate parent and subspec by an underscore
    // https://github.com/kstenerud/KSCrash/blob/master/KSCrash.podspec#L21
    private static Set<String> NAME_EXCEPTIONS = new HashSet<>(Arrays.asList("KSCrash"));


    // https://guides.cocoapods.org/syntax/podspec.html
    public static PodSpec createPodSpec(CreatePodSpecArgs args) throws ExtenderException {
        PodSpec spec = new PodSpec();
        JSONObject specJson = args.specJson;
        spec.umbrellaHeaderGenerator = args.umbrellaHeaderGenerator;
        spec.moduleMapGenerator = args.moduleMapGenerator;
        spec.name = (String)specJson.get("name");
        spec.productModuleName = args.parentSpec == null ? toC99extIdentifier(spec.name) : args.parentSpec.productModuleName;
        spec.moduleName = getModuleName(specJson, args.parentSpec);
        spec.version = (args.parentSpec == null) ? (String)specJson.get("version") : args.parentSpec.version;
        spec.dir = (args.parentSpec == null) ? new File(args.podsDir, spec.name) : args.parentSpec.dir;
        spec.parentSpec = args.parentSpec;

        // generated files relating to the pod
        // modulemap, swift header etc
        spec.generatedDir = new File(args.generatedDir, spec.name);
        spec.generatedDir.mkdirs();

        // inherit flags and defines from the parent
        if (args.parentSpec != null) {
            spec.flags.ios.addAll(args.parentSpec.flags.ios);
            spec.flags.osx.addAll(args.parentSpec.flags.osx);
            spec.defines.ios.addAll(args.parentSpec.defines.ios);
            spec.defines.osx.addAll(args.parentSpec.defines.osx);
            spec.linkflags.ios.addAll(args.parentSpec.linkflags.ios);
            spec.linkflags.ios.addAll(args.parentSpec.linkflags.ios);
            spec.flags.remove("-fmodule-name=" + args.parentSpec.productModuleName);
        }

        // platform versions
        JSONObject platforms = (JSONObject)specJson.get("platforms");
        if (platforms != null) {
            spec.iosversion = (String)platforms.getOrDefault("ios", args.jobEnvContext.get("env.IOS_VERSION_MIN"));
        }
        if (platforms != null) {
            spec.osxversion = (String)platforms.getOrDefault("osx", args.jobEnvContext.get("env.MACOS_VERSION_MIN"));
        }

        // for multi platform settings
        JSONObject ios = (JSONObject)specJson.get("ios");
        JSONObject osx = (JSONObject)specJson.get("osx");

        // flags and defines
        // https://guides.cocoapods.org/syntax/podspec.html#pod_target_xcconfig
        // https://guides.cocoapods.org/syntax/podspec.html#user_target_xcconfig
        parseMultiPlatformConfig(spec, (JSONObject)specJson.get("pod_target_xcconfig"));
        parseMultiPlatformConfig(spec, (JSONObject)specJson.get("user_target_xcconfig")); // not recommended for use but we need to handle it
        parseMultiPlatformConfig(spec, (JSONObject)specJson.get("xcconfig"));  // undocumented but used by some pods

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
        spec.flags.ios.objc.add((requiresArc == null || requiresArc == true) ? "-fobjc-arc" : "-fno-objc-arc");
        spec.flags.ios.objcpp.add((requiresArc == null || requiresArc == true) ? "-fobjc-arc" : "-fno-objc-arc");
        spec.flags.osx.objc.add((requiresArc == null || requiresArc == true) ? "-fobjc-arc" : "-fno-objc-arc");
        spec.flags.osx.objcpp.add((requiresArc == null || requiresArc == true) ? "-fobjc-arc" : "-fno-objc-arc");
        
        // compiler flags
        // https://guides.cocoapods.org/syntax/podspec.html#compiler_flags
        spec.flags.ios.addAll(getAsSplitString(specJson, "compiler_flags"));
        spec.flags.osx.addAll(getAsSplitString(specJson, "compiler_flags"));
        if (ios != null) spec.flags.ios.addAll(getAsSplitString(ios, "compiler_flags"));
        if (osx != null) spec.flags.osx.addAll(getAsSplitString(osx, "compiler_flags"));
        spec.flags.ios.c.add("--language=c");
        spec.flags.osx.c.add("--language=c");
        spec.flags.ios.cpp.add("--language=c++");
        spec.flags.osx.cpp.add("--language=c++");
        spec.flags.ios.objc.add("--language=objective-c");
        spec.flags.osx.objc.add("--language=objective-c");
        spec.flags.ios.objcpp.add("--language=objective-c++");
        spec.flags.osx.objcpp.add("--language=objective-c++");
        // CocoaPods sets CLANG_ENABLE_MODULES when creating an XCode project
        // https://xcodebuildsettings.com/#clang_enable_modules
        spec.flags.ios.c.add("-fmodules");
        spec.flags.osx.c.add("-fmodules");
        spec.flags.ios.objc.add("-fmodules");
        spec.flags.osx.objc.add("-fmodules");
        spec.flags.ios.objcpp.add("-fcxx-modules");
        spec.flags.osx.objcpp.add("-fcxx-modules");
        spec.flags.ios.c.add("-fmodule-name=" + spec.productModuleName);
        spec.flags.osx.c.add("-fmodule-name=" + spec.productModuleName);
        spec.flags.ios.objc.add("-fmodule-name=" + spec.productModuleName);
        spec.flags.osx.objc.add("-fmodule-name=" + spec.productModuleName);
        spec.flags.ios.objcpp.add("-fmodule-name=" + spec.productModuleName);
        spec.flags.osx.objcpp.add("-fmodule-name=" + spec.productModuleName);

        // resources
        // https://guides.cocoapods.org/syntax/podspec.html#resources
        spec.resources.addAll(getAsList(specJson, "resource"));
        spec.resources.addAll(getAsList(specJson, "resources"));
        if (ios != null) spec.resources.ios.addAll(getAsList(ios, "resource"));
        if (osx != null) spec.resources.osx.addAll(getAsList(osx, "resource"));
        if (ios != null) spec.resources.ios.addAll(getAsList(ios, "resources"));
        if (osx != null) spec.resources.osx.addAll(getAsList(osx, "resources"));

        // resource bundles
        // https://guides.cocoapods.org/syntax/podspec.html#resource_bundles
        spec.resourceBundles = getAsMapList(specJson, "resource_bundles");

        // frameworks
        // https://guides.cocoapods.org/syntax/podspec.html#frameworks
        spec.frameworks.addAll(getAsList(specJson, "frameworks"));
        if (ios != null) spec.frameworks.ios.addAll(getAsList(ios, "frameworks"));
        if (osx != null) spec.frameworks.osx.addAll(getAsList(osx, "frameworks"));

        // weak frameworks
        // https://guides.cocoapods.org/syntax/podspec.html#weak_frameworks
        spec.weakFrameworks.addAll(getAsList(specJson, "weak_frameworks"));
        if (ios != null) spec.weakFrameworks.ios.addAll(getAsList(ios, "weak_frameworks"));
        if (osx != null) spec.weakFrameworks.osx.addAll(getAsList(osx, "weak_frameworks"));

        // vendored frameworks
        // https://guides.cocoapods.org/syntax/podspec.html#vendored_frameworks
        List<String> vendored = getAsList(specJson, "vendored_frameworks");
        if (vendored != null) {
            spec.vendoredFrameworks.addAll(vendored);
        }
        if (ios != null) {
            List<String> ios_vendored = getAsList(ios, "vendored_frameworks");
            if (ios_vendored != null) {
                spec.vendoredFrameworks.addAll(ios_vendored);
            }
        }
        if (osx != null) {
            List<String> osx_vendored = getAsList(osx, "vendored_frameworks");
            if (osx_vendored != null) {
                spec.vendoredFrameworks.addAll(osx_vendored);
            }
        }

        // libraries
        // https://guides.cocoapods.org/syntax/podspec.html#libraries
        spec.libraries.addAll(getAsList(specJson, "libraries"));
        if (ios != null) spec.libraries.ios.addAll(getAsList(ios, "libraries"));
        if (spec.libraries.ios.contains("c++")) {
            spec.flags.ios.cpp.add("-std=c++11");
        }
        if (osx != null) spec.libraries.osx.addAll(getAsList(osx, "libraries"));
        if (spec.libraries.osx.contains("c++")) {
            spec.flags.osx.cpp.add("-std=c++11");
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
                else {
                    spec.containsFramework = true;
                }
            }
        }
        spec.installed = spec.containsFramework || !spec.sourceFiles.isEmpty() || !spec.swiftSourceFiles.isEmpty();

        // add swift libs to the runtime search path
        if (!spec.swiftSourceFiles.isEmpty()) {
            spec.linkflags.add("-Wl,-rpath,/usr/lib/swift");
        }

        // add ObjC link flag if pod contains Objective-C code
        for (File sourceFile : spec.sourceFiles) {
            String filename = sourceFile.getName();
            if (filename.endsWith(".m") || filename.endsWith(".mm")) {
                spec.linkflags.add("-ObjC");
                break;
            }
        }

        // public header files
        // https://guides.cocoapods.org/syntax/podspec.html#public_header_files
        spec.publicHeaders.addAll(getAsList(specJson, "public_header_files"));
        if (ios != null) spec.publicHeaders.ios.addAll(getAsList(ios, "public_header_files"));
        if (osx != null) spec.publicHeaders.osx.addAll(getAsList(osx, "public_header_files"));

        // generate umbrella header
        if (spec.installed) {
            spec.iosUmbrellaHeader = createIosUmbrellaHeader(spec, args.workingDir);
            spec.osxUmbrellaHeader = createOsxUmbrellaHeader(spec, args.workingDir);
        }

        // module map
        // https://guides.cocoapods.org/syntax/podspec.html#module_map
        spec.iosModuleMap = (String)specJson.get("module_map");
        spec.osxModuleMap = (String)specJson.get("module_map");
        if (ios != null) spec.iosModuleMap = (String)ios.get("module_map");
        if (osx != null) spec.osxModuleMap = (String)osx.get("module_map");

        if (spec.iosModuleMap != null && spec.iosModuleMap.toLowerCase().equals("false")) {
            // do not generate a module map
            spec.iosModuleMap = null;
        }
        else if (spec.iosModuleMap == null && spec.installed) {
            if (ExtenderUtil.listFilesMatchingRecursive(spec.dir, "module.modulemap").isEmpty()
                || !spec.swiftSourceFiles.isEmpty()) {
                spec.iosModuleMap = createIosModuleMap(spec, true);
                spec.iosSwiftModuleMap = createIosModuleMap(spec, false);
            }
        }
        if (spec.iosModuleMap != null) {
            spec.flags.ios.objc.add("-fmodule-map-file=" + spec.iosModuleMap);
            spec.flags.ios.objcpp.add("-fmodule-map-file=" + spec.iosModuleMap);
            spec.flags.ios.swift.add("-Xcc -fmodule-map-file=" + spec.iosSwiftModuleMap);
        }

        if (spec.osxModuleMap != null && spec.osxModuleMap.toLowerCase().equals("false")) {
            // do not generate a module map
            spec.osxModuleMap = null;
        }
        else if (spec.osxModuleMap == null && spec.installed) {
            if (ExtenderUtil.listFilesMatchingRecursive(spec.dir, "module.modulemap").isEmpty()
                || !spec.swiftSourceFiles.isEmpty()) {
                spec.osxModuleMap = createOsxModuleMap(spec, true);
                spec.osxSwiftModuleMap = createOsxModuleMap(spec, false);
            }
        }
        if (spec.osxModuleMap != null) {
            spec.flags.osx.objc.add("-fmodule-map-file=" + spec.osxModuleMap);
            spec.flags.osx.objcpp.add("-fmodule-map-file=" + spec.osxModuleMap);
            spec.flags.osx.swift.add("-Xcc -fmodule-map-file=" + spec.osxSwiftModuleMap);
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

    static void parseMultiPlatformConfig(PodSpec spec, JSONObject config) {
        if (config != null) {
            parseConfig(config, spec.flags.ios, spec.linkflags.ios, spec.defines.ios);
            parseConfig(config, spec.flags.osx, spec.linkflags.osx, spec.defines.osx);
            JSONObject iosConfig = (JSONObject)config.get("ios");
            JSONObject osxConfig = (JSONObject)config.get("ios");
            if (iosConfig != null) parseConfig(iosConfig, spec.flags.ios, spec.linkflags.ios, spec.defines.ios);
            if (osxConfig != null) parseConfig(osxConfig, spec.flags.osx, spec.linkflags.osx, spec.defines.osx);
        }
    }


    static void parseConfig(JSONObject config, LanguageSet flags, Set<String> linkflags, Set<String> defines) {
        // https://pewpewthespells.com/blog/buildsettings.html
        // defines
        List<String> defs = getStringListValues(config, "GCC_PREPROCESSOR_DEFINITIONS");
        if (defs != null) {
            defines.addAll(unescapeStrings(defs));
        }
        // linker flags
        // https://xcodebuildsettings.com/#other_ldflags
        List<String> ldFlags = getStringListValues(config, "OTHER_LDFLAGS");
        if (ldFlags != null) {
            linkflags.addAll(ldFlags);
        }
        // compiler flags for c and objc files
        // https://xcodebuildsettings.com/#other_cflags
        List<String> cFlags = getStringListValues(config, "OTHER_CFLAGS");
        if (cFlags != null) {
            flags.c.addAll(cFlags);
            flags.objc.addAll(cFlags);
        }
        // compiler flags
        if (hasString(config, "CLANG_CXX_LANGUAGE_STANDARD")) {
            String cppStandard = getAsString(config, "CLANG_CXX_LANGUAGE_STANDARD", "compiler-default");
            String compilerFlag = "";
            switch (cppStandard) {
                case "c++98":   compilerFlag = "-std=c++98"; break;
                case "c++0x":   compilerFlag = "-std=c++11"; break;
                case "gnu++0x": compilerFlag = "-std=gnu++11"; break;
                case "c++14":   compilerFlag = "-std=c++1y"; break;
                case "gnu++14": compilerFlag = "-std=gnu++1y"; break;
                case "gnu++98": 
                case "compiler-default": 
                default:  compilerFlag = "-std=gnu++98"; break;
            }
            flags.cpp.add(compilerFlag);
            flags.objcpp.add(compilerFlag);
        }
        if (hasString(config, "GCC_C_LANGUAGE_STANDARD")) {
            String cStandard = getAsString(config, "GCC_C_LANGUAGE_STANDARD", "compiler-default");
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
            flags.c.add(compilerFlag);
        }
        if (hasString(config, "CLANG_CXX_LIBRARY")) {
            String stdLib = getAsString(config, "CLANG_CXX_LIBRARY", "compiler-default");
            String stdLibFlag = "";
            switch (stdLib) {
                case "libc++": stdLibFlag = "-stdlib=libc++"; break;
                case "libstdc++":
                case "compiler-default":
                default: stdLibFlag = "-stdlib=libstdlibc++"; break;
            }
            flags.cpp.add(stdLibFlag);
            flags.objcpp.add(stdLibFlag);
        }
        if (compareString(config, "GCC_ENABLE_CPP_EXCEPTIONS", "YES")) {
            flags.cpp.add("-fcxx-exceptions");
        }
        if (compareString(config, "GCC_ENABLE_CPP_EXCEPTIONS", "NO")) {
            flags.cpp.add("-fno-cxx-exceptions");
        }
        if (compareString(config, "GCC_ENABLE_EXCEPTIONS", "YES")) {
            flags.add("-fexceptions");
        }
        if (compareString(config, "GCC_ENABLE_EXCEPTIONS", "NO")) {
            flags.add("-fno-exceptions");
        }
        if (compareString(config, "GCC_ENABLE_OBJC_EXCEPTIONS", "YES")) {
            flags.objc.add("-fobjc-exceptions");
            flags.objcpp.add("-fobjc-exceptions");
        }
        if (compareString(config, "GCC_ENABLE_OBJC_EXCEPTIONS", "NO")) {
            flags.objc.add("-fno-objc-exceptions");
            flags.objcpp.add("-fno-objc-exceptions");
        }
        if (compareString(config, "GCC_ENABLE_CPP_RTTI", "YES")) {
            flags.cpp.add("-frtti");
        }
        if (compareString(config, "GCC_ENABLE_CPP_RTTI", "NO")) {
            flags.cpp.add("-fno-rtti");
        }
        if (compareString(config, "GCC_ENABLE_OBJC_GC", "supported")) {
            flags.objc.add("-fobjc-gc");
            flags.objcpp.add("-fobjc-gc");
        }
        if (compareString(config, "GCC_ENABLE_OBJC_GC", "required")) {
            flags.objc.add("-fobjc-gc-only");
            flags.objcpp.add("-fobjc-gc-only");
        }
        if (compareString(config, "GCC_ENABLE_ASM_KEYWORD", "YES")) {
            flags.add("-fasm");
        }
        if (compareString(config, "GCC_ENABLE_ASM_KEYWORD", "NO")) {
            flags.add("-fno-asm");
        }
        if (compareString(config, "APPLICATION_EXTENSION_API_ONLY", "YES")) {
            flags.add("-fapplication-extension");
            flags.swift.add("-application-extension");
        }
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

    // check if a string value on a JSON object exists
    // will return false if the value doesn't exist or is an empty string
    static boolean hasString(JSONObject o, String key) {
        String value = (String)o.get(key);
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        return true;
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

    // check if the value for a specific key on a json object matches an expected value
    static boolean compareString(JSONObject o, String key, String expected) {
        String value = (String)o.get(key);
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
            else if (!isHeaderFile(filename)) {
                pod.sourceFiles.add(podSrcFile);
            }
        }
    }


    static boolean isHeaderFile(String filename) {
        return filename.endsWith(".h") || filename.endsWith(".def");
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
            if (isHeaderFile(filename)) {
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

    static String createIosModuleMap(PodSpec pod, boolean includeSwiftDefinition) throws ExtenderException {
        File moduleMapFile = new File(pod.generatedDir, String.format("%smodule.modulemap", includeSwiftDefinition ? "swift." : ""));
        createModuleMap(pod, pod.iosUmbrellaHeader, moduleMapFile, includeSwiftDefinition);
        return moduleMapFile.getAbsolutePath();
    }

    static String createOsxModuleMap(PodSpec pod, boolean includeSwiftDefinition) throws ExtenderException {
        File moduleMapFile = new File(pod.generatedDir, String.format("%smodule.modulemap", includeSwiftDefinition ? "swift." : ""));
        createModuleMap(pod, pod.osxUmbrellaHeader, moduleMapFile, includeSwiftDefinition);
        return moduleMapFile.getAbsolutePath();
    }

    // https://clang.llvm.org/docs/Modules.html#module-declaration
    static void createModuleMap(PodSpec pod, String umbrellaHeader, File moduleMapFile, boolean includeSwiftDefinition) throws ExtenderException {
        List<String> headers = new ArrayList<>();

        // swift source file header
        // generated by the swift compiler if -emit-objc-header flag is set
        if (!pod.swiftSourceFiles.isEmpty()) {
            File swiftModuleHeader = new File(pod.generatedDir, pod.productModuleName + "-Swift.h");
            pod.swiftModuleHeader = swiftModuleHeader;
            headers.add(swiftModuleHeader.getAbsolutePath());
        }

        if (pod.moduleMapGenerator != null) {
            // generate final modulemap
            HashMap<String, Object> envContext = new HashMap<>();
            envContext.put("SWIFT_DEFINITION", includeSwiftDefinition);
            envContext.put("FRAMEWORKOPT", pod.containsFramework ? "framework" : "");
            envContext.put("MODULE_ID", pod.productModuleName);
            envContext.put("HEADERS", headers);
            envContext.put("UMBRELLA_HEADER", umbrellaHeader);
            envContext.put("SUBMODULE", pod.containsFramework ? "module * { export * }" : "");

            String moduleMap = pod.moduleMapGenerator.apply(envContext);
            try {
                Files.writeString(moduleMapFile.toPath(), moduleMap);
            }
            catch (IOException e) {
                throw new ExtenderException(e, "Unable to create modulemap for " + pod.productModuleName);
            }
        }
    }

    static String createIosUmbrellaHeader(PodSpec pod, File jobDir) throws ExtenderException {
        File umbrellaHeaderFile = new File(pod.generatedDir, pod.productModuleName + "-umbrella.h");
        createUmbrellaHeader(pod, pod.publicHeaders.ios, umbrellaHeaderFile, jobDir);
        return umbrellaHeaderFile.getAbsolutePath();
    }

    static String createOsxUmbrellaHeader(PodSpec pod, File jobDir) throws ExtenderException {
        File umbrellaHeaderFile = new File(pod.generatedDir, pod.productModuleName + "-umbrella.h");
        createUmbrellaHeader(pod, pod.publicHeaders.osx, umbrellaHeaderFile, jobDir);
        return umbrellaHeaderFile.getAbsolutePath();
    }

    static void createUmbrellaHeader(PodSpec pod, Set<String> headerPatterns, File umbrellaHeaderFile, File jobDir) throws ExtenderException {
        List<String> headers = new ArrayList<>();
        for (String headerPattern : headerPatterns) {
            List<File> headerFiles = PodUtils.listFilesGlob(pod.dir, headerPattern);
            for (File headerFile : headerFiles) {
                headers.add(headerFile.getAbsolutePath());
            }
        }

        Map<String, Object> envContext = new HashMap<>();
        envContext.put("MODULE_ID", pod.productModuleName);
        envContext.put("HEADERS", headers);

        if (pod.umbrellaHeaderGenerator != null) {
            String umbrellaHeader = pod.umbrellaHeaderGenerator.apply(envContext);
            try {
                Files.writeString(umbrellaHeaderFile.toPath(), umbrellaHeader);
            }
            catch (IOException e) {
                throw new ExtenderException(e, "Unable to create umbrella header for " + pod.productModuleName);
            }
        }
    }
}
