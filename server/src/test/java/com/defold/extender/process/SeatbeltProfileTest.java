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
        assertFalse(profile.contains("(allow default)"), profile);
    }

    @Test
    public void readOnlyGrantsReadAndExecButNotWrite(@TempDir Path root) throws IOException {
        Path dir = Files.createDirectory(root.resolve("ro dir"));
        Path file = Files.writeString(root.resolve("tool.jar"), "x");
        String profile = SeatbeltProfile.render(grants(paths(dir, file), Set.of(), Set.of(), SandboxPolicy.Network.NONE));

        String read = line(profile, "(allow file-read*");
        assertTrue(read.contains("(subpath " + SeatbeltProfile.quote(dir.toString()) + ")"), read);
        assertTrue(read.contains("(literal " + SeatbeltProfile.quote(file.toString()) + ")"), read);
        String exec = line(profile, "(allow process-exec");
        assertTrue(exec.contains("(subpath " + SeatbeltProfile.quote(dir.toString()) + ")"), exec);
        assertTrue(exec.contains("(literal " + SeatbeltProfile.quote(file.toString()) + ")"), exec);
        assertEquals("", line(profile, "(allow file-read* file-write*"));
    }

    @Test
    public void writableGrantsDoNotExecUnlessMarkedExec(@TempDir Path root) throws IOException {
        Path rw = Files.createDirectory(root.resolve("job"));
        Path rwx = Files.createDirectory(root.resolve("plugins"));
        Path ro = Files.createDirectory(root.resolve("usr"));
        String profile = SeatbeltProfile.render(grants(paths(ro), paths(rw), paths(rwx), SandboxPolicy.Network.NONE));

        String exec = line(profile, "(allow process-exec");
        assertTrue(exec.contains(SeatbeltProfile.quote(ro.toString())), exec);
        assertTrue(exec.contains(SeatbeltProfile.quote(rwx.toString())), exec);
        assertFalse(exec.contains(SeatbeltProfile.quote(rw.toString())), exec);
        List<String> writes = profile.lines().filter(l -> l.startsWith("(allow file-read* file-write*")).toList();
        assertEquals(2, writes.size(), profile);
        assertTrue(writes.get(0).contains(SeatbeltProfile.quote(rw.toString())), profile);
        assertTrue(writes.get(1).contains(SeatbeltProfile.quote(rwx.toString())), profile);
    }

    @Test
    public void theRendererEmitsNoPtyRule(@TempDir Path root) {
        String profile = SeatbeltProfile.render(grants(Set.of(), paths(root), Set.of(), SandboxPolicy.Network.NONE));
        // A /dev/ttys rule wide enough to cover the command's own pty also covers the
        // operator's other sessions, so the renderer emits none - and with it goes the only
        // file-ioctl grant in the profile. /dev/ptmx may still arrive through
        // read-write-paths, but read/write without ioctl is useless as a pty master.
        assertFalse(profile.contains("/dev/ttys"), profile);
        assertFalse(profile.contains("file-ioctl"), profile);
    }

    @Test
    public void agentSocketsAreDeniedUnderNetworkNone(@TempDir Path root) {
        String profile = SeatbeltProfile.render(grants(Set.of(), paths(root), Set.of(), SandboxPolicy.Network.NONE));
        // unix sockets stay allowed, but not the launchd directories holding ssh-agent's
        assertTrue(profile.contains("(deny network-outbound (regex #\"^/private/(var/run|tmp)/com\\.apple\\.launchd\\.\"))"), profile);
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
        // a unix socket under a denied path is reached with network-outbound, not file*
        assertEquals("(deny network-outbound (subpath " + SeatbeltProfile.quote(keychains.toString()) + "))", lines.get(lines.size() - 2));
        assertEquals("(deny file* (subpath " + SeatbeltProfile.quote(keychains.toString()) + "))", lines.get(lines.size() - 3));
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
