package com.defold.extender;

import java.io.File;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.defold.extender.process.CommandLineTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ExtensionManifestValidator {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExtensionManifestValidator.class);

    private final List<Pattern> allowedLibs = new ArrayList<>();
    private final List<Pattern> allowedFlags = new ArrayList<>();
    private final List<Pattern> allowedDefines = new ArrayList<>();
    private final List<Pattern> allowedSymbols = new ArrayList<>();

    private static final Pattern VALID_INCLUDE_PATH = Pattern.compile("^[A-Za-z0-9._+\\-/]+$");
    private static final Pattern VALID_SYMBOL_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern VALID_API_LEVEL = Pattern.compile("^[0-9]+$");

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

    // Validates the top-level app.manifest `context` map (the one read by
    // ExtenderBuildState). Whitespace is intentionally rejected: the rendered
    // command is split on spaces in ProcessExecutor.execute(String), so any
    // space here would inject extra argv elements (compiler-flag injection).
    void validateAppManifestContext(Map<String, Object> appContext) throws ExtenderException {
        if (appContext == null) {
            return;
        }
        Object debugSourcePath = appContext.get(ExtenderBuildState.APPMANIFEST_DEBUG_SOURCE_PATH);
        if (debugSourcePath != null) {
            if (!(debugSourcePath instanceof String)) {
                throw new ExtenderException(String.format(
                        "Error in app.manifest: '%s' must be a string.",
                        ExtenderBuildState.APPMANIFEST_DEBUG_SOURCE_PATH));
            }
            String s = (String) debugSourcePath;
            if (!s.isEmpty() && !VALID_INCLUDE_PATH.matcher(s).matches()) {
                throw new ExtenderException(String.format(
                        "Error in app.manifest: invalid '%s' value '%s'. Allowed characters: letters, digits and '._+-/'.",
                        ExtenderBuildState.APPMANIFEST_DEBUG_SOURCE_PATH, s));
            }
        }

        // Reaches the command line as the argument of d8 --min-api, so it must be a bare number.
        Object minAndroidSdkVersion = appContext.get(ExtenderBuildState.APPMANIFEST_MIN_ANDROID_SDK_VERSION_KEYWORD);
        if (minAndroidSdkVersion != null && !(minAndroidSdkVersion instanceof Integer)) {
            if (!(minAndroidSdkVersion instanceof String) || !VALID_API_LEVEL.matcher((String) minAndroidSdkVersion).matches()) {
                throw new ExtenderException(String.format(
                        "Error in app.manifest: '%s' must be an integer, got '%s'.",
                        ExtenderBuildState.APPMANIFEST_MIN_ANDROID_SDK_VERSION_KEYWORD, minAndroidSdkVersion));
            }
        }
    }

    // Values are rendered unquoted into command templates and the result is tokenized by
    // CommandLineTokenizer, so a space would inject extra argv elements, a backslash would escape
    // the separator the template emits and merge the following token into this one, and '@'
    // names a response file for clang, the linker (-Wl,@file) and emscripten (-s KEY=@file).
    // Quotes are checked on the tokenized form: a leading quote is stripped by the tokenizer,
    // while quotes after an assignment are kept literally, which the SDK relies on for values
    // such as EXPORTED_RUNTIME_METHODS=["ccall"]. The macOS install-name prefixes are the only
    // '@' forms allowed. Mustache syntax is rejected because values are expanded after this
    // check and the expansion could reintroduce whitespace.
    private static final Pattern RESPONSE_FILE = Pattern.compile("@(?!(loader_path|executable_path|rpath)\\b)");

    private static boolean namesResponseFile(String s) {
        try {
            return CommandLineTokenizer.parse(s).stream().anyMatch(t -> RESPONSE_FILE.matcher(t).find());
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static boolean isArgvUnsafe(String s) {
        return s.contains("{{") || s.chars().anyMatch(c -> Character.isWhitespace(c) || c == '\\') || namesResponseFile(s);
    }

    private static void validateArgvSafe(String extensionName, String key, Object value) throws ExtenderException {
        if (value == null) {
            return;
        }
        List<?> values = value instanceof List ? (List<?>) value : List.of(value);
        for (Object o : values) {
            if (o instanceof String s && isArgvUnsafe(s)) {
                throw new ExtenderException(String.format(
                        "Error in '%s': invalid '%s' value '%s'. Values must not contain whitespace, backslashes, template syntax or unbalanced quotes, or name a response file.",
                        extensionName, key, s));
            }
        }
    }

    private void validateIncludePaths(String extensionName, File extensionFolder, List<String> includes) throws ExtenderException {
        for (String include : includes) {
            if (include == null || include.isEmpty() || !VALID_INCLUDE_PATH.matcher(include).matches()) {
                throw new ExtenderException(String.format(
                        "Error in '%s': Invalid include path '%s'. Include paths may only contain letters, digits and '._+-/'.",
                        extensionName, include));
            }
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
                    LOGGER.warn("The include path '{}' does not exist:", f);
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
                    if (v instanceof List) {
                        for (String sym : (List<String>) v) {
                            if (sym == null || !VALID_SYMBOL_IDENTIFIER.matcher(sym).matches()) {
                                throw new ExtenderException(String.format(
                                        "Error in '%s': Invalid symbol '%s'. Symbols must be valid C identifiers.",
                                        extensionName, sym));
                            }
                        }
                    }
                    continue;

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
                case "stackSize":
                case "initialMemory":
                case "minFirefoxVersion":
                case "minSafariVersion":
                case "minChromeVersion":
                case "emscriptenLinkFlags":
                case "externalJsPorts":
                case "use-clang": // deprecated
                    validateArgvSafe(extensionName, k, v);
                    continue; // no need to whitelist

                default:
                    // If the user has added a non supported name
                    throw new ExtenderException(String.format("Error in '%s': Manifest context variable unsupported: %s", extensionName, k));
            }

            if (v instanceof List) {
                List<String> strings = (List<String>) v;
                String s = ExtensionManifestValidator.whitelistCheck(patterns, strings);
                if (s == null) {
                    s = strings.stream().filter(ExtensionManifestValidator::namesResponseFile).findFirst().orElse(null);
                }
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
