package com.defold.extender.process;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The per-user locations macOS tools use regardless of {@code $HOME} and {@code $TMPDIR}:
 * Xcode and SwiftPM resolve the home through getpwuid, and Foundation, xcrun and the Xcode
 * build service use the darwin user temp/cache directories from confstr(3). Java exposes the
 * temp one as {@code java.io.tmpdir}; the cache one is its {@code C} sibling.
 */
public final class DarwinSandboxPaths {
    private DarwinSandboxPaths() {}

    /** The server user's real home directory. */
    public static Path realHome() {
        return Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
    }

    /** {@code /private/var/folders/xx/yyyy/T}, resolved. */
    public static Path userTempDir() {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        try {
            return tmp.toRealPath();
        } catch (IOException e) {
            return tmp;
        }
    }

    /** {@code /private/var/folders/xx/yyyy/C}, the sibling of {@link #userTempDir()}. */
    public static Path userCacheDir() {
        return userTempDir().resolveSibling("C");
    }

    /** A regex over real paths matching {@code <user temp dir>/<suffix regex>}. */
    public static String userTempPattern(String suffixRegex) {
        return "^" + SeatbeltProfile.regexQuote(userTempDir().toString()) + "/" + suffixRegex;
    }
}
