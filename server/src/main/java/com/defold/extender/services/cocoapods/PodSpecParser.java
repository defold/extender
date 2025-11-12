package com.defold.extender.services.cocoapods;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import com.defold.extender.ExtenderException;

public final class PodSpecParser {
    private static final Pattern BRACE_PATTERN = Pattern.compile("\\{([^{}]*)\\}");

    // https://guides.cocoapods.org/syntax/podspec.html
    public static PodSpec createPodSpec(JSONObject specJson, PodUtils.Platform selectedPlatform, PodSpec parentSpec) throws ExtenderException, IOException {
        PodSpec spec = new PodSpec();
        spec.name = (String)specJson.get("name");
        spec.moduleName = getModuleName(specJson, parentSpec);
        spec.version = (parentSpec == null) ? (String)specJson.get("version") : parentSpec.version;
        spec.parentSpec = parentSpec;

        // platform versions
        JSONObject platforms = (JSONObject)specJson.get("platforms");
        if (platforms != null) {
            if (PodUtils.isIOS(selectedPlatform)) {
                spec.platformVersion = (String)platforms.getOrDefault("ios", "");
            } else if (PodUtils.isMacOS(selectedPlatform)) {
                spec.platformVersion = (String)platforms.getOrDefault("osx", "");
            }
        }

        // for multi platform settings
        JSONObject platformSettings = (JSONObject)specJson.get(PodUtils.isIOS(selectedPlatform) ? "ios" : "osx");

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

        // resources
        // https://guides.cocoapods.org/syntax/podspec.html#resources
        spec.resources.addAll(getAsList(specJson, "resource"));
        if (platformSettings != null) spec.resources.addAll(getAsList(platformSettings, "resource"));

        spec.resources.addAll(getAsList(specJson, "resources"));
        if (platformSettings != null) spec.resources.addAll(getAsList(platformSettings, "resources"));

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
                PodSpec subSpec = createPodSpec(o, selectedPlatform, spec);
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

        // collect public headers for case when need to build framework
        spec.publicHeadersPatterns.addAll(getAsList(specJson, "public_header_files"));

        // find source and header files
        // https://guides.cocoapods.org/syntax/podspec.html#source_files
        spec.sourceFilesPatterns.addAll(getAsList(specJson, "source_files"));

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

        return parent.moduleName;
    }
    
    static String toC99extIdentifier(String s) {
        return s.replaceAll("^([0-9])", "_$1")  // 123FooBar -> _123FooBar
            .replaceAll("[\\+].*", "")          // NSData+zlib -> NSData
            .replaceAll("[^a-zA-Z0-9_]", "_")   // Foo-Bar -> Foo_Bar
            .replaceAll("_+", "_");             // Foo__Bar -> Foo_Bar
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

    // get a string value from a JSON object and split it into a list using space character as delimiter
    // will return an empty list if the value does not exist
    static List<String> getAsSplitString(JSONObject o, String key) {
        String value = (String)o.get(key);
        if (value == null || value.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(value.split(" ")));
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
}
