package com.defold.extender;

import java.io.File;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ExtensionManifestValidator {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExtensionManifestValidator.class);

    private final List<Pattern> allowedLibs = new ArrayList<>();
    private final List<Pattern> allowedFlags = new ArrayList<>();
    private final List<Pattern> allowedDefines = new ArrayList<>();
    private final List<Pattern> allowedSymbols = new ArrayList<>();

    ExtensionManifestValidator(WhitelistConfig whitelistConfig, List<String> allowedFlags, List<String> allowedSymbols) {
        this.allowedDefines.add(WhitelistConfig.compile(whitelistConfig.defineRe));
        this.allowedLibs.add(WhitelistConfig.compile(whitelistConfig.libraryRe));

        TemplateExecutor templateExecutor = new TemplateExecutor();
        ExtensionManifestValidator.expandPatterns(templateExecutor, whitelistConfig.context, allowedFlags, this.allowedFlags);
        ExtensionManifestValidator.expandPatterns(templateExecutor, whitelistConfig.context, allowedSymbols, this.allowedSymbols);
    }

    private static boolean isListOfStrings(List<Object> list) {
        return list != null && list.stream().allMatch(o -> o instanceof String);
    }

    private void validateIncludePaths(String extensionName, File extensionFolder, List<String> includes) throws ExtenderException {
        for (String include : includes) {
            String[] tokens = include.split("/");
            for (int i = 0; i < tokens.length; ++i)
            {
                String[] subtokens = Arrays.copyOfRange(tokens, 0, i);
                String s = String.join("/", subtokens);
                File f = new File(extensionFolder, s);

                if (!ExtenderUtil.isChild(extensionFolder, f))
                {
                    throw new ExtenderException(String.format("Error in '%s': The include '%s' path must be relative subdirectory to the extension folder '%s'", extensionName, include, extensionFolder));
                }

                if (!f.exists()) {
                    LOGGER.warn("The include path '%s' does not exist:", f);
                }
            }
        }
    }

    void validate(String extensionName, File extensionFolder, Map<String, Object> context) throws ExtenderException {
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
                case "cxxShLibs":
                    patterns = allowedLibs;
                    type = "lib";
                    break;

                case "flags":
                case "linkFlags":
                case "cxxShFlags":
                case "cxxLinkShFlags":
                case "javaFlags":
                    patterns = allowedFlags;
                    type = "flag";
                    break;

                case "includes":
                    if (!(v instanceof List)) {
                        throw new ExtenderException(String.format("Error in '%s': The 'includes' must be a list of strings. Got %s: %s (type %s)", extensionName, k, v.toString(), v.getClass().getCanonicalName()));
                    }

                    validateIncludePaths(extensionName, extensionFolder, (List<String>) v);
                    continue;

                case "symbols":
                case "excludeLibs":
                case "excludeJars":
                case "excludeJsLibs":
                case "excludeSymbols":
                case "excludeObjectFiles":
                case "includeObjectFiles":
                case "excludeDynamicLibs":
                case "excludeFrameworks":
                case "aaptExtraPackages":
                case "objectFiles":
                case "jetifier":
                case "use-clang": // deprecated
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
