package com.defold.extender.services.spm;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts the wrapper's final link line and the generated module maps from the captured
 * xcodebuild log. A dynamic wrapper links via clang -dynamiclib, a static one archives via
 * libtool -static; package targets are prelinked with clang -r into loose <Target>.o files
 * whose Ld lines must not be mistaken for the wrapper link.
 */
public class SpmBuildOutputParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpmBuildOutputParser.class);

    public record LinkInfo(List<String> systemLibs,
                           List<String> frameworkNames,
                           List<String> librarySearchPaths,
                           List<String> rpaths,
                           List<String> forceLoad) {}

    private static final Pattern MODULE_MAP_PATTERN = Pattern.compile("-fmodule-map-file=([^\\s']+)");
    // an exact -l<name> token. This on its own does not separate libraries from the ld
    // flags that also begin with -l ("-lto_library" matches it too) — those are listed
    // in LD_FLAGS_WITH_ARG and consumed with their argument before this is tried.
    private static final Pattern LIB_TOKEN_PATTERN = Pattern.compile("-l[A-Za-z0-9_+.]+");
    // ld flags that begin with -l but name no library; each takes a following argument
    private static final Set<String> LD_FLAGS_WITH_ARG = Set.of("-lto_library", "-lazy_library", "-lazy_framework");

    // Xcode moves long link commands into response files referenced as @<path>. The flags
    // inside are part of the link line and are expanded here — an unexpanded token silently
    // drops every -framework/-l/-L the wrapper's consumers need.
    private static final int MAX_RESPONSE_FILE_DEPTH = 4;
    private static final long MAX_RESPONSE_FILE_SIZE = 4L * 1024 * 1024;
    // dyld run-path placeholders: an "@" token that is a path value, not a response file
    private static final List<String> DYLD_PATH_PREFIXES = List.of("@rpath/", "@executable_path/", "@loader_path/");

    /**
     * Single streaming pass — a large package graph produces a log too big to hold whole.
     * The clang -dynamiclib line wins over the libtool -static fallback.
     */
    public static String findWrapperLinkLine(File logFile, String wrapperName) throws IOException {
        String wrapperBinary = wrapperBinaryPath(wrapperName);
        String libtoolLine = null;
        // an xcodebuild log is not guaranteed to be valid UTF-8; InputStreamReader replaces
        // malformed input where Files.newBufferedReader would throw
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(logFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // cheap pre-filter: both link steps name the wrapper in their -o argument
                if (!line.contains(wrapperName)) {
                    continue;
                }
                if (isDynamicLinkLine(line, wrapperBinary)) {
                    return line;
                }
                if (libtoolLine == null && isLibtoolLine(line, wrapperBinary)) {
                    libtoolLine = line;
                }
            }
        }
        return libtoolLine;
    }

    /** The clang -dynamiclib invocation that links <wrapper>.framework/<wrapper>, or null. */
    public static String findWrapperLinkLine(String log, String wrapperName) {
        String wrapperBinary = wrapperBinaryPath(wrapperName);
        for (String line : log.split("\n")) {
            if (isDynamicLinkLine(line, wrapperBinary)) {
                return line;
            }
        }
        return null;
    }

    /** The libtool -static invocation that archives <wrapper>.framework/<wrapper>, or null. */
    public static String findWrapperLibtoolLine(String log, String wrapperName) {
        String wrapperBinary = wrapperBinaryPath(wrapperName);
        for (String line : log.split("\n")) {
            if (isLibtoolLine(line, wrapperBinary)) {
                return line;
            }
        }
        return null;
    }

    private static String wrapperBinaryPath(String wrapperName) {
        return wrapperName + ".framework/" + wrapperName;
    }

    private static boolean isDynamicLinkLine(String line, String wrapperBinary) {
        return line.contains("-dynamiclib") && outputsWrapperBinary(line, wrapperBinary);
    }

    private static boolean isLibtoolLine(String line, String wrapperBinary) {
        return line.contains("libtool") && line.contains("-static") && outputsWrapperBinary(line, wrapperBinary);
    }

    private static boolean outputsWrapperBinary(String line, String wrapperBinary) {
        // macOS frameworks are versioned bundles: the binary is <W>.framework/Versions/<v>/<W>
        int slash = wrapperBinary.indexOf('/');
        String bundleName = wrapperBinary.substring(0, slash);
        String binaryName = wrapperBinary.substring(slash);
        List<String> tokens = tokenize(line);
        for (int i = 0; i < tokens.size() - 1; i++) {
            if (!tokens.get(i).equals("-o")) {
                continue;
            }
            String output = tokens.get(i + 1);
            if (output.endsWith(binaryName) && output.contains(bundleName + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tokenizes one link line and collects the flags a consumer of the wrapper needs.
     * Search paths under excludePathPrefix (the job's DerivedData) are dropped — they
     * are job-local and re-expressed from the harvested products directory instead.
     */
    public static LinkInfo parseLinkLine(String linkLine, String excludePathPrefix) {
        return parseLinkLine(linkLine, excludePathPrefix, null);
    }

    /**
     * @param responseFileBaseDir directory relative @<path> response-file tokens resolve
     *                            against (the working directory of the build step)
     */
    public static LinkInfo parseLinkLine(String linkLine, String excludePathPrefix, File responseFileBaseDir) {
        Set<String> systemLibs = new LinkedHashSet<>();
        Set<String> frameworks = new LinkedHashSet<>();
        Set<String> librarySearchPaths = new LinkedHashSet<>();
        Set<String> rpaths = new LinkedHashSet<>();
        Set<String> forceLoad = new LinkedHashSet<>();

        List<String> tokens = expandResponseFiles(tokenize(linkLine), responseFileBaseDir, 0);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.equals("-framework") && i + 1 < tokens.size()) {
                frameworks.add(tokens.get(++i));
            }
            else if (LD_FLAGS_WITH_ARG.contains(token)) {
                // consume the flag's argument so it is not mistaken for an input file
                i++;
            }
            else if (LIB_TOKEN_PATTERN.matcher(token).matches()) {
                systemLibs.add(token.substring(2));
            }
            else if (token.startsWith("-L") && token.length() > 2) {
                String path = token.substring(2);
                if (!path.startsWith(excludePathPrefix)) {
                    librarySearchPaths.add(path);
                }
            }
            else if (token.equals("-force_load") && i + 1 < tokens.size()) {
                forceLoad.add(tokens.get(++i));
            }
            else if (token.equals("-Xlinker") && i + 1 < tokens.size()) {
                String linkerArg = tokens.get(++i);
                if (linkerArg.equals("-rpath") && i + 2 < tokens.size() && tokens.get(i + 1).equals("-Xlinker")) {
                    rpaths.add(tokens.get(i + 2));
                    i += 2;
                }
                else if (linkerArg.equals("-force_load") && i + 2 < tokens.size() && tokens.get(i + 1).equals("-Xlinker")) {
                    forceLoad.add(tokens.get(i + 2));
                    i += 2;
                }
            }
            else if (token.startsWith("-Wl,")) {
                String[] parts = token.substring(4).split(",");
                if (parts.length > 1 && parts[0].equals("-rpath")) {
                    rpaths.add(parts[1]);
                }
                else if (parts.length > 1 && parts[0].equals("-force_load")) {
                    forceLoad.add(parts[1]);
                }
            }
        }

        return new LinkInfo(
            new ArrayList<>(systemLibs),
            new ArrayList<>(frameworks),
            new ArrayList<>(librarySearchPaths),
            new ArrayList<>(rpaths),
            new ArrayList<>(forceLoad));
    }

    /** All -fmodule-map-file= values in the log, deduplicated, quotes stripped. */
    public static List<String> parseModuleMaps(String log) {
        Set<String> moduleMaps = new LinkedHashSet<>();
        Matcher matcher = MODULE_MAP_PATTERN.matcher(log);
        while (matcher.find()) {
            moduleMaps.add(matcher.group(1));
        }
        return new ArrayList<>(moduleMaps);
    }

    /** The object files fed to the wrapper link, from <Wrapper>.LinkFileList. */
    public static List<String> readLinkFileList(File linkFileList) throws IOException {
        List<String> result = new ArrayList<>();
        for (String line : Files.readAllLines(linkFileList.toPath())) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * Replaces every @<path> response-file token with the tokens the file holds. A file
     * that cannot be expanded is reported and dropped: silently keeping the token would
     * leave the harvested link surface incomplete with no trace of why.
     */
    private static List<String> expandResponseFiles(List<String> tokens, File baseDir, int depth) {
        boolean hasResponseFile = false;
        for (String token : tokens) {
            if (isResponseFileToken(token)) {
                hasResponseFile = true;
                break;
            }
        }
        if (!hasResponseFile) {
            return tokens;
        }

        List<String> expanded = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            if (!isResponseFileToken(token)) {
                expanded.add(token);
                continue;
            }
            String path = token.substring(1);
            File file = new File(path);
            if (!file.isAbsolute() && baseDir != null) {
                file = new File(baseDir, path);
            }
            if (depth >= MAX_RESPONSE_FILE_DEPTH) {
                LOGGER.warn("Link response file {} nested deeper than {} levels, its link flags are not harvested",
                    file, MAX_RESPONSE_FILE_DEPTH);
                continue;
            }
            if (!file.isFile()) {
                LOGGER.warn("Link response file {} does not exist, its link flags are not harvested", file);
                continue;
            }
            if (file.length() > MAX_RESPONSE_FILE_SIZE) {
                LOGGER.warn("Link response file {} is larger than {} bytes, its link flags are not harvested",
                    file, MAX_RESPONSE_FILE_SIZE);
                continue;
            }
            try {
                String contents = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                expanded.addAll(expandResponseFiles(tokenize(contents), file.getParentFile(), depth + 1));
            } catch (IOException e) {
                LOGGER.warn("Failed to read link response file {}, its link flags are not harvested", file, e);
            }
        }
        return expanded;
    }

    private static boolean isResponseFileToken(String token) {
        if (token.length() < 2 || token.charAt(0) != '@') {
            return false;
        }
        for (String prefix : DYLD_PATH_PREFIXES) {
            if (token.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Splits a command line into tokens the way a shell would: quoted runs and
     * backslash-escaped characters keep their whitespace, quotes are not part of the value.
     */
    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inToken = false;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && quote != '\'' && i + 1 < text.length()) {
                current.append(text.charAt(++i));
                inToken = true;
            }
            else if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                else {
                    current.append(c);
                }
            }
            else if (c == '\'' || c == '"') {
                quote = c;
                inToken = true;
            }
            else if (Character.isWhitespace(c)) {
                if (inToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
            }
            else {
                current.append(c);
                inToken = true;
            }
        }
        if (inToken) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
