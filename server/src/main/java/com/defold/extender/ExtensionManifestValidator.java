package com.defold.extender;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class ExtensionManifestValidator {

    private final WhitelistConfig whitelistConfig;
    private final List<Pattern> allowedLibs = new ArrayList<Pattern>();
    private final List<Pattern> allowedFlags = new ArrayList<Pattern>();
    private final List<Pattern> allowedDefines = new ArrayList<Pattern>();

    ExtensionManifestValidator(WhitelistConfig whitelistConfig, List<String> allowedFlags, List<String> allowedLibs) {
        this.whitelistConfig = whitelistConfig;

        this.allowedDefines.add( Pattern.compile(String.format("^(%s)$", whitelistConfig.defineRe ) ) );

        TemplateExecutor templateExecutor = new TemplateExecutor();
        ExtensionManifestValidator.expandPatterns(templateExecutor, this.whitelistConfig.context, allowedFlags, this.allowedFlags);
        ExtensionManifestValidator.expandPatterns(templateExecutor, this.whitelistConfig.context, allowedLibs, this.allowedLibs);
    }

    public WhitelistConfig getWhitelistConfig() {
        return this.whitelistConfig;
    }

    static boolean isListOfStrings(List<Object> list) {
        if (list instanceof List<?>) {
            return list.stream().allMatch(o -> o instanceof String);
        }
        return false;
    }

    void validate(String extensionName, Map<String, Object> context) throws ExtenderException {
        Set<String> keys = context.keySet();
        for (String k : keys) {
            Object v = context.get(k);

            if (!ExtensionManifestValidator.isListOfStrings((List<Object>)v)) {
                throw new ExtenderException(String.format("The context variables only support strings or lists of strings. Got %s: %s (type %s)  (%s)", k, v.toString(), v.getClass().getCanonicalName(), extensionName ));
            }

            List<String> strings = (List<String>)v;
            List<Pattern> patterns = null;
            String type = "";
            switch(k) {
                case "defines":
                    patterns = this.allowedDefines;
                    type = "define";
                    break;

                case "libs":
                case "frameworks":
                    patterns = allowedLibs;
                    type = "lib";
                    break;

                case "flags":
                case "linkFlags":
                    patterns = allowedFlags;
                    type = "flag";
                    break;

                default:
                    // If the user has added a non supported name
                    throw new ExtenderException(String.format("Manifest context variable unsupported in '%s': %s", extensionName, k));
            }

            String s = ExtensionManifestValidator.whitelistCheck(patterns, strings);
            if (s != null) {
                throw new ExtenderException(String.format("Invalid %s in extension '%s' - '%s': '%s'", type, extensionName, k, s));
            }
        }
    }

    static void expandPatterns(TemplateExecutor executor, Map<String, Object> context, List<String> vars, List<Pattern> out) {
        for (String s : vars) {
            out.add( Pattern.compile( String.format("^(%s)$", executor.execute(s, context)) ) );
        }
    }

    static String whitelistCheck(List<Pattern> patterns, List<String> l) {
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