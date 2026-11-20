package com.defold.extender.cache;

import com.defold.extender.ExtenderConst;
import com.defold.extender.Version;
import com.defold.extender.process.CommandLineTokenizer;
import com.defold.extender.services.DataCacheService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Fingerprints compilation inputs once per job. Unknown actions use the normal compiler. */
public final class ExtensionCompileFingerprint {
    public record Keys(String family, String direct, List<List<String>> probes) {}

    private record FileDigest(String hash, boolean unsafe) {}

    private static final List<String> SEARCH_OPTIONS = List.of("-I", "-isystem", "-iquote", "-idirafter", "-F", "-iframework");
    private static final List<String> COMPILE_FLAGS = List.of("-fpic", "-fPIC", "-ffunction-sections", "-fdata-sections",
            "-fstack-protector", "-fstack-protector-strong", "-fomit-frame-pointer", "-fno-omit-frame-pointer",
            "-fno-strict-aliasing", "-funwind-tables", "-fno-exceptions", "-fexceptions", "-fno-rtti", "-frtti",
            "-fno-unwind-tables", "-fno-threadsafe-statics", "-fno-unused-command-line-argument", "-fpermissive",
            "-fvisibility=hidden", "-fvisibility-inlines-hidden", "-fdebug-compilation-dir=.", "-pipe", "-pthread",
            "-Wa,--noexecstack", "-nostdinc++", "-Wno-error", "-ansi", "--ansi", "-msimd128",
            "-ffp-contract=off", "-fno-fast-math");
    private static final List<String> COMPILER_ENVIRONMENT = List.of("PATH", "LANG", "LC_ALL", "LC_CTYPE",
            "SOURCE_DATE_EPOCH", "CC", "CXX", "SDKROOT", "SYSROOT");
    private static final List<String> UNSUPPORTED_ENVIRONMENT = List.of("CPATH", "C_INCLUDE_PATH", "CPLUS_INCLUDE_PATH",
            "OBJC_INCLUDE_PATH", "COMPILER_PATH", "GCC_EXEC_PREFIX", "CCC_OVERRIDE_OPTIONS",
            "CLANG_CONFIG_FILE_USER_DIR", "CLANG_CONFIG_FILE_SYSTEM_DIR");
    private static final List<String> WEB_ENVIRONMENT = List.of("EM_CONFIG", "EM_CACHE", "EMSDK", "EMSDK_PYTHON",
            "EMSDK_NODE", "EM_LLVM_ROOT", "EMCC_STRICT");
    private static final List<String> UNSUPPORTED_WEB_ENVIRONMENT = List.of("EMCC_CFLAGS", "EMMAKEN_CFLAGS",
            "EM_COMPILER_WRAPPER", "EMCC_FORCE_STDLIBS", "EMCC_ONLY_FORCED_STDLIBS", "EMCC_LOCAL_PORTS",
            "EMCC_AUTODEBUG", "EMMAKEN_JUST_CONFIGURE");
    private final Path job;
    private final Path upload;
    private final Path sdk;
    private final String sdkVersion;
    private final String toolchainId;
    private final String platform;
    private final Map<String, String> environment;
    private final Map<Path, FileDigest> files = new HashMap<>();
    private final Map<Path, String> directories = new HashMap<>();

    public ExtensionCompileFingerprint(Path job, Path upload, Path sdk, String sdkVersion,
            String toolchainId, Map<String, String> environment, String platform) {
        this.job = job.toAbsolutePath().normalize();
        this.upload = upload.toAbsolutePath().normalize();
        this.sdk = sdk.toAbsolutePath().normalize();
        this.sdkVersion = sdkVersion;
        this.toolchainId = toolchainId;
        this.platform = platform;
        Map<String, String> inherited = new HashMap<>(System.getenv());
        inherited.putAll(environment);
        this.environment = new TreeMap<>();
        for (String name : COMPILER_ENVIRONMENT) {
            if (inherited.containsKey(name)) {
                this.environment.put(name, inherited.get(name));
            }
        }
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if (entry.getKey().startsWith("NDK_") || entry.getKey().startsWith("ANDROID_")
                    || entry.getKey().equals("DYNAMO_HOME")) {
                this.environment.put(entry.getKey(), entry.getValue());
            }
        }
        for (String name : UNSUPPORTED_ENVIRONMENT) {
            if (!inherited.getOrDefault(name, "").isBlank()) {
                this.environment.put(name, inherited.get(name));
            }
        }
        if (isWebPlatform(platform)) {
            for (Map.Entry<String, String> entry : inherited.entrySet()) {
                if (WEB_ENVIRONMENT.contains(entry.getKey()) || entry.getKey().startsWith("EMSCRIPTEN_")
                        || UNSUPPORTED_WEB_ENVIRONMENT.contains(entry.getKey())) {
                    this.environment.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    public static boolean isWebPlatform(String platform) {
        return "wasm-web".equals(platform) || "wasm_pthread-web".equals(platform);
    }

    public Keys keys(String extension, List<String> compileCommands, List<String> objectPaths,
            List<Path> sources, String archiveTemplate) throws IOException {
        if (toolchainId == null || toolchainId.isBlank() || sdkVersion == null || !sdkVersion.matches("[0-9a-fA-F]{40}") || compileCommands.isEmpty()
                || compileCommands.size() != objectPaths.size() || compileCommands.size() != sources.size()) {
            return null;
        }
        if (UNSUPPORTED_ENVIRONMENT.stream().anyMatch(environment::containsKey)
                || isWebPlatform(platform) && UNSUPPORTED_WEB_ENVIRONMENT.stream()
                        .anyMatch(name -> !environment.getOrDefault(name, "").isBlank())) {
            return null;
        }
        MessageDigest family = digest();
        add(family, "extension-compile-v1");
        add(family, Version.gitVersion);
        add(family, extension);
        add(family, sdkVersion);
        add(family, toolchainId);
        add(family, archiveTemplate);
        if (isWebPlatform(platform)) {
            add(family, platform);
        }
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            add(family, entry.getKey());
            add(family, entry.getKey().equals("DYNAMO_HOME") ? pathName(Path.of(entry.getValue())) : entry.getValue());
        }
        if (isWebPlatform(platform) && environment.containsKey("EM_CONFIG")) {
            Path configuration = absolute(Path.of(environment.get("EM_CONFIG")));
            if (!Files.isRegularFile(configuration, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            add(family, fileDigest(configuration).hash());
        }
        MessageDigest search = digest();
        List<List<String>> probes = new ArrayList<>();
        List<Path> searchDirectories = new ArrayList<>();
        // Quoted includes can reach any upload file through the default upload-root include path.
        searchDirectories.add(upload);
        for (int index = 0; index < compileCommands.size(); index++) {
            List<String> args;
            try {
                args = CommandLineTokenizer.parse(compileCommands.get(index));
            } catch (IllegalArgumentException e) {
                return null;
            }
            if (args.isEmpty() || !(isWebPlatform(platform)
                    ? args.getFirst().matches("(?:.*[/\\\\])?(?:em\\+\\+|emcc)(?:\\.bat|\\.py|\\.exe)?")
                    : args.getFirst().matches(".*(?:clang\\+\\+|clang|g\\+\\+|gcc)(?:\\.exe)?"))) {
                return null;
            }
            Path compiler = resolveExecutable(args.getFirst());
            if (compiler == null) {
                return null;
            }
            add(family, args.getFirst());
            add(family, pathName(compiler));
            Path source = absolute(sources.get(index));
            if (!source.startsWith(job) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            FileDigest sourceDigest = fileDigest(source);
            if (sourceDigest.unsafe()) {
                return null;
            }
            add(family, pathName(source));
            add(family, sourceDigest.hash());
            searchDirectories.add(source.getParent());
            List<String> probe = new ArrayList<>();
            probe.add(args.getFirst());
            boolean compile = false;
            boolean output = false;
            boolean input = false;
            for (int i = 1; i < args.size(); i++) {
                String argument = args.get(i);
                if (argument.startsWith("@")) {
                    return null;
                }
                if (argument.equals("-c")) {
                    compile = true;
                    continue;
                }
                if (isWebPlatform(platform) && (argument.equals("-s") || argument.matches("-s[A-Z_].*"))) {
                    String setting = argument.equals("-s") ? ++i < args.size() ? args.get(i) : "" : argument.substring(2);
                    if (!setting.matches("DISABLE_EXCEPTION_CATCHING=[01]")) {
                        return null;
                    }
                    add(family, "-s" + setting);
                    probe.add("-s");
                    probe.add(setting);
                    continue;
                }
                if (argument.equals("-o") || argument.startsWith("-o")) {
                    String target = argument.equals("-o") ? ++i < args.size() ? args.get(i) : "" : argument.substring(2);
                    if (!absolute(Path.of(target)).equals(absolute(Path.of(objectPaths.get(index))))) {
                        return null;
                    }
                    output = true;
                    continue;
                }
                String searchOption = searchOption(argument);
                if (searchOption != null) {
                    String location = argument.equals(searchOption) ? ++i < args.size() ? args.get(i) : "" : argument.substring(searchOption.length());
                    if (location.isEmpty()) {
                        return null;
                    }
                    add(search, searchOption);
                    Path directory = absolute(Path.of(location));
                    add(search, pathName(directory));
                    searchDirectories.add(directory);
                    probe.add(searchOption);
                    probe.add(location);
                    continue;
                }
                if (argument.equals("-include") || argument.equals("-imacros")) {
                    // These may be resolved through search paths. The conservative direct tree covers job inputs.
                    if (++i >= args.size()) {
                        return null;
                    }
                    String location = args.get(i);
                    Path forced = absolute(Path.of(location));
                    if (!Files.isRegularFile(forced) || !forced.startsWith(job)) {
                        return null;
                    }
                    FileDigest forcedDigest = fileDigest(forced);
                    if (forcedDigest.unsafe()) {
                        return null;
                    }
                    add(family, argument);
                    add(family, pathName(forced));
                    add(search, forcedDigest.hash());
                    searchDirectories.add(forced.getParent());
                    probe.add(argument);
                    probe.add(location);
                    continue;
                }
                if (argument.equals("-ffile-prefix-map=" + job + "=.")
                        || argument.equals("-ffile-prefix-map=" + job.toString().replace('\\', '/') + "=.")) {
                    add(family, "-ffile-prefix-map=$JOB=.");
                } else if (argument.startsWith("-ffile-prefix-map=")) {
                    return null;
                } else if (!argument.startsWith("-")) {
                    if (!absolute(Path.of(argument)).equals(source)) {
                        // Separate operands such as -x c and --sysroot PATH are declined conservatively.
                        return null;
                    }
                    input = true;
                    add(family, pathName(source));
                } else {
                    if (!supportedCompileFlag(argument)) {
                        return null;
                    }
                    add(family, argument);
                }
                probe.add(argument);
            }
            if (!compile || !output || !input) {
                return null;
            }
            probe.add("-E");
            probes.add(List.copyOf(probe));
        }
        for (Path directory : searchDirectories.stream().distinct().sorted().toList()) {
            add(search, pathName(directory));
            if (directory.startsWith(sdk)) {
                // Defold SDK content is identified by its immutable SHA.
                continue;
            }
            String tree = directoryDigest(directory);
            if (tree == null) {
                return null;
            }
            add(search, tree);
        }
        String familyKey = hex(family);
        add(search, familyKey);
        return new Keys(familyKey, hex(search), List.copyOf(probes));
    }

    public String effective(Keys keys, List<Path> preprocessedOutputs) throws IOException {
        if (keys.probes().size() != preprocessedOutputs.size()) {
            throw new IllegalArgumentException("One preprocessed output is required per compile action");
        }
        MessageDigest result = digest();
        add(result, keys.family());
        for (Path output : preprocessedOutputs) {
            add(result, Long.toString(Files.size(output)));
            try (InputStream input = Files.newInputStream(output)) {
                if (digestFile(input, result)) {
                    return null;
                }
            }
        }
        return hex(result);
    }

    private String directoryDigest(Path directory) throws IOException {
        if (directories.containsKey(directory)) {
            return directories.get(directory);
        }
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return "missing";
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        MessageDigest result = digest();
        try (Stream<Path> entries = Files.walk(directory)) {
            for (Path path : entries.sorted(Comparator.comparing(p -> directory.relativize(p).toString())).toList()) {
                if (Files.isSymbolicLink(path)) {
                    return null;
                }
                if (path.equals(upload.resolve(DataCacheService.FILE_CACHE_INFO_FILE))
                        || path.equals(upload.resolve(ExtenderConst.SOURCE_CODE_ARCHIVE_MAGIC_NAME))) {
                    continue;
                }
                add(result, directory.relativize(path).toString().replace('\\', '/'));
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    add(result, "directory");
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    FileDigest file = fileDigest(path);
                    if (file.unsafe()) {
                        return null;
                    }
                    add(result, file.hash());
                } else {
                    return null;
                }
            }
        }
        String hash = hex(result);
        directories.put(directory, hash);
        return hash;
    }

    private FileDigest fileDigest(Path path) throws IOException {
        FileDigest cached = files.get(path);
        if (cached != null) {
            return cached;
        }
        MessageDigest result = digest();
        boolean unsafe;
        try (InputStream input = Files.newInputStream(path)) {
            unsafe = digestFile(input, result);
        }
        FileDigest value = new FileDigest(hex(result), unsafe);
        files.put(path, value);
        return value;
    }

    private Path resolveExecutable(String executable) throws IOException {
        Path candidate = absolute(Path.of(executable));
        if (Files.isRegularFile(candidate)) {
            return candidate.toRealPath();
        }
        for (String directory : environment.getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            candidate = Path.of(directory).resolve(executable);
            if (Files.isRegularFile(candidate)) {
                return candidate.toRealPath();
            }
        }
        return null;
    }

    private Path absolute(Path path) {
        return (path.isAbsolute() ? path : job.resolve(path)).toAbsolutePath().normalize();
    }

    private String pathName(Path path) {
        Path absolute = absolute(path);
        if (absolute.startsWith(sdk)) {
            return "$SDK/" + sdk.relativize(absolute).toString().replace('\\', '/');
        }
        if (absolute.startsWith(job)) {
            return "$JOB/" + job.relativize(absolute).toString().replace('\\', '/');
        }
        return absolute.toString().replace('\\', '/');
    }

    private static String searchOption(String argument) {
        for (String option : SEARCH_OPTIONS) {
            if (argument.startsWith(option)) {
                return option;
            }
        }
        return null;
    }

    private boolean supportedCompileFlag(String argument) {
        if (argument.startsWith("-isysroot=") || argument.startsWith("--sysroot=")) {
            Path sysroot = Path.of(argument.substring(argument.indexOf('=') + 1));
            return sysroot.isAbsolute() && !absolute(sysroot).startsWith(job);
        }
        return COMPILE_FLAGS.contains(argument) || argument.matches("-(?:[DU].+|O[0-3szg]|Ofast|g(?:[0-3]|dwarf-[2-5])?"
                + "|W[\\w=+-]+|m(?:arch|cpu|tune)=[\\w.+-]+|std=[\\w+]+|stdlib=[\\w+]+|ferror-limit=\\d+)");
    }

    private static boolean digestFile(InputStream input, MessageDigest result) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        int count;
        String tail = "";
        boolean unsafe = false;
        while ((count = input.read(buffer)) != -1) {
            result.update(buffer, 0, count);
            String text = tail + new String(buffer, 0, count, StandardCharsets.ISO_8859_1);
            unsafe |= text.contains("__TIME__") || text.contains("__DATE__") || text.contains("__TIMESTAMP__")
                    || text.contains(".incbin") || text.contains(".include");
            tail = text.substring(Math.max(0, text.length() - 32));
        }
        return unsafe;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }
}
