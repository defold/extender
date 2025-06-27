package com.defold.extender.services.cocoapods;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class XCConfigParser {
    private static final Logger LOGGER = LoggerFactory.getLogger(XCConfigParser.class);
    private File buildDir;
    private File podsDir;
    private String platform;
    private String configuration; //Debug/Release
    private String arch;

    enum ParseMode {
        VAR_START,
        VAR_END,
        FLAVOUR_START,
        FLAVOUR_END,
        ASSIGMENT_OPERATOR,
        VALUE
    }

    public XCConfigParser(File buildDir, File podsDir, String platform, String configuration, String arch) {
        this.buildDir = buildDir;
        this.podsDir = podsDir;
        this.platform = platform;
        this.configuration = configuration;
        this.arch = arch;
    }

    Map<String, String> calculateBaseVariables(String podName) {
        Map<String, String> result = new HashMap<>();
        result.put("BUILD_DIR", this.buildDir.toString());
        result.put("EFFECTIVE_PLATFORM_NAME", this.platform);
        result.put("CONFIGURATION", this.configuration);
        result.put("SRCROOT", this.podsDir.toString());
        result.put("MODULEMAP_FILE", String.format("Headers/Public/%s/%s.modulemap", podName, podName));
        result.put("DEVELOPMENT_LANGUAGE", "en");
        result.put("PLATFORM_NAME", this.platform); // iphoneos/iphonesimulator/macosx
        result.put("TOOLCHAIN_DIR", System.getenv("XCTOOLCHAIN_PATH")); //path to .xctoolchain
        result.put("ARCHS", this.arch);
        return result;
    }

    void parseIncludes(String line) {

    }

    /*
     * @param value String Value which need to be processed
     * @param allValues Map<String, String> Map with values which can use for substitution.
     * Merged values from base values (like directory paths) and values obtained from xcconfig
     */
    String postProcessValue(String value, Map<String, String> allValues) {
        List<String> tmpList = new ArrayList<>(Arrays.asList(value.split(" ")));
        tmpList.remove("$(inherited)");

        // check for $(....) pattern
        Pattern p = Pattern.compile("\\$[\\(|{]([\\w]+)[\\)|}]");

        // substitute values if any placeholders are presented
        for (int idx = 0 ; idx < tmpList.size(); ++idx) {
            String element = tmpList.get(idx);
            Matcher matcher = p.matcher(element);
            while (matcher.find()) {
                String replaceKey = matcher.group(1);
                String replaceValue = allValues.containsKey(replaceKey) ? allValues.get(replaceKey) : null;
                if (replaceValue != null) {
                    element = element.replace(matcher.group(0), replaceValue);
                    element = element.replaceAll("\"", "");
                    // update matcher every time because during replace new values for substitution can be introduced.
                    // For example: ${PODS_ROOT}/Headers (where PODS_ROOT=${SRCROOT}) -> ${SRCROOT}/Headers
                    matcher = p.matcher(element);
                } else {
                    LOGGER.warn("Can't find value for substitution for key {}", replaceKey);
                }
            }
            tmpList.set(idx, element);
        }

        return String.join(" ", tmpList);
    }

    Pair<String, String> parseLine(String line) {
        line = line.trim();
        // remove all trailing ';'
        int lastSymbolsIdx = line.length() - 1;
        while (line.charAt(lastSymbolsIdx) == ';') {
            lastSymbolsIdx--;
        }
        line = line.substring(0, lastSymbolsIdx + 1);
        int commentStart = line.indexOf("//");
        if (commentStart != -1) {
            line = line.substring(0, commentStart);
            line = line.trim();
        }
        if (line.isBlank()) {
            return null;
        }
        if (line.startsWith("#")) {
            parseIncludes(line);
            return null;
        }
        char[] charsArray = line.toCharArray();
        ParseMode currentMode = ParseMode.VAR_START;
        StringBuilder varBuilder = new StringBuilder();
        StringBuilder valueBuilder = new StringBuilder();
        //! NOTE: all build flavours we skip now
        for (char c : charsArray) {
            switch (currentMode) {
                case ParseMode.VAR_START:
                    if (c == '[') {
                        currentMode = ParseMode.FLAVOUR_START;
                        continue;
                    }
                    if ((c >= '0' && c <= '9') 
                        || (c >= 'a' && c <= 'z')
                        || (c >= 'A' && c <= 'Z')
                        || c == '_') {
                            varBuilder.append(c);
                    } else if (c == '[') {
                        currentMode = ParseMode.FLAVOUR_START;
                    } else if (c == '=') {
                        currentMode = ParseMode.ASSIGMENT_OPERATOR;
                    } else {
                        currentMode = ParseMode.VAR_END;
                    }
                    break;
                case ParseMode.VAR_END:
                    if (c == '=') {
                        currentMode = ParseMode.ASSIGMENT_OPERATOR;
                    } else if (c == '[') {
                        currentMode = ParseMode.FLAVOUR_START;
                    }
                    break;
                case ParseMode.FLAVOUR_START:
                    if (c == ']') {
                        currentMode = ParseMode.FLAVOUR_END;
                    }
                    break;
                case ParseMode.FLAVOUR_END:
                    if (c == '=') {
                        currentMode = ParseMode.ASSIGMENT_OPERATOR;
                    } else if (c == '[') {
                        currentMode = ParseMode.FLAVOUR_START;
                    }
                    break;
                case ParseMode.ASSIGMENT_OPERATOR:
                    if (c != ' ' && c != '\t') {
                        currentMode = ParseMode.VALUE;
                        valueBuilder.append(c);
                    }
                    break;
                case ParseMode.VALUE:
                    valueBuilder.append(c);
                    break;
                default:
                    break;
            }
        }
        return Pair.of(varBuilder.toString(), valueBuilder.toString());
    }

    public Map<String, String> parse(String podName, File xcconfig) throws IOException {
        // https://pewpewthespells.com/blog/xcconfig_guide.html
        Map<String, String> allValues = calculateBaseVariables(podName);

        Map<String, String> result = new HashMap<>();
        List<String> lines = Files.readAllLines(xcconfig.toPath());
        for (String line : lines) {
            Pair<String, String> parseResult = parseLine(line);
            if (parseResult != null) {
                result.put(parseResult.getLeft(), parseResult.getValue());
            }
        }
        allValues.putAll(result);
        // post-process all values. It can be case when we have transitive reference
        for (Map.Entry<String, String> entry : allValues.entrySet()) {
            String value = postProcessValue(entry.getValue(), allValues);
            entry.setValue(value);
        }
        return allValues;
    }
}
