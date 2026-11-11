package com.defold.extender.services.spm;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.defold.extender.ExtenderException;
import com.defold.extender.process.JobFiles;

/**
 * The build settings that the package targets impart to the wrapper, from the file the script
 * phase of the wrapper writes: the module maps a Swift client of the packages needs, and the
 * libraries and frameworks the packages link.
 */
public class SpmWrapperBuildSettings {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpmWrapperBuildSettings.class);

    public record LinkInfo(List<String> systemLibs, List<String> frameworkNames, List<String> librarySearchPaths) {}

    private static final int MAX_FILE_SIZE = 4 * 1024 * 1024;

    private static final int MAX_LINKED_NAMES = 1024;

    private static final Pattern LIBRARY_PATTERN = Pattern.compile("-l[A-Za-z0-9_+.]+");
    private static final Pattern FRAMEWORK_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_+.-]*");
    // ld options that begin with -l but name no library
    private static final Set<String> LD_FLAGS_WITH_ARG = Set.of("-lto_library", "-load_hidden", "-lazy_library", "-lazy_framework");
    private static final Set<String> LD_FLAGS = Set.of("-ld_classic", "-ld_new");

    /**
     * The first value of each setting in the file, or null when there is no file. Package code ran in
     * the build that wrote the file, so it must be a regular file of the job, not reached through a
     * link out of it.
     */
    public static Map<String, String> read(File jobDir, File settingsFile) throws IOException {
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
            data = in.readNBytes(MAX_FILE_SIZE + 1);
        }
        if (data.length > MAX_FILE_SIZE) {
            throw new IOException(settingsFile + " is larger than " + MAX_FILE_SIZE + " bytes");
        }
        Map<String, String> settings = new LinkedHashMap<>();
        for (String line : new String(data, StandardCharsets.UTF_8).split("\n")) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                settings.putIfAbsent(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        return settings;
    }

    /**
     * The clang module maps that the {@code -Xcc -fmodule-map-file=} flags among Swift compiler
     * flags name. Response files are not read.
     */
    public static List<String> swiftModuleMaps(String swiftFlags) {
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

    /**
     * The libraries, frameworks and library search paths that linker flags name. Only
     * {@code -l<name>} and {@code -framework <name>} with plain names are taken, as the names go
     * into the command line of the engine link, and {@code -L<path>}, which the caller checks;
     * response files are not read.
     */
    public static LinkInfo linkInfo(String ldFlags) throws ExtenderException {
        Set<String> systemLibs = new LinkedHashSet<>();
        Set<String> frameworks = new LinkedHashSet<>();
        Set<String> searchPaths = new LinkedHashSet<>();
        Set<String> ignored = new LinkedHashSet<>();
        List<String> arguments = splitArguments(ldFlags);
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            if (argument.equals("-framework") && i + 1 < arguments.size()) {
                String name = arguments.get(++i);
                if (FRAMEWORK_NAME_PATTERN.matcher(name).matches()) {
                    frameworks.add(name);
                }
                else {
                    ignored.add("-framework " + name);
                }
            }
            else if (argument.equals("-L") && i + 1 < arguments.size()) {
                searchPaths.add(arguments.get(++i));
            }
            else if (argument.startsWith("-L") && argument.length() > 2) {
                searchPaths.add(argument.substring(2));
            }
            else if (LD_FLAGS_WITH_ARG.contains(argument)) {
                // consume the flag's argument so it is not mistaken for a library
                i++;
            }
            else if (argument.startsWith("-l") && !LD_FLAGS.contains(argument)) {
                if (LIBRARY_PATTERN.matcher(argument).matches()) {
                    systemLibs.add(argument.substring(2));
                }
                else {
                    ignored.add(argument);
                }
            }
            if (systemLibs.size() + frameworks.size() + searchPaths.size() > MAX_LINKED_NAMES) {
                throw new ExtenderException("The Swift packages link more than " + MAX_LINKED_NAMES
                    + " libraries, frameworks and library search paths");
            }
        }
        if (!ignored.isEmpty()) {
            LOGGER.warn("Ignoring {} libraries and frameworks that the Swift packages link, as their names are not plain: {}",
                ignored.size(), sample(ignored));
        }
        return new LinkInfo(new ArrayList<>(systemLibs), new ArrayList<>(frameworks), new ArrayList<>(searchPaths));
    }

    /** At most five of the values, each cut to 100 characters, as package code chose them. */
    static String sample(Set<String> values) {
        return values.stream().limit(5)
            .map(value -> (value.length() > 100 ? value.substring(0, 100) + "..." : value).replaceAll("\\p{Cntrl}", "?"))
            .collect(Collectors.joining(", ")) + (values.size() > 5 ? ", ..." : "");
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
