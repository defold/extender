package com.defold.extender.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SeatbeltProfileTest {

    private static Set<Path> paths(Path... entries) {
        return new LinkedHashSet<>(List.of(entries));
    }

    private static SeatbeltProfile.Grants grants(Set<Path> ro, Set<Path> rw, Set<Path> rwx, SandboxPolicy.Network network) {
        return new SeatbeltProfile.Grants(ro, rw, rwx, List.of(), network, List.of(), List.of(), Set.of(), List.of(), List.of());
    }

    private static String line(String profile, String prefix) {
        return profile.lines().filter(l -> l.startsWith(prefix)).findFirst().orElse("");
    }

    @Test
    public void baseIsDenyDefaultWithTheSystemImport(@TempDir Path root) {
        String profile = SeatbeltProfile.render(grants(Set.of(), paths(root), Set.of(), SandboxPolicy.Network.NONE));
        List<String> lines = profile.lines().toList();
        assertEquals("(version 1)", lines.get(0));
        assertEquals("(deny default)", lines.get(1));
        assertEquals("(import \"system.sb\")", lines.get(2));
        assertTrue(profile.contains("(allow file-read-metadata)"), profile);
        assertTrue(profile.contains("(allow signal (target same-sandbox))"), profile);
        assertTrue(profile.contains("(allow process-info* (target same-sandbox))"), profile);
        assertTrue(profile.contains("(literal \"/dev/tty\")"), profile);
        assertFalse(profile.contains("(allow default)"), profile);
    }

    @Test
    public void readOnlyGrantsReadAndExecButNotWrite(@TempDir Path root) throws IOException {
        Path dir = Files.createDirectory(root.resolve("ro dir"));
        Path file = Files.writeString(root.resolve("tool.jar"), "x");
        String profile = SeatbeltProfile.render(grants(paths(dir, file), Set.of(), Set.of(), SandboxPolicy.Network.NONE));

        String read = line(profile, "(allow file-read*");
        assertTrue(read.contains("(subpath \"" + dir + "\")"), read);
        assertTrue(read.contains("(literal \"" + file + "\")"), read);
        String exec = line(profile, "(allow process-exec");
        assertTrue(exec.contains("(subpath \"" + dir + "\")"), exec);
        assertTrue(exec.contains("(literal \"" + file + "\")"), exec);
        assertEquals("", line(profile, "(allow file-read* file-write*"));
    }

    @Test
    public void writableGrantsDoNotExecUnlessMarkedExec(@TempDir Path root) throws IOException {
        Path rw = Files.createDirectory(root.resolve("job"));
        Path rwx = Files.createDirectory(root.resolve("plugins"));
        Path ro = Files.createDirectory(root.resolve("usr"));
        String profile = SeatbeltProfile.render(grants(paths(ro), paths(rw), paths(rwx), SandboxPolicy.Network.NONE));

        String exec = line(profile, "(allow process-exec");
        assertTrue(exec.contains("\"" + ro + "\""), exec);
        assertTrue(exec.contains("\"" + rwx + "\""), exec);
        assertFalse(exec.contains("\"" + rw + "\""), exec);
        List<String> writes = profile.lines().filter(l -> l.startsWith("(allow file-read* file-write*")).toList();
        assertEquals(2, writes.size(), profile);
        assertTrue(writes.get(0).contains("\"" + rw + "\""), profile);
        assertTrue(writes.get(1).contains("\"" + rwx + "\""), profile);
    }

    @Test
    public void networkNoneKeepsUnixSocketsButNotTheResolver(@TempDir Path root) {
        String none = SeatbeltProfile.render(grants(Set.of(), paths(root), Set.of(), SandboxPolicy.Network.NONE));
        assertTrue(none.contains("(allow network* (local unix-socket) (remote unix-socket))"), none);
        assertTrue(none.contains("(deny network-outbound (literal \"/private/var/run/mDNSResponder\"))"), none);
        assertFalse(none.contains("(system-network)"), none);

        String all = SeatbeltProfile.render(grants(Set.of(), paths(root), Set.of(), SandboxPolicy.Network.ALL));
        assertTrue(all.contains("(system-network)\n(allow network*)\n"), all);
        assertFalse(all.contains("mDNSResponder"), all);
    }

    @Test
    public void patternsServicesAndDeniesAreRendered(@TempDir Path root) throws IOException {
        Path job = Files.createDirectory(root.resolve("job"));
        Path keychains = Files.createDirectory(root.resolve("Keychains"));
        SeatbeltProfile.Grants grants = new SeatbeltProfile.Grants(Set.of(), paths(job), Set.of(),
                List.of("^/private/var/folders/[^/]+/[^/]+/T/xcrun_db"), SandboxPolicy.Network.ALL,
                List.of("com.apple.lsd.mapdb", "com.apple.FSEvents"), List.of("com.apple.dt.Xcode"),
                paths(keychains), List.of("/usr/bin/sudo", "/usr/bin/security"), List.of("(allow sysctl-write)", " "));
        String profile = SeatbeltProfile.render(grants);

        assertTrue(profile.contains("(allow mach-lookup (global-name \"com.apple.lsd.mapdb\") (global-name \"com.apple.FSEvents\"))"), profile);
        assertTrue(profile.contains("(allow user-preference-read (preference-domain \"com.apple.dt.Xcode\"))"), profile);
        assertTrue(line(profile, "(allow file-read* file-write*").contains("(regex #\"^/private/var/folders/[^/]+/[^/]+/T/xcrun_db\")"), profile);
        assertTrue(profile.contains("(allow sysctl-write)\n"), profile);
        List<String> lines = profile.lines().toList();
        assertEquals("(deny process-exec (literal \"/usr/bin/sudo\") (literal \"/usr/bin/security\"))", lines.get(lines.size() - 1));
        assertEquals("(deny file* (subpath \"" + keychains + "\"))", lines.get(lines.size() - 2));
        assertTrue(profile.indexOf("(allow sysctl-write)") < profile.indexOf("(deny file*"), profile);
    }

    @Test
    public void stringsAreQuotedForScheme() {
        assertEquals("\"/a/b\"", SeatbeltProfile.quote("/a/b"));
        assertEquals("\"/a \\\"b\\\"/c\\\\d\"", SeatbeltProfile.quote("/a \"b\"/c\\d"));
        assertEquals("#\"^/x/y\\.\"", SeatbeltProfile.rawRegex("^/x/y\\."));
        assertThrows(IllegalArgumentException.class, () -> SeatbeltProfile.rawRegex("^/x\"y"));
        assertEquals("/a\\.b/c\\[1\\]/d\\+e", SeatbeltProfile.regexQuote("/a.b/c[1]/d+e"));
    }
}
