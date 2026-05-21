package com.defold.extender.services.cocoapods;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.defold.extender.ExtenderBuildState;
import com.defold.extender.process.CommandLineTokenizer;

public class XCConfigParser implements IConfigParser {
    private static final Logger LOGGER = LoggerFactory.getLogger(XCConfigParser.class);
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$[\\(|{]([\\w]+)[\\)|}]");
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

    public XCConfigParser(ExtenderBuildState buildState, CocoaPodsServiceBuildState cocoapodsBuildState) {
        this(buildState.getBuildDir(), cocoapodsBuildState.getPodsDir(), cocoapodsBuildState.getSelectedPlatform(), buildState.getBuildConfiguration(), buildState.getBuildArch());
    }

    XCConfigParser(File buildDir, File podsDir, PodUtils.Platform selectedPlatform, String buildConfiguration, String buildArch) {
        this.buildDir = buildDir;
        this.podsDir = podsDir;
        this.platform = selectedPlatform.toString().toLowerCase();

        this.configuration = buildConfiguration;
        this.arch = buildArch;
    }

    Map<String, String> calculateBaseVariables(String moduleName, String podName) {
        Map<String, String> result = new HashMap<>();
        result.put("BUILD_DIR", this.buildDir.toString());
        result.put("EFFECTIVE_PLATFORM_NAME", this.platform);
        result.put("CONFIGURATION", this.configuration);
        result.put("SRCROOT", this.podsDir.toString());
        result.put("MODULEMAP_FILE", String.format("Headers/Public/%s/%s.modulemap", moduleName, podName));
        result.put("DEVELOPMENT_LANGUAGE", "en");
        result.put("PLATFORM_NAME", this.platform); // iphoneos/iphonesimulator/macosx
        result.put("TOOLCHAIN_DIR", System.getenv("XCTOOLCHAIN_PATH")); //path to .xctoolchain
        result.put("ARCHS", this.arch);
        // result.put("SDKROOT", );
        return result;
    }

    void parseIncludes(String line) {
        throw new UnsupportedOperationException("Include statement parsing not supported");
    }

    /*
     * @param value String Value which need to be processed
     * @param allValues Map<String, String> Map with values which can use for substitution.
     * Merged values from base values (like directory paths) and values obtained from xcconfig
     */
    String postProcessValue(String value, Map<String, String> allValues) {
        return String.join(" ", postProcessTokens(value, allValues, new HashSet<>()));
    }

    private List<String> postProcessTokens(String value, Map<String, String> allValues, Set<String> visitedKeys) {
        List<String> result = new ArrayList<>();
        List<String> tmpList = new ArrayList<>(CommandLineTokenizer.splitPreservingEscapedWhitespace(value));

        for (String token : tmpList) {
            if ("$(inherited)".equals(token)) {
                continue;
            }
            result.addAll(postProcessToken(token, allValues, visitedKeys));
        }

        return result;
    }

    private List<String> postProcessToken(String token, Map<String, String> allValues, Set<String> visitedKeys) {
        Matcher matcher = VARIABLE_PATTERN.matcher(token);
        if (matcher.matches()) {
            String replaceKey = matcher.group(1);
            if (visitedKeys.contains(replaceKey)) {
                return List.of(CommandLineTokenizer.escapeWhitespace(token));
            }

            String replaceValue = allValues.get(replaceKey);
            if (replaceValue != null) {
                Set<String> nextVisitedKeys = new HashSet<>(visitedKeys);
                nextVisitedKeys.add(replaceKey);
                if (isListBuildSetting(replaceKey)) {
                    return postProcessTokens(replaceValue, allValues, nextVisitedKeys);
                }
                return List.of(postProcessScalarValue(replaceValue, allValues, nextVisitedKeys));
            } else {
                LOGGER.warn("Can't find value for substitution for key {}", replaceKey);
                return List.of(CommandLineTokenizer.escapeWhitespace(token));
            }
        }

        return List.of(postProcessSingleToken(token, allValues, visitedKeys));
    }

    private boolean isListBuildSetting(String key) {
        return key.endsWith("FLAGS")
            || key.endsWith("PATHS")
            || key.endsWith("DEFINITIONS")
            || key.endsWith("ARCHS")
            || key.endsWith("LIBRARIES");
    }

    private String postProcessScalarValue(String value, Map<String, String> allValues, Set<String> visitedKeys) {
        String scalarValue = String.join(" ", CommandLineTokenizer.splitPreservingEscapedWhitespace(value));
        return postProcessSingleToken(scalarValue, allValues, visitedKeys);
    }

    private String postProcessSingleToken(String token, Map<String, String> allValues, Set<String> visitedKeys) {
        String element = token;
        Set<String> localVisitedKeys = new HashSet<>(visitedKeys);
        Matcher matcher = VARIABLE_PATTERN.matcher(element);
        while (matcher.find()) {
            String replaceKey = matcher.group(1);
            if (localVisitedKeys.contains(replaceKey)) {
                continue;
            }

            String replaceValue = allValues.get(replaceKey);
            localVisitedKeys.add(replaceKey);
            if (replaceValue != null) {
                String resolvedValue = String.join(" ", postProcessTokens(replaceValue, allValues, localVisitedKeys));
                element = element.replace(matcher.group(0), resolvedValue);
                element = element.replaceAll("(?<!\\\\)\"", "");
                // update matcher every time because during replace new values for substitution can be introduced.
                // For example: ${PODS_ROOT}/Headers (where PODS_ROOT=${SRCROOT}) -> ${SRCROOT}/Headers
                matcher = VARIABLE_PATTERN.matcher(element);
            } else {
                LOGGER.warn("Can't find value for substitution for key {}", replaceKey);
            }
        }

        return CommandLineTokenizer.escapeWhitespace(element);
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
        return Pair.of(varBuilder.toString(), normalizeParsedValue(valueBuilder.toString()));
    }

    private String normalizeParsedValue(String value) {
        if (value.length() >= 2) {
            char quote = value.charAt(0);
            if ((quote == '\'' || quote == '"') && value.charAt(value.length() - 1) == quote) {
                String quotedValue = value.substring(1, value.length() - 1);
                if (quotedValue.chars().noneMatch(Character::isWhitespace)) {
                    return quotedValue;
                }
            }
        }

        return value;
    }

    @Override
    public Map<String, String> parse(String moduleName, String podName, File xcconfig) throws IOException {
        // https://pewpewthespells.com/blog/xcconfig_guide.html
        Map<String, String> allValues = calculateBaseVariables(moduleName, podName);

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
            String value = entry.getValue();
            if (value != null) {
                value = postProcessValue(value, allValues);
                entry.setValue(value);
            }
        }
        return allValues;
    }
}
