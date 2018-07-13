package com.defold.extender;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class ExtenderUtil
{

    // Excludes items from input list that matches an item in the expressions list
    static private List<String> excludeItems(List<String> input, List<String> expressions) {
        List<String> items = new ArrayList<>();

        List<Pattern> patterns = new ArrayList<>();
        for (String expression : expressions) {
            patterns.add(Pattern.compile(expression));
        }
        for (String item : input) {
            boolean excluded = false;
            if (expressions.contains(item) ) {
                excluded = true;
            }
            else {
                for (Pattern pattern : patterns) {
                    Matcher m = pattern.matcher(item);
                    if (m.matches()) {
                        excluded = true;
                        break;
                    }
                }
            }
            if (!excluded) {
                items.add(item);
            }
        }
        return items;
    }

    // Keeps the matching items from input list that matches an item in the expressions list
    static private List<String> matchItems(List<String> input, List<String> expressions) {
        List<String> items = new ArrayList<>();

        List<Pattern> patterns = new ArrayList<>();
        for (String expression : expressions) {
            patterns.add(Pattern.compile(expression));
        }
        for (String item : input) {
            boolean included = false;
            if (expressions.contains(item) ) {
                included = true;
            }
            else {
                for (Pattern pattern : patterns) {
                    Matcher m = pattern.matcher(item);
                    if (m.matches()) {
                        included = true;
                        break;
                    }
                }
            }
            if (included) {
                items.add(item);
            }
        }
        return items;
    }

    static public List<String> mergeLists(List<String> l1, List<String> l2) {
        List<String> items = new ArrayList<>();
        if (l1 != null) {
            items.addAll(l1);
        }
        if (l2 != null) {
            items.addAll(l2);
        }
        return items;
    }

    static List<String> pruneItems(List<String> input, List<String> includePatterns, List<String> excludePatterns) {
        List<String> includeItems = matchItems(input, includePatterns);
        List<String> items = excludeItems(input, excludePatterns);
        for( String item : includeItems) {
            if (!items.contains(item)) {
                items.add(item);
            }
        }
        return items;
    }

    static String getRelativePath(File base, File path) {
        return base.toURI().relativize(path.toURI()).getPath();
    }

    static void debugPrint(Map<String, Object> map, int indent) {

        for (String key : map.keySet()) {
            Object v = map.get(key);
            if (v instanceof Map) {
                debugPrint((Map<String, Object>)v, indent+1);
            } else {
                for (int i = 0; i < indent; ++i) {
                    System.out.print("  ");
                }
                System.out.println(String.format("%s:\t%s", key, v.toString() ) );
            }
        }
    }

    static File[] listFilesMatching(File dir, String regex) {
        if(!dir.isDirectory()) {
            throw new IllegalArgumentException(dir+" is not a directory.");
        }
        final Pattern p = Pattern.compile(regex);
        return dir.listFiles(new FileFilter(){
            @Override
            public boolean accept(File file) {
                return p.matcher(file.getName()).matches();
            }
        });
    }

    /** Merges the different levels in the app manifest into one context
     * @param manifest  The app manifest
     * @param platform  The platform
     * @return The resource, or null if not found
     */
    public static Map<String, Object> getAppManifestContext(AppManifestConfiguration manifest, String platform) throws ExtenderException {
        Map<String, Object> appManifestContext = new HashMap<>();

        if( manifest == null )
            return appManifestContext;

        if (manifest.platforms.containsKey("common")) {
            appManifestContext = Extender.mergeContexts(appManifestContext, manifest.platforms.get("common").context);
        }
        if (manifest.platforms.containsKey(platform)) {
            appManifestContext = Extender.mergeContexts(appManifestContext, manifest.platforms.get(platform).context);
        }

        return appManifestContext;
    }

    static List<String> getAppManifestItems(AppManifestConfiguration manifest, String platform, String name) throws ExtenderException {
        List<String> items = new ArrayList<>();

        if( manifest == null || manifest.platforms == null )
            return items;

        if (manifest.platforms.containsKey("common")) {
            Object v = manifest.platforms.get("common").context.get(name);
            if( v != null ) {
                if (!Extender.isListOfStrings((List<Object>) v)) {
                    throw new ExtenderException(String.format("The context variables only support lists of strings. Got %s (type %s)", v.toString(), v.getClass().getCanonicalName()));
                }
                items.addAll((List<String>) v);
            }
        }

        if (manifest.platforms.containsKey(platform)) {
            Object v = manifest.platforms.get(platform).context.get(name);
            if( v != null ) {
                if (!Extender.isListOfStrings((List<Object>) v)) {
                    throw new ExtenderException(String.format("The context variables only support lists of strings. Got %s (type %s)", v.toString(), v.getClass().getCanonicalName()));
                }
                items.addAll((List<String>) v);
            }
        }
        return items;
    }

    static Object getAppManifestObject(AppManifestConfiguration manifest, String platform, String name) throws ExtenderException {
        if( manifest == null || manifest.platforms == null )
            return null;

        if (manifest.platforms.containsKey("common")) {
            Object v = manifest.platforms.get("common").context.get(name);
            if( v != null ) {
                return v;
            }
        }

        if (manifest.platforms.containsKey(platform)) {
            Object v = manifest.platforms.get(platform).context.get(name);
            if( v != null ) {
                return v;
            }
        }
        return null;
    }

    static Boolean getAppManifestBoolean(AppManifestConfiguration manifest, String platform, String name, Boolean default_value) throws ExtenderException {
        Boolean b = (Boolean)getAppManifestObject(manifest, platform, name);
        if (b instanceof Boolean) {
            return b;
        }
        return default_value;
    }
}