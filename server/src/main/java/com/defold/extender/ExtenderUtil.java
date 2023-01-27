package com.defold.extender;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.FileSystems;

import java.lang.reflect.Field;

import org.apache.commons.io.comparator.NameFileComparator;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.RegexFileFilter;

public class ExtenderUtil
{

    // Excludes items from input list that matches an item in the expressions list
    static List<String> excludeItems(List<String> input, List<String> expressions) {
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

    static void debugPrintStringMap(Map<String, String> map, int indent) {
        for (String key : map.keySet()) {
            Object v = map.get(key);
            if (v instanceof Map) {
                debugPrintStringMap((Map<String, String>)v, indent+1);
            } else {
                for (int i = 0; i < indent; ++i) {
                    System.out.print("  ");
                }
                System.out.println(String.format("%s:\t%s", key, v.toString() ) );
            }
        }
    }

    static void debugPrint(String name, List<String> l) {
        if (l == null) {
            System.out.println(String.format("%s: <null>", name));
            return;
        }
        System.out.print(String.format("%s: [", name));
        for (String v : l) {
            System.out.print(String.format("%s, ", v));
        }
        System.out.println("]");
    }

    static void debugPrintFiles(String name, List<File> l) {
        if (l == null) {
            System.out.println(String.format("%s: <null>", name));
            return;
        }
        System.out.print(String.format("%s: [", name));
        for (File v : l) {
            System.out.print(String.format("%s, ", v));
        }
        System.out.println("]");
    }

    // Lists files in a directory
    public static File[] listFilesMatching(File dir, String regex) {
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

    // Finds all files recursively
    public static List<File> listFilesMatchingRecursive(File dir, String regex) {
        if(!dir.isDirectory()) {
            throw new IllegalArgumentException(dir+" is not a directory.");
        }
        return new ArrayList(FileUtils.listFiles(dir, new RegexFileFilter(regex), DirectoryFileFilter.DIRECTORY));
    }


    public static List<File> filterFiles(Collection<File> files, PathMatcher pm) {
        return files.stream().filter(f -> pm.matches(f.toPath())).collect(Collectors.toList());
    }

    public static List<File> filterFiles(Collection<File> files, String re) {
        return filterFiles(files, FileSystems.getDefault().getPathMatcher("regex:" + re));
    }

    public static List<File> filterFilesGlob(Collection<File> files, String glob) {
        return filterFiles(files, FileSystems.getDefault().getPathMatcher("glob:" + glob));
    }

    public static List<String> filterStrings(Collection<String> strings, String re) {
        Pattern p = Pattern.compile(re);
        return strings.stream().filter(s -> p.matcher(s).matches()).collect(Collectors.toList());
    }

    public static List<File> listFiles(File[] srcDirs, PathMatcher pm) {
        List<File> srcFiles = new ArrayList<>();
        for (File srcDir : srcDirs) {
            if (srcDir.exists() && srcDir.isDirectory()) {
                List<File> _srcFiles = new ArrayList<>(FileUtils.listFiles(srcDir, null, true));
                _srcFiles = ExtenderUtil.filterFiles(_srcFiles, pm);

                // sorting makes it easier to diff different builds
                Collections.sort(_srcFiles, NameFileComparator.NAME_INSENSITIVE_COMPARATOR);
                srcFiles.addAll(_srcFiles);
            }
        }
        return srcFiles;
    }

    public static List<File> listFiles(File[] srcDirs, String regEx) {
        return listFiles(srcDirs, FileSystems.getDefault().getPathMatcher("regex:" + regEx));
    }
    public static List<File> listFiles(File srcDir, String regEx) {
        File[] srcDirs = {srcDir};
        return listFiles(srcDirs, regEx);
    }
    public static List<File> listFiles(List<File> srcDirs, String regEx) {
        return listFiles(srcDirs.toArray(new File[0]), regEx);
    }

    public static List<File> listFilesGlob(File[] srcDirs, String glob) {
        return listFiles(srcDirs, FileSystems.getDefault().getPathMatcher("glob:" + glob));
    }
    public static List<File> listFilesGlob(File srcDir, String glob) {
        return listFiles(new File[] {srcDir}, FileSystems.getDefault().getPathMatcher("glob:" + glob));
    }



    /** Merges the different levels in the app manifest into one context
     * @param manifest  The app manifest
     * @param platform  The platform
     * @param optionalBaseVariantManifest The base manifest (optional)
     * @return The resource, or null if not found
     */
    public static Map<String, Object> getAppManifestContext(AppManifestConfiguration manifest, String platform, AppManifestConfiguration optionalBaseVariantManifest) throws ExtenderException {
        Map<String, Object> appManifestContext = new HashMap<>();

        if (optionalBaseVariantManifest != null && optionalBaseVariantManifest.platforms != null) {
            if (optionalBaseVariantManifest.platforms.containsKey("common")) {
                appManifestContext = ExtenderUtil.mergeContexts(appManifestContext, optionalBaseVariantManifest.platforms.get("common").context);
            }
            if (optionalBaseVariantManifest.platforms.containsKey(platform)) {
                appManifestContext = ExtenderUtil.mergeContexts(appManifestContext, optionalBaseVariantManifest.platforms.get(platform).context);
            }
        }

        if (manifest != null && manifest.platforms != null) {
            if (manifest.platforms.containsKey("common")) {
                appManifestContext = ExtenderUtil.mergeContexts(appManifestContext, manifest.platforms.get("common").context);
            }
            if (manifest.platforms.containsKey(platform)) {
                appManifestContext = ExtenderUtil.mergeContexts(appManifestContext, manifest.platforms.get(platform).context);
            }
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
                if (!ExtenderUtil.isListOfStrings((List<Object>) v)) {
                    throw new ExtenderException(String.format("The context variables only support lists of strings. Got %s (type %s)", v.toString(), v.getClass().getCanonicalName()));
                }
                items.addAll((List<String>) v);
            }
        }

        if (manifest.platforms.containsKey(platform)) {
            Object v = manifest.platforms.get(platform).context.get(name);
            if( v != null ) {
                if (!ExtenderUtil.isListOfStrings((List<Object>) v)) {
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


    static Object getAppManifestContextObject(AppManifestConfiguration manifest, String name) throws ExtenderException {
        if (manifest.context == null) {
            return null;
        }
        return manifest.context.get(name);
    }

    static Boolean getAppManifestBoolean(AppManifestConfiguration manifest, String platform, String name, Boolean default_value) throws ExtenderException {
        Boolean b = (Boolean)getAppManifestObject(manifest, platform, name);
        if (b instanceof Boolean) {
            return b;
        }
        return default_value;
    }

    static Boolean getAppManifestContextBoolean(AppManifestConfiguration manifest, String name, Boolean default_value) throws ExtenderException {
        Boolean b = (Boolean)getAppManifestContextObject(manifest, name);
        if (b instanceof Boolean) {
            return b;
        }
        return default_value;
    }

    static String getAppManifestContextString(AppManifestConfiguration manifest, String name, String default_value) throws ExtenderException {
        String s = (String)getAppManifestContextObject(manifest, name);
        if (s instanceof String) {
            return s;
        }
        return default_value;
    }

    static public boolean isListOfStrings(List<Object> list) {
        return list != null && list.stream().allMatch(o -> o instanceof String);
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

    private static final String MERGE_KEY_REPLACE = "_replace";
    static private boolean isMergeOp(String name)
    {
        if (name.endsWith(MERGE_KEY_REPLACE))
            return false;
        return true;
    }

    static private String stripMergeKey(String name)
    {
        if (name.endsWith(MERGE_KEY_REPLACE))
            return name.substring(0, name.length()-MERGE_KEY_REPLACE.length());
        return name;
    }

    // Copies the original context, and appends the extra context's elements, if the keys and types are valid
    static public Map<String, Object> mergeContexts(Map<String, Object> originalContext, Map<String, Object> extensionContext) throws ExtenderException {
        Map<String, Object> context = new HashMap<>(originalContext);

        // Clean the names of the previous context
        Set<String> originalKeys = new HashSet<>(context.keySet());
        for (String k : originalKeys) {
            String key = stripMergeKey(k);
            if (!key.equals(k))
            {
                Object v = context.get(k);
                context.remove(k);
                context.put(key, v);
            }
        }

        Set<String> keys = extensionContext.keySet();
        for (String k : keys) {

            boolean isMergeOp = isMergeOp(k);
            String key = stripMergeKey(k);

            Object v1 = context.getOrDefault(key, context.getOrDefault(k, null));
            Object v2 = extensionContext.get(k);

            if (v1 == null && v2 == null) {
                // Simply skip keys that hold no values at all
                context.remove(key);
                continue;
            }

            if (v1 != null && v2 != null && !v1.getClass().equals(v2.getClass())) {
                throw new ExtenderException(String.format("Wrong manifest context variable type for %s: Expected %s, got %s: %s", k, v1.getClass().toString(), v2.getClass().toString(), v2.toString()));
            }
            if (v2 != null && v2 instanceof List && !ExtenderUtil.isListOfStrings((List<Object>) v2)) {
                throw new ExtenderException(String.format("The context variables only support lists of strings. Got %s (type %s)", v2.toString(), v2.getClass().getCanonicalName()));
            }

            if (v1 != null && v2 != null && v1 instanceof List) {
                if (isMergeOp) {
                    v1 = ExtenderUtil.mergeLists((List<String>) v1, (List<String>) v2);
                } else {
                    v1 = v2;
                }
            }

            if (v1 != null) {
                context.put(key, v1);
            } else {
                context.put(key, v2);
            }
        }
        return context;
    }

    static public Map<String, Object> createEmptyContext(Map<String, Object> original) {
        Map<String, Object> out = new HashMap<>();
        Set<String> keys = original.keySet();
        for (String k : keys) {
            Object v = original.get(k);
            if (v instanceof String) {
                v = "";
            } else if (v instanceof List) {
                v = new ArrayList<String>();
            }
            out.put(k, v);
        }
        return out;
    }

    static List<String> getStringList(Map<String, Object> context, String key) throws ExtenderException
    {
        Object v = context.getOrDefault(key, new ArrayList<>());
        if (v instanceof List && isListOfStrings((List<Object>)v)) {
            return (List<String>)v;
        }
        throw new ExtenderException(String.format("The context variables only support strings or lists of strings. Key %s: %s (type %s)", key, v.toString(), v.getClass().getCanonicalName()));
    }

    // Does a regexp match on the filename for each file found in a directory
    static public List<String> collectFilesByName(File dir, String re) {
        List<String> result = new ArrayList<>();
        if (re == null) {
            return result;
        }
        Pattern p = Pattern.compile(re);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            for (File f : files) {
                Matcher m = p.matcher(f.getName());
                if (m.matches()) {
                    result.add(m.group(1));
                }
            }
        }
        Collections.sort(result);
        return result;
    }

    // Does a regexp match on the absolute path for each file found in a directory
    static public List<String> collectFilesByPath(File dir, String re) {
        List<String> result = new ArrayList<>();
        if (re == null) {
            return result;
        }
        Pattern p = Pattern.compile(re);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            for (File f : files) {
                Matcher m = p.matcher(f.getAbsolutePath());
                if (m.matches()) {
                    result.add(m.group(1));
                }
            }
        }
        Collections.sort(result);
        return result;
    }

    static public List<String> getPlatformAlternatives(String platform) {
        List<String> platforms = new ArrayList<>();
        platforms.add("common");
        String[] platformParts = platform.split("-");
        if (platformParts.length == 2) {
            platforms.add(platformParts[1]);
        }
        platforms.add(platform);
        return platforms;
    }

    public static boolean matchesAndroidAssetDirectoryName(String name) {
        // For the list of reserved names, see Table 1: https://developer.android.com/guide/topics/resources/providing-resources
        String[] assetDirs = new String[]{"values", "xml", "layout", "animator", "anim", "color", "drawable", "mipmap", "menu", "raw", "font"};
        for (String reserved : assetDirs) {
            if (name.startsWith(reserved)) {
                return true;
            }
        }
        return false;
    }

    public static boolean verifyAndroidAssetDirectory(File dir) {
        File[] files = dir.listFiles();
        for (File d : files) {
            if (!matchesAndroidAssetDirectoryName(d.getName())) {
                return false;
            }
        }
        return files.length > 0;
    }


    public static File getAndroidResourceFolder(File dir) {
        // In resource folders, we add packages in several ways:
        // 'project/extension/res/android/res/com.foo.name/res/<android folders>' (new)
        // 'project/extension/res/android/res/com.foo.name/<android folders>' (legacy)
        // 'project/extension/res/android/res/<android folders>' (legacy)
        if (dir.isDirectory() && verifyAndroidAssetDirectory(dir)) {
            return dir;
        }

        for (File f : dir.listFiles()) {
            if (!f.isDirectory()) {
                continue;
            }

            File resDir = getAndroidResourceFolder(f);
            if (resDir != null) {
                return resDir;
            }
        }
        return null;
    }

    public static boolean isChild(File parent, File child) {
        Path parentPath = parent.toPath().normalize().toAbsolutePath();
        Path childPath = child.toPath().normalize().toAbsolutePath();
        return childPath.startsWith(parentPath);
    }

    // Doesn't work for ".hidden"
    public static String getNameWithoutExtension(String name) {
       int dotIndex = name.lastIndexOf('.');
       return (dotIndex == -1) ? name : name.substring(0, dotIndex);
    }

    public static String removeFileExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return filename;
        }
        String extPattern = "(?<!^)[.][^.]*$";
        return filename.replaceAll(extPattern, "");
    }

    public static String switchExtension(String name, String newExt) {
        return removeFileExtension(name) + newExt;
    }


    public static boolean isAppleTarget(String platform) {
        return platform.equals("x86_64-osx") ||
               platform.equals("x86_64-ios") ||
               platform.equals("armv7-ios") ||
               platform.equals("arm64-ios");
    }

    public static boolean isWebTarget(String platform) {
        return platform.equals("wasm-web") ||
               platform.equals("js-web");
    }


    @SuppressWarnings("unchecked")
    public static <V> V get(Object object, String fieldName) {
        Class<?> clazz = object.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return (V) field.get(object);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return null;
    }

}
