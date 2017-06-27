package com.defold.extender;

import java.io.File;
import java.util.ArrayList;
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
}