package com.defold.extender.services;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;

import com.defold.extender.ExtenderException;

/**
 * Native dependencies resolved by a dependency manager (CocoaPods, Swift Package Manager)
 * for an Apple target. Extender consumes every implementation uniformly: the values feed
 * the ext.* template variables of the compile/link commands and the build output.
 */
public interface ResolvedNativeDeps {

    List<String> getFrameworks();

    default Set<String> getBuiltFrameworks() { return Set.of(); }

    default List<String> getWeakFrameworks() { return List.of(); }

    List<String> getFrameworksSearchPaths();

    default List<String> getStaticLibraries() { return List.of(); }

    default List<String> getLibrarySearchPaths() { return List.of(); }

    /** Include paths added when compiling the user's extension sources. */
    default List<String> getAdditionalIncludePaths() { return List.of(); }

    /** Raw additional flags for the final engine link. */
    default List<String> getLinkFlags() { return List.of(); }

    /** Minimum OS version required by the resolved dependencies, or null if not constrained. */
    String getPlatformMinVersion();

    default List<File> getResources() { return List.of(); }

    default List<File> createResourceBundles(File targetDir, String platform) throws IOException, ExtenderException { return List.of(); }

    /** Dynamically linked frameworks that must be embedded into the application bundle. */
    default List<File> getDynamicFrameworks() { return List.of(); }

    /** Podfile.lock / Package.resolved, emitted with the build output; null when absent. */
    default File getLockFile() { return null; }

    /** Privacy manifests to merge for engines that predate resource-bundle packaging. */
    default List<File> getPrivacyManifests() { return List.of(); }
}
