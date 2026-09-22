package com.defold.extender.process;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Renders the macOS Seatbelt profile (SBPL) the darwin launcher hands to
 * {@code /usr/bin/sandbox-exec -p}. Pure text generation; the only I/O is a directory check
 * to pick {@code subpath} (directories) over {@code literal} (files).
 *
 * The profile is an allowlist over a {@code (deny default)} base. Rules are evaluated in order
 * and the last matching rule wins, so the deny block for secrets is emitted last and overrides
 * any broader grant above it. Every path must be a real path ({@code /private/tmp}, not
 * {@code /tmp}): Seatbelt matches the resolved vnode path, not the spelling the tool used.
 */
final class SeatbeltProfile {

    /** Everything the profile grants, already resolved to real paths by the caller. */
    record Grants(Set<Path> readOnly,
                  Set<Path> readWrite,
                  Set<Path> readWriteExec,
                  List<String> readWritePatterns,
                  SandboxPolicy.Network network,
                  List<String> machServices,
                  List<String> preferenceDomains,
                  Set<Path> denyPaths,
                  List<String> denyExecPaths,
                  List<String> extraRules) {}

    private SeatbeltProfile() {}

    static String render(Grants grants) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("(version 1)\n");
        sb.append("(deny default)\n");
        // dyld bootstrap, cfprefsd/logd/opendirectory/trustd lookups, /System, /usr/lib, /usr/share,
        // sysctl-read, the standard device nodes
        sb.append("(import \"system.sb\")\n");
        // stat()/realpath() on any path (as Apple's bsd.sb); reading data stays governed below
        sb.append("(allow file-read-metadata)\n");
        sb.append("(allow process-fork)\n");
        sb.append("(allow ipc-posix*)\n");
        // signals and process introspection only within the command's own sandbox: the
        // server JVM is out of reach
        sb.append("(allow signal (target same-sandbox))\n");
        sb.append("(allow process-info* (target same-sandbox))\n");
        // No pty access: SBPL cannot name the slave a command allocates for itself, so any
        // /dev/ttys rule wide enough to cover it also covers the terminals of the operator's
        // other sessions (same uid, crw--w----). Nothing on the build paths needs one.

        if (!grants.machServices().isEmpty()) {
            sb.append("(allow mach-lookup");
            for (String service : grants.machServices()) {
                sb.append(" (global-name ").append(quote(service)).append(')');
            }
            sb.append(")\n");
        }
        if (!grants.preferenceDomains().isEmpty()) {
            sb.append("(allow user-preference-read (preference-domain");
            for (String domain : grants.preferenceDomains()) {
                sb.append(' ').append(quote(domain));
            }
            sb.append("))\n");
        }

        if (!grants.readOnly().isEmpty()) {
            sb.append("(allow file-read*").append(pathFilters(grants.readOnly())).append(")\n");
        }
        if (!grants.readOnly().isEmpty() || !grants.readWriteExec().isEmpty()) {
            sb.append("(allow process-exec").append(pathFilters(grants.readOnly()))
                    .append(pathFilters(grants.readWriteExec())).append(")\n");
        }
        if (!grants.readWrite().isEmpty() || !grants.readWritePatterns().isEmpty()) {
            sb.append("(allow file-read* file-write*").append(pathFilters(grants.readWrite()));
            for (String pattern : grants.readWritePatterns()) {
                sb.append(" (regex ").append(rawRegex(pattern)).append(')');
            }
            sb.append(")\n");
        }
        if (!grants.readWriteExec().isEmpty()) {
            sb.append("(allow file-read* file-write*").append(pathFilters(grants.readWriteExec())).append(")\n");
        }

        if (grants.network() == SandboxPolicy.Network.ALL) {
            // SystemConfiguration/reachability plumbing from system.sb, then the sockets themselves
            sb.append("(system-network)\n");
            sb.append("(allow network*)\n");
        } else {
            // unix sockets only (as the Linux launcher), minus the resolver socket: with no
            // network a DNS lookup would still carry data out through mDNSResponder
            sb.append("(allow network* (local unix-socket) (remote unix-socket))\n");
            sb.append("(deny network-outbound (literal \"/private/var/run/mDNSResponder\"))\n");
            // Per-session agent sockets (ssh-agent, and gpg-agent when forwarded) live in the
            // launchd per-session directories, which are under /private/var/run for a login
            // session and /private/tmp for some daemon contexts. The variables naming them are
            // scrubbed by env-deny-patterns; this stops a hardcoded path as well. Verified: with
            // this rule `ssh-add -l` under a Network.NONE profile fails with EPERM instead of
            // listing the operator's keys.
            sb.append("(deny network-outbound (regex ")
                    .append(rawRegex("^/private/(var/run|tmp)/com\\.apple\\.launchd\\.")).append("))\n");
        }

        for (String rule : grants.extraRules()) {
            if (rule != null && !rule.isBlank()) {
                sb.append(rule.strip()).append('\n');
            }
        }

        // last so that they win over every grant above
        if (!grants.denyPaths().isEmpty()) {
            sb.append("(deny file*").append(pathFilters(grants.denyPaths())).append(")\n");
            // connecting to a unix socket is network-outbound, not file*, so ~/.gnupg/S.gpg-agent
            // and the like need denying in both vocabularies
            sb.append("(deny network-outbound").append(pathFilters(grants.denyPaths())).append(")\n");
        }
        if (!grants.denyExecPaths().isEmpty()) {
            sb.append("(deny process-exec");
            for (String path : grants.denyExecPaths()) {
                sb.append(" (literal ").append(quote(path)).append(')');
            }
            sb.append(")\n");
        }
        return sb.toString();
    }

    private static String pathFilters(Collection<Path> paths) {
        StringBuilder sb = new StringBuilder();
        for (Path path : paths) {
            sb.append(Files.isDirectory(path) ? " (subpath " : " (literal ").append(quote(path.toString())).append(')');
        }
        return sb.toString();
    }

    /** A Scheme string literal. */
    static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.append('"').toString();
    }

    /** The raw-string regex form {@code #"..."}, which takes no escapes and therefore no quotes. */
    static String rawRegex(String pattern) {
        if (pattern.indexOf('"') >= 0) {
            throw new IllegalArgumentException("Seatbelt regex patterns cannot contain a double quote: " + pattern);
        }
        return "#\"" + pattern + "\"";
    }

    /** Escapes a literal path for use inside a regex pattern. */
    static String regexQuote(String literal) {
        StringBuilder sb = new StringBuilder(literal.length() + 8);
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if ("\\.[]{}()*+?^$|".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
