package com.defold.extender.services.spm;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.defold.extender.ExtenderException;
import com.defold.extender.ExtenderUtil;
import com.defold.extender.process.JobFiles;
import com.defold.extender.services.ResolvedNativeDeps;
import com.defold.extender.services.spm.SpmWrapperBuildSettings.LinkInfo;
import com.defold.extender.utils.FrameworkUtil;

/**
 * The harvested result of an SPM wrapper build: everything Extender needs to compile the
 * extension sources against the packages and link the engine with them. A static wrapper
 * is linked into the engine; a dylib wrapper is additionally embedded — both fall out of
 * the dynamic-frameworks classification.
 */
public class ResolvedPackages implements ResolvedNativeDeps {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResolvedPackages.class);

    private final List<String> frameworks = new ArrayList<>();
    private final List<String> frameworksSearchPaths = new ArrayList<>();
    private final List<String> staticLibraries = new ArrayList<>();
    private final List<String> librarySearchPaths = new ArrayList<>();
    private final List<String> additionalIncludePaths = new ArrayList<>();
    private final List<String> swiftModuleMaps = new ArrayList<>();
    private final List<String> swiftIncludePaths = new ArrayList<>();
    private final List<String> linkFlags = new ArrayList<>();
    private final List<File> resources = new ArrayList<>();
    private final List<File> dynamicFrameworks = new ArrayList<>();
    private final List<File> privacyManifests = new ArrayList<>();
    // outside the job directory, so the sandboxed engine link must be granted them explicitly
    private final List<String> sandboxReadOnlyPaths = new ArrayList<>();
    private final File lockFile;
    private final String platformMinVersion;

    private ResolvedPackages(File lockFile, String platformMinVersion) {
        this.lockFile = lockFile;
        this.platformMinVersion = platformMinVersion;
    }

    /**
     * @param wrapperModuleMaps the module maps that the package targets impart to the Swift compile
     *                          of the wrapper, as the build settings of the wrapper name them
     */
    static ResolvedPackages harvest(SpmServiceBuildState buildState, String platformMinVersion, LinkInfo linkInfo,
            File swiftRuntimeLibDir, List<String> wrapperModuleMaps) throws IOException, ExtenderException {
        File productsDir = buildState.getProductsDir();
        if (!productsDir.isDirectory()) {
            throw new ExtenderException("Swift package build products directory does not exist: " + productsDir);
        }

        File lockFile = buildState.getLockFile().isFile() ? buildState.getLockFile() : null;
        ResolvedPackages resolved = new ResolvedPackages(lockFile, platformMinVersion);

        File[] entries = productsDir.listFiles();
        if (entries == null) {
            throw new ExtenderException("Cannot list the Swift package build products directory: " + productsDir);
        }
        Arrays.sort(entries);
        // product framework name -> is a dylib
        java.util.Map<String, Boolean> productFrameworks = new java.util.LinkedHashMap<>();
        for (File entry : entries) {
            String name = entry.getName();
            if (name.endsWith(".framework") && entry.isDirectory()) {
                boolean dynamic = FrameworkUtil.isDynamicallyLinked(entry, buildState.getJobDir());
                productFrameworks.put(name.substring(0, name.length() - ".framework".length()), dynamic);
                if (dynamic) {
                    resolved.dynamicFrameworks.add(entry);
                }
            }
            else if (name.endsWith(".bundle") && entry.isDirectory()) {
                resolved.resources.add(entry);
            }
        }

        // a package library product declared `type: .dynamic` does not merge into the
        // wrapper; it builds as a dylib framework under PackageFrameworks and must be
        // linked and embedded like any other dynamic framework
        File packageFrameworksDir = new File(productsDir, "PackageFrameworks");
        File[] packageFrameworkEntries = packageFrameworksDir.listFiles();
        if (packageFrameworkEntries != null) {
            Arrays.sort(packageFrameworkEntries);
            for (File entry : packageFrameworkEntries) {
                String name = entry.getName();
                if (!name.endsWith(".framework") || !entry.isDirectory()) {
                    continue;
                }
                String frameworkName = name.substring(0, name.length() - ".framework".length());
                if (productFrameworks.containsKey(frameworkName)) {
                    continue;
                }
                boolean dynamic = FrameworkUtil.isDynamicallyLinked(entry, buildState.getJobDir());
                productFrameworks.put(frameworkName, dynamic);
                if (dynamic) {
                    resolved.dynamicFrameworks.add(entry);
                }
            }
        }

        // every products-dir framework stays on the engine link: a wrapper pulls static
        // framework members only on demand, so the framework remains the authoritative
        // home of ObjC classes the extension code references itself
        Set<String> frameworkNames = new LinkedHashSet<>(productFrameworks.keySet());

        frameworkNames.addAll(linkInfo.frameworkNames());
        // a -l naming a products-dir library is a wrapper input the wrapper already
        // resolved; system libs (c++, z, ...) have no products-dir counterpart and stay
        for (String lib : linkInfo.systemLibs()) {
            if (!Files.exists(new File(productsDir, "lib" + lib + ".a").toPath(), LinkOption.NOFOLLOW_LINKS)) {
                resolved.staticLibraries.add(lib);
            }
        }
        // a package can keep libraries of its own in its folder, with a search path for them in unsafeFlags
        List<Path> workingDirSpellings = spellingsOf(buildState.getWorkingDir());
        Set<String> knownSearchPaths = new HashSet<>();
        Set<String> ignoredSearchPaths = new LinkedHashSet<>();
        for (String path : linkInfo.librarySearchPaths()) {
            File dir = packageLibraryDir(buildState.getWorkingDir(), workingDirSpellings, path);
            if (dir == null) {
                ignoredSearchPaths.add(path);
            }
            else if (knownSearchPaths.add(dir.getCanonicalPath())) {
                resolved.librarySearchPaths.add(dir.getPath());
            }
        }
        if (!ignoredSearchPaths.isEmpty()) {
            LOGGER.warn("Ignoring {} library search paths of the Swift packages that are not folders of the Swift package build: {}",
                ignoredSearchPaths.size(), SpmWrapperBuildSettings.sample(ignoredSearchPaths));
        }
        // package objects auto-link the Swift compatibility archives from the building
        // Xcode's toolchain
        if (swiftRuntimeLibDir != null && swiftRuntimeLibDir.isDirectory()) {
            resolved.librarySearchPaths.add(swiftRuntimeLibDir.getAbsolutePath());
            resolved.sandboxReadOnlyPaths.add(swiftRuntimeLibDir.getAbsolutePath());
        }
        resolved.frameworks.addAll(frameworkNames);

        resolved.frameworksSearchPaths.add(productsDir.getAbsolutePath());
        if (packageFrameworkEntries != null && packageFrameworkEntries.length > 0) {
            resolved.frameworksSearchPaths.add(packageFrameworksDir.getAbsolutePath());
        }

        Set<String> includePaths = new LinkedHashSet<>();
        File generatedModuleMapsDir = buildState.getGeneratedModuleMapsDir();
        if (generatedModuleMapsDir.isDirectory()) {
            includePaths.add(generatedModuleMapsDir.getAbsolutePath());
        }
        includePaths.add(productsDir.getAbsolutePath());
        File productsIncludeDir = new File(productsDir, "include");
        if (productsIncludeDir.isDirectory()) {
            includePaths.add(productsIncludeDir.getAbsolutePath());
        }
        collectModuleMapIncludePaths(generatedModuleMapsDir, new File(buildState.getWorkingDir(), "include"), includePaths);
        resolved.additionalIncludePaths.addAll(includePaths);

        Set<String> knownModuleMaps = new HashSet<>();
        File[] moduleMaps = generatedModuleMapsDir.listFiles((dir, name) -> name.endsWith(".modulemap"));
        if (moduleMaps != null) {
            Arrays.sort(moduleMaps);
            for (File moduleMap : moduleMaps) {
                resolved.swiftModuleMaps.add(moduleMap.getAbsolutePath());
                knownModuleMaps.add(moduleMap.getCanonicalPath());
            }
        }
        // a target with its own module.modulemap gets no generated one. Xcode also puts the folder of
        // the map on the include path, as the headers of the module include each other from there
        Set<String> swiftIncludePaths = new LinkedHashSet<>();
        for (String path : wrapperModuleMaps) {
            File moduleMap = packageModuleMap(buildState.getWorkingDir(), workingDirSpellings, path);
            if (moduleMap != null && knownModuleMaps.add(moduleMap.getCanonicalPath())) {
                resolved.swiftModuleMaps.add(moduleMap.getPath());
                swiftIncludePaths.add(moduleMap.getParent());
            }
        }
        resolved.swiftIncludePaths.addAll(swiftIncludePaths);

        // an embedded framework resolves the Swift runtime and its own location at run time
        if (!resolved.dynamicFrameworks.isEmpty()) {
            resolved.linkFlags.add("-Wl,-rpath,/usr/lib/swift");
            resolved.linkFlags.add("-Wl,-rpath,@executable_path/Frameworks");
        }

        // versioned (macOS) framework bundles expose the same manifest through the
        // Resources and Versions/A + Versions/Current symlinks — keep one per file
        Set<String> seenManifests = new LinkedHashSet<>();
        for (File manifest : ExtenderUtil.listFilesMatchingRecursive(productsDir, "PrivacyInfo.xcprivacy")) {
            if (seenManifests.add(manifest.getCanonicalPath())) {
                resolved.privacyManifests.add(manifest);
            }
        }

        return resolved;
    }

    // the characters that split or quote an argument of a rendered command line
    private static final java.util.regex.Pattern COMMAND_LINE_SPECIAL = java.util.regex.Pattern.compile("[\\s'\"\\\\]");

    /**
     * The module map at a path the wrapper build settings name, as Xcode spelled it, or null.
     */
    private static File packageModuleMap(File workingDir, List<Path> workingDirSpellings, String path) {
        Path lexical = packagePath(workingDirSpellings, path);
        if (lexical == null || !lexical.toString().endsWith(".modulemap")) {
            LOGGER.warn("Ignoring the module map {} of the Swift package build", path);
            return null;
        }
        try {
            File real = JobFiles.requireWithin(workingDir, lexical.toFile());
            if (real.isFile() && real.getName().endsWith(".modulemap")) {
                return lexical.toFile();
            }
            LOGGER.warn("Ignoring the module map {} of the Swift package build: not a module map file", path);
        } catch (IOException e) {
            LOGGER.warn("Ignoring the module map {} of the Swift package build: {}", path, e.getMessage());
        }
        return null;
    }

    /**
     * The folder at a library search path the wrapper build settings name, as Xcode spelled it, or
     * null. A folder out of the build would add libraries of the build host to the engine link.
     */
    private static File packageLibraryDir(File workingDir, List<Path> workingDirSpellings, String path) {
        Path lexical = packagePath(workingDirSpellings, path);
        if (lexical == null) {
            return null;
        }
        try {
            return JobFiles.requireWithin(workingDir, lexical.toFile()).isDirectory() ? lexical.toFile() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * A path the wrapper build settings name, normalized, if by name it is in the folder of the build
     * and is one argument of a rendered command line, or null. Package code ran in the build that
     * wrote the settings, so the path is checked by name before it is resolved: resolving an
     * arbitrary path can reach the network through an automounted folder.
     */
    private static Path packagePath(List<Path> workingDirSpellings, String path) {
        Path lexical;
        try {
            lexical = Path.of(path).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
        if (!lexical.isAbsolute() || COMMAND_LINE_SPECIAL.matcher(path).find()
                || workingDirSpellings.stream().noneMatch(lexical::startsWith)) {
            return null;
        }
        return lexical;
    }

    // Xcode names /private/tmp and /private/var by their links /tmp and /var
    private static List<Path> spellingsOf(File dir) throws IOException {
        Set<Path> spellings = new LinkedHashSet<>();
        spellings.add(dir.getAbsoluteFile().toPath().normalize());
        Path real = dir.toPath().toRealPath();
        spellings.add(real);
        Path privateDir = Path.of("/private");
        if (real.startsWith(privateDir)) {
            Path linked = Path.of("/").resolve(privateDir.relativize(real));
            if (Files.exists(linked) && linked.toRealPath().equals(real)) {
                spellings.add(linked);
            }
        }
        return new ArrayList<>(spellings);
    }

    private static final java.util.regex.Pattern MODULE_NAME_PATTERN =
        java.util.regex.Pattern.compile("module\\s+([A-Za-z0-9_]+)");
    private static final java.util.regex.Pattern UMBRELLA_PATTERN =
        java.util.regex.Pattern.compile("umbrella\\s+(?:header\\s+)?\"([^\"]+)\"");

    /**
     * Source-built packages keep their public headers in the package checkout; the umbrella
     * named by each generated module map is the only reliable pointer to them. The derived
     * -I paths (plus a symlinked module-name dir for flat layouts) are what lets extension
     * code use framework-style imports of source-built modules.
     */
    static void collectModuleMapIncludePaths(File generatedModuleMapsDir, File syntheticIncludeDir, Set<String> includePaths)
            throws IOException {
        if (!generatedModuleMapsDir.isDirectory()) {
            return;
        }
        File[] moduleMaps = generatedModuleMapsDir.listFiles((dir, name) -> name.endsWith(".modulemap"));
        if (moduleMaps == null) {
            return;
        }
        Arrays.sort(moduleMaps);
        for (File moduleMap : moduleMaps) {
            String content = new String(java.nio.file.Files.readAllBytes(moduleMap.toPath()));
            java.util.regex.Matcher nameMatcher = MODULE_NAME_PATTERN.matcher(content);
            java.util.regex.Matcher umbrellaMatcher = UMBRELLA_PATTERN.matcher(content);
            if (!nameMatcher.find() || !umbrellaMatcher.find()) {
                continue;
            }
            String moduleName = nameMatcher.group(1);
            File umbrella = new File(umbrellaMatcher.group(1));
            // umbrella can name a header file or a headers directory
            File headersDir = umbrella.isDirectory() ? umbrella : umbrella.getParentFile();
            if (headersDir == null || !headersDir.isDirectory()) {
                continue;
            }
            if (headersDir.getName().equals(moduleName) && headersDir.getParentFile() != null) {
                includePaths.add(headersDir.getParentFile().getAbsolutePath());
            }
            else {
                // the dir itself serves <SubDir/Header.h> imports whose name differs from
                // the module; the module-name symlink serves flat layouts
                includePaths.add(headersDir.getAbsolutePath());
                File link = new File(syntheticIncludeDir, moduleName);
                if (!java.nio.file.Files.isSymbolicLink(link.toPath())) {
                    syntheticIncludeDir.mkdirs();
                    java.nio.file.Files.createSymbolicLink(link.toPath(), headersDir.toPath());
                }
                includePaths.add(syntheticIncludeDir.getAbsolutePath());
            }
        }
    }

    @Override
    public List<String> getFrameworks() {
        return frameworks;
    }

    @Override
    public List<String> getFrameworksSearchPaths() {
        return frameworksSearchPaths;
    }

    @Override
    public List<String> getStaticLibraries() {
        return staticLibraries;
    }

    @Override
    public List<String> getLibrarySearchPaths() {
        return librarySearchPaths;
    }

    @Override
    public List<String> getAdditionalIncludePaths() {
        return additionalIncludePaths;
    }

    @Override
    public List<String> getSwiftModuleMaps() {
        return swiftModuleMaps;
    }

    @Override
    public List<String> getSwiftIncludePaths() {
        return swiftIncludePaths;
    }

    /** Directories outside the job the engine link reads (the building Xcode's Swift runtime libs). */
    public List<String> getSandboxReadOnlyPaths() {
        return sandboxReadOnlyPaths;
    }

    @Override
    public List<String> getLinkFlags() {
        return linkFlags;
    }

    @Override
    public String getPlatformMinVersion() {
        return platformMinVersion;
    }

    @Override
    public List<File> getResources() {
        return resources;
    }

    @Override
    public List<File> getDynamicFrameworks() {
        return dynamicFrameworks;
    }

    @Override
    public File getLockFile() {
        return lockFile;
    }

    @Override
    public List<File> getPrivacyManifests() {
        return privacyManifests;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("frameworks: " + frameworks + "\n");
        sb.append("framework search paths: " + frameworksSearchPaths + "\n");
        sb.append("system libraries: " + staticLibraries + "\n");
        sb.append("library search paths: " + librarySearchPaths + "\n");
        sb.append("include paths: " + additionalIncludePaths + "\n");
        sb.append("resource bundles: " + resources.size() + "\n");
        sb.append("dynamic frameworks: " + dynamicFrameworks.size() + "\n");
        sb.append("privacy manifests: " + privacyManifests.size() + "\n");
        sb.append("platform min version: " + platformMinVersion + "\n");
        sb.append("Package.resolved: " + lockFile);
        return sb.toString();
    }
}
