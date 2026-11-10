package com.defold.extender.services.spm;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.defold.extender.process.JobFiles;

/**
 * Extracts the wrapper's final link line from the captured xcodebuild log, and the module maps
 * imparted to the wrapper from the build settings its script phase writes. A dynamic wrapper
 * links via clang -dynamiclib, a static one archives via libtool -static; package targets are
 * prelinked with clang -r into loose <Target>.o files whose Ld lines must not be mistaken for
 * the wrapper link.
 */
public class SpmBuildOutputParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpmBuildOutputParser.class);

    public record LinkInfo(List<String> systemLibs,
                           List<String> frameworkNames,
                           List<String> librarySearchPaths,
                           List<String> rpaths,
                           List<String> forceLoad) {}

    private static final Pattern MODULE_MAP_PATTERN = Pattern.compile("-fmodule-map-file=([^\\s']+)");
    // an exact -l<name> argument. This on its own does not separate libraries from the ld
    // flags that also begin with -l ("-lto_library" matches it too) — those are listed
    // in LD_FLAGS_WITH_ARG and consumed with their argument before this is tried.
    private static final Pattern LIBRARY_PATTERN = Pattern.compile("-l[A-Za-z0-9_+.]+");
    // ld flags that begin with -l but name no library; each takes a following argument
    private static final Set<String> LD_FLAGS_WITH_ARG = Set.of("-lto_library", "-lazy_library", "-lazy_framework");

    // Xcode moves long link commands into response files referenced as @<path>. The flags
    // inside are part of the link line and are expanded here — an unexpanded argument silently
    // drops every -framework/-l/-L the wrapper's consumers need.
    private static final int MAX_RESPONSE_FILE_DEPTH = 4;
    private static final long MAX_RESPONSE_FILE_SIZE = 4L * 1024 * 1024;
    // dyld run-path placeholders: an "@" argument that is a path value, not a response file
    private static final List<String> DYLD_PATH_PREFIXES = List.of("@rpath/", "@executable_path/", "@loader_path/");

    private static final int MAX_BUILD_SETTINGS_FILE_SIZE = 4 * 1024 * 1024;

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
        List<String> arguments = splitArguments(line);
        for (int i = 0; i < arguments.size() - 1; i++) {
            if (!arguments.get(i).equals("-o")) {
                continue;
            }
            String output = arguments.get(i + 1);
            if (output.endsWith(binaryName) && output.contains(bundleName + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Splits one link line into arguments and collects the flags a consumer of the wrapper needs.
     * Search paths under excludePathPrefix (the job's DerivedData) are dropped — they
     * are job-local and re-expressed from the harvested products directory instead.
     */
    public static LinkInfo parseLinkLine(String linkLine, String excludePathPrefix) {
        return parseLinkLine(linkLine, excludePathPrefix, null);
    }

    /**
     * @param responseFileBaseDir directory relative @<path> response-file arguments resolve
     *                            against (the working directory of the build step)
     */
    public static LinkInfo parseLinkLine(String linkLine, String excludePathPrefix, File responseFileBaseDir) {
        Set<String> systemLibs = new LinkedHashSet<>();
        Set<String> frameworks = new LinkedHashSet<>();
        Set<String> librarySearchPaths = new LinkedHashSet<>();
        Set<String> rpaths = new LinkedHashSet<>();
        Set<String> forceLoad = new LinkedHashSet<>();

        List<String> arguments = expandResponseFiles(splitArguments(linkLine), responseFileBaseDir, 0);
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            if (argument.equals("-framework") && i + 1 < arguments.size()) {
                frameworks.add(arguments.get(++i));
            }
            else if (LD_FLAGS_WITH_ARG.contains(argument)) {
                // consume the flag's argument so it is not mistaken for an input file
                i++;
            }
            else if (LIBRARY_PATTERN.matcher(argument).matches()) {
                systemLibs.add(argument.substring(2));
            }
            else if (argument.startsWith("-L") && argument.length() > 2) {
                String path = argument.substring(2);
                if (!path.startsWith(excludePathPrefix)) {
                    librarySearchPaths.add(path);
                }
            }
            else if (argument.equals("-force_load") && i + 1 < arguments.size()) {
                forceLoad.add(arguments.get(++i));
            }
            else if (argument.equals("-Xlinker") && i + 1 < arguments.size()) {
                String linkerArg = arguments.get(++i);
                if (linkerArg.equals("-rpath") && i + 2 < arguments.size() && arguments.get(i + 1).equals("-Xlinker")) {
                    rpaths.add(arguments.get(i + 2));
                    i += 2;
                }
                else if (linkerArg.equals("-force_load") && i + 2 < arguments.size() && arguments.get(i + 1).equals("-Xlinker")) {
                    forceLoad.add(arguments.get(i + 2));
                    i += 2;
                }
            }
            else if (argument.startsWith("-Wl,")) {
                String[] parts = argument.substring(4).split(",");
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

    /**
     * The value of a setting in the file the script phase of the wrapper writes, or null when the
     * file or the setting is missing. Package code ran in the build that wrote the file, so it must be
     * a regular file of the job, not reached through a link out of it.
     */
    public static String readWrapperBuildSetting(File jobDir, File settingsFile, String name) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(settingsFile.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return null;
        }
        if (!attributes.isRegularFile()) {
            throw new IOException(settingsFile + " is not a regular file");
        }
        JobFiles.requireWithin(jobDir, settingsFile);
        byte[] data;
        try (InputStream in = Files.newInputStream(settingsFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            data = in.readNBytes(MAX_BUILD_SETTINGS_FILE_SIZE + 1);
        }
        if (data.length > MAX_BUILD_SETTINGS_FILE_SIZE) {
            throw new IOException(settingsFile + " is larger than " + MAX_BUILD_SETTINGS_FILE_SIZE + " bytes");
        }
        String prefix = name + "=";
        for (String line : new String(data, StandardCharsets.UTF_8).split("\n")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length());
            }
        }
        return null;
    }

    /**
     * The clang module maps that the {@code -Xcc -fmodule-map-file=} flags among Swift compiler
     * flags name. Response files are not read.
     */
    public static List<String> parseSwiftModuleMaps(String swiftFlags) {
        List<String> arguments = splitArguments(swiftFlags);
        Set<String> moduleMaps = new LinkedHashSet<>();
        for (int i = 1; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            if (arguments.get(i - 1).equals("-Xcc") && argument.startsWith("-fmodule-map-file=")) {
                moduleMaps.add(argument.substring("-fmodule-map-file=".length()));
            }
        }
        return new ArrayList<>(moduleMaps);
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
     * Replaces every @<path> response-file argument with the arguments the file holds. A file
     * that cannot be expanded is reported and dropped: silently keeping the argument would
     * leave the harvested link surface incomplete with no trace of why.
     */
    private static List<String> expandResponseFiles(List<String> arguments, File baseDir, int depth) {
        boolean hasResponseFile = false;
        for (String argument : arguments) {
            if (isResponseFile(argument)) {
                hasResponseFile = true;
                break;
            }
        }
        if (!hasResponseFile) {
            return arguments;
        }

        List<String> expanded = new ArrayList<>(arguments.size());
        for (String argument : arguments) {
            if (!isResponseFile(argument)) {
                expanded.add(argument);
                continue;
            }
            String path = argument.substring(1);
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
                expanded.addAll(expandResponseFiles(splitArguments(contents), file.getParentFile(), depth + 1));
            } catch (IOException e) {
                LOGGER.warn("Failed to read link response file {}, its link flags are not harvested", file, e);
            }
        }
        return expanded;
    }

    private static boolean isResponseFile(String argument) {
        if (argument.length() < 2 || argument.charAt(0) != '@') {
            return false;
        }
        for (String prefix : DYLD_PATH_PREFIXES) {
            if (argument.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Splits a command line into arguments the way a shell would: quoted runs and
     * backslash-escaped characters keep their whitespace, quotes are not part of the value.
     */
    private static List<String> splitArguments(String text) {
        List<String> arguments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inArgument = false;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && quote != '\'' && i + 1 < text.length()) {
                current.append(text.charAt(++i));
                inArgument = true;
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
                inArgument = true;
            }
            else if (Character.isWhitespace(c)) {
                if (inArgument) {
                    arguments.add(current.toString());
                    current.setLength(0);
                    inArgument = false;
                }
            }
            else {
                current.append(c);
                inArgument = true;
            }
        }
        if (inArgument) {
            arguments.add(current.toString());
        }
        return arguments;
    }
}
