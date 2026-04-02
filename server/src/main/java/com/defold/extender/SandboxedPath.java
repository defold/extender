package com.defold.extender;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Centralized path validation utility that prevents directory traversal attacks.
 * All user-controlled data that becomes a filesystem path should be validated
 * through this class before use.
 */
public final class SandboxedPath {

    private static final Pattern NAME_RE = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_-]{0,127}$");

    private SandboxedPath() {}

    /**
     * Resolves a user-supplied relative path within a root directory.
     * Rejects absolute paths, ".." escapes, and symlinks pointing outside root.
     *
     * @param root      the sandbox root directory
     * @param childPath the user-supplied relative path
     * @return a validated File guaranteed to be within root
     * @throws ExtenderException if the path escapes the root directory
     */
    public static File resolve(File root, String childPath) throws ExtenderException {
        if (childPath == null || childPath.isEmpty()) {
            throw new ExtenderException("Path must not be null or empty");
        }

        // Reject absolute paths
        Path childAsPath = Path.of(childPath);
        if (childAsPath.isAbsolute()) {
            throw new ExtenderException(
                String.format("Absolute paths are not allowed: '%s'", childPath));
        }

        // Resolve and normalize
        Path rootPath = root.toPath().toAbsolutePath().normalize();
        Path resolved = rootPath.resolve(childPath).normalize();

        // Verify the normalized path is within the root
        if (!resolved.startsWith(rootPath)) {
            throw new ExtenderException(
                String.format("Path traversal detected: '%s' escapes root directory", childPath));
        }

        // For existing files, also verify via real path (resolves symlinks)
        File resolvedFile = resolved.toFile();
        if (resolvedFile.exists()) {
            try {
                Path realRoot = root.toPath().toRealPath();
                Path realResolved = resolvedFile.toPath().toRealPath();
                if (!realResolved.startsWith(realRoot)) {
                    throw new ExtenderException(
                        String.format("Symlink escape detected: '%s' resolves outside root directory", childPath));
                }
            } catch (IOException e) {
                throw new ExtenderException(
                    String.format("Failed to resolve real path for '%s': %s", childPath, e.getMessage()));
            }
        } else {
            // For non-existent files, walk up to the nearest existing ancestor
            // and verify it resolves within root
            Path current = resolved.getParent();
            while (current != null && !current.toFile().exists()) {
                current = current.getParent();
            }

            Path existedRoot = rootPath;
            while (existedRoot != null && !existedRoot.toFile().exists()) {
                existedRoot = existedRoot.getParent();
            }
            if (current != null && existedRoot != null) {
                try {
                    Path realAncestor = current.toRealPath();
                    Path realRoot = existedRoot.toRealPath();
                    if (!realAncestor.startsWith(realRoot) && !realAncestor.equals(realRoot)) {
                        throw new ExtenderException(
                            String.format("Symlink escape detected: ancestor of '%s' resolves outside root directory", childPath));
                    }
                } catch (IOException e) {
                    throw new ExtenderException(
                        String.format("Failed to resolve real path for ancestor of '%s': %s", childPath, e.getMessage()));
                }
            }
        }

        return resolvedFile;
    }

    /**
     * Asserts that an already-constructed File is within the given root directory.
     *
     * @param root   the sandbox root directory
     * @param target the file to validate
     * @throws ExtenderException if target is outside root
     */
    public static void assertWithin(File root, File target) throws ExtenderException {
        Path rootPath = root.toPath().toAbsolutePath().normalize();
        Path targetPath = target.toPath().toAbsolutePath().normalize();

        if (!targetPath.startsWith(rootPath)) {
            throw new ExtenderException(
                String.format("Path '%s' is outside the allowed directory '%s'", target.getPath(), root.getPath()));
        }

        // For existing files, also check via real path to catch symlink escapes
        if (target.exists() && root.exists()) {
            try {
                Path realRoot = root.toPath().toRealPath();
                Path realTarget = target.toPath().toRealPath();
                if (!realTarget.startsWith(realRoot)) {
                    throw new ExtenderException(
                        String.format("Symlink escape detected: '%s' resolves outside '%s'", target.getPath(), root.getPath()));
                }
            } catch (IOException e) {
                throw new ExtenderException(
                    String.format("Failed to resolve real path: %s", e.getMessage()));
            }
        }
    }

    /**
     * Validates a name suitable for use as a single filename component.
     * Must start with a letter, contain only alphanumeric characters, underscores, or hyphens,
     * and be at most 128 characters.
     *
     * @param name the name to validate
     * @return the validated name (unchanged)
     * @throws ExtenderException if the name is invalid
     */
    public static String validateName(String name) throws ExtenderException {
        if (name == null || name.isEmpty()) {
            throw new ExtenderException("Name must not be null or empty");
        }
        if (!NAME_RE.matcher(name).matches()) {
            throw new ExtenderException(
                String.format("Invalid name '%s': must start with a letter, contain only [a-zA-Z0-9_-], and be at most 128 characters", name));
        }
        return name;
    }
}
