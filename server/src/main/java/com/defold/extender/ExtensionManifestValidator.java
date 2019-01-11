package com.defold.extender;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class ExtensionManifestValidator {

    private final List<Pattern> allowedLibs = new ArrayList<>();
    private final List<Pattern> allowedFlags = new ArrayList<>();
    private final List<Pattern> allowedDefines = new ArrayList<>();
    private final List<Pattern> allowedSymbols = new ArrayList<>();

    ExtensionManifestValidator(WhitelistConfig whitelistConfig, List<String> allowedFlags, List<String> allowedLibs, List<String> allowedSymbols) {
        this.allowedDefines.add(WhitelistConfig.compile(whitelistConfig.defineRe));

        TemplateExecutor templateExecutor = new TemplateExecutor();
        ExtensionManifestValidator.expandPatterns(templateExecutor, whitelistConfig.context, allowedFlags, this.allowedFlags);
        ExtensionManifestValidator.expandPatterns(templateExecutor, whitelistConfig.context, allowedLibs, this.allowedLibs);
        ExtensionManifestValidator.expandPatterns(templateExecutor, whitelistConfig.context, allowedSymbols, this.allowedSymbols);
    }

    private static boolean isListOfStrings(List<Object> list) {
        return list != null && list.stream().allMatch(o -> o instanceof String);
    }

    void validate(String extensionName, Map<String, Object> context) throws ExtenderException {
        Set<String> keys = context.keySet();
        for (String k : keys) {
            Object v = context.get(k);

            if (v instanceof List && !ExtensionManifestValidator.isListOfStrings((List<Object>) v)) {
                throw new ExtenderException(String.format("Error in '%s': The context variables only support strings or lists of strings. Got %s: %s (type %s)", extensionName, k, v.toString(), v.getClass().getCanonicalName()));
            }

            List<Pattern> patterns;
            String type;
            switch (k) {
                case "defines":
                    patterns = this.allowedDefines;
                    type = "define";
                    break;

                case "libs":
                case "dynamicLibs":
                case "engineLibs":
                case "frameworks":
                case "weakFrameworks":
                    patterns = allowedLibs;
                    type = "lib";
                    break;

                case "flags":
                case "linkFlags":
                    patterns = allowedFlags;
                    type = "flag";
                    break;

                case "symbols":
                    patterns = this.allowedSymbols;
                    type = "symbol";
                    break;

                case "excludeLibs":
                case "excludeJars":
                case "excludeJsLibs":
                case "excludeSymbols":
                case "use-clang": // deprecated
                case "legacy-use-cl": // undocumented, only used if some project needs to use cl.exe in the transition period
                    continue; // no need to whitelist

                default:
                    // If the user has added a non supported name
                    throw new ExtenderException(String.format("Error in '%s': Manifest context variable unsupported: %s", extensionName, k));
            }

            if (v instanceof List) {
                List<String> strings = (List<String>) v;
                String s = ExtensionManifestValidator.whitelistCheck(patterns, strings);
                if (s != null) {
                    throw new ExtenderException(String.format("Error in '%s': Invalid %s - '%s': '%s'", extensionName, type, k, s));
                }
            }
        }
    }

    public static void expandPatterns(TemplateExecutor executor, Map<String, Object> context, List<String> vars, List<Pattern> out) {
        for (String s : vars) {
            out.add(WhitelistConfig.compile(executor.execute(s, context)));
        }
    }

    public static String whitelistCheck(List<Pattern> patterns, List<String> l) {
        for (String s : l) {
            boolean matched = false;
            for (Pattern p : patterns) {
                Matcher m = p.matcher(s);
                if (m.matches()) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return s;
            }
        }
        return null;
    }
}
