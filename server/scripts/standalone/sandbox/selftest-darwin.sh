#!/bin/sh
# Exercises the macOS extender-sandbox launcher on this host (any user, no privileges).
#
#   sh selftest-darwin.sh /path/to/extender-sandbox
#
# Exit 0 = all checks passed, 1 = a check failed, 2 = Seatbelt (sandbox-exec) is unavailable.
set -u

SB=${1:-$(dirname "$0")/../../../app/extender-sandbox}
failures=0

fail() {
    echo "FAIL: $*" >&2
    failures=$((failures + 1))
}

pass() {
    echo "ok: $*"
}

probe=$("$SB" --probe) || { echo "probe failed"; exit 2; }
echo "probe: $probe"
case "$probe" in
    *seatbelt=no*)
        echo "Seatbelt is unavailable on this host; the self-test needs /usr/bin/sandbox-exec" >&2
        exit 2
        ;;
esac

# Seatbelt matches real paths: /var/folders is /private/var/folders
tmp=$(mktemp -d)
tmp=$(cd "$tmp" && pwd -P)
sbdir=$(cd "$(dirname "$SB")" && pwd -P)
trap 'rm -rf "$tmp"' EXIT

# Same shape as the server's toolchain profile (SeatbeltProfile.java): system trees read-only
# and executable, one job dir writable, no network beyond unix sockets.
BASE='(version 1)(deny default)(import "system.sb")(allow file-read-metadata)(allow process-fork)(allow ipc-posix*)(allow signal (target same-sandbox))(allow process-info* (target same-sandbox))(allow file-read* (subpath "/usr") (subpath "/bin") (subpath "/sbin") (subpath "/System") (subpath "/Library") (subpath "/private/etc") (subpath "'"$sbdir"'"))(allow process-exec (subpath "/usr") (subpath "/bin") (subpath "/sbin") (subpath "/System") (subpath "'"$sbdir"'"))(allow file-read* file-write* (subpath "'"$tmp"'"))(deny process-exec (literal "/usr/bin/sudo") (literal "/usr/bin/security"))'
NET_NONE='(allow network* (local unix-socket) (remote unix-socket))(deny network-outbound (literal "/private/var/run/mDNSResponder"))'
NET_ALL='(system-network)(allow network*)'

run() {
    "$SB" --profile "$BASE$NET_NONE" --nproc 4096 --nofile 1024 --strict -- "$@"
}

run_net() {
    "$SB" --profile "$BASE$NET_ALL" --strict -- "$@"
}

# 1. granted read-only path is readable (through the /etc -> /private/etc symlink)
if run cat /etc/hosts >/dev/null 2>&1; then pass "read of granted path"; else fail "read of granted path"; fi

# 2. an unlisted path is invisible, even though the caller could read it
outside=$(mktemp)
echo secret > "$outside"
if run cat "$outside" >/dev/null 2>&1; then fail "read of unlisted $outside succeeded"; else pass "unlisted path denied"; fi
rm -f "$outside"

# 3. listing a directory that is not granted is denied
if run ls /tmp >/dev/null 2>&1; then fail "listing /tmp succeeded"; else pass "listing ungranted directory denied"; fi

# 4. writes inside the job dir work, including rename between subdirectories
if run sh -c "mkdir -p '$tmp/a' '$tmp/b' && echo hi > '$tmp/a/f' && mv '$tmp/a/f' '$tmp/b/f' && cat '$tmp/b/f'" 2>/dev/null | grep -q hi; then
    pass "write and rename inside job dir"
else
    fail "write and rename inside job dir"
fi

# 5. writes to a read-only tree are denied.
# Not /usr: that is on the sealed system volume, so the write fails with no sandbox at all and
# the check would pass against an unconfined launcher. $sbdir is granted read-only by BASE and
# is genuinely writable by this user, which is what makes the denial attributable to Seatbelt -
# the positive control below asserts exactly that.
ro_target="$sbdir/.extender-sandbox-selftest"
if echo x > "$ro_target" 2>/dev/null; then
    rm -f "$ro_target"
    if run sh -c "echo x > '$ro_target'" 2>/dev/null; then
        fail "write into the read-only tree $sbdir succeeded"
        rm -f "$ro_target"
    else
        pass "write into read-only tree denied"
    fi
else
    fail "control: $sbdir is not writable unsandboxed, so the read-only check proves nothing"
fi

# 6. no exec from the writable job dir
cp /usr/bin/true "$tmp/true-copy"
if run "$tmp/true-copy" 2>/dev/null; then fail "exec from writable job dir succeeded"; else pass "exec from writable dir denied"; fi

# 7. a regex grant (the darwin temp dir entries the SPM step needs) matches only what it names
mkdir -p "$tmp-x"
REGEX='(allow file-read* file-write* (regex #"^'"$tmp"'-x/foo\."))'
out=$("$SB" --profile "$BASE$REGEX$NET_NONE" --strict -- sh -c "echo hi > '$tmp-x/foo.txt' && echo yes; echo hi > '$tmp-x/bar.txt' 2>/dev/null && echo no" 2>/dev/null)
rm -rf "$tmp-x"
if [ "$out" = "yes" ]; then pass "regex grant scoped to its pattern"; else fail "regex grant: got '$out'"; fi

# 8. sockets. The unix answer is a connect to the one socket a Network.NONE profile denies by
# name (mDNSResponder), not a bare socket(AF_UNIX) - that always succeeds and would read "ok"
# with no sandbox at all.
out=$(run "$SB" --check-sockets 2>&1)
case "$out" in
    "unix=EPERM inet=EPERM inet6=EPERM") pass "net none: $out" ;;
    *) fail "net none: $out" ;;
esac
out=$(run_net "$SB" --check-sockets 2>&1)
case "$out" in
    "unix=ok inet=ok inet6=ok") pass "net all: $out" ;;
    *) fail "net all: $out" ;;
esac
# name resolution goes through mDNSResponder's unix socket, which is denied too
if run curl -sS -m 3 https://example.com >/dev/null 2>&1; then fail "DNS/network reachable under net none"; else pass "DNS denied under net none"; fi

# 9. exit codes propagate
run sh -c "exit 7"; rc=$?
if [ "$rc" -eq 7 ]; then pass "exit code propagated"; else fail "exit code was $rc, expected 7"; fi

# 10. signals to processes outside the sandbox are denied (the server JVM is out of reach)
if run sh -c "kill -0 $$" 2>/dev/null; then fail "signal to a process outside the sandbox allowed"; else pass "signal to outside process denied"; fi

# 11. a background process does not outlive the command
# (the bracketed pattern keeps this script's own command line from matching)
run sh -c "sleep 300 </dev/null >/dev/null 2>&1 & echo started" >/dev/null 2>&1
sleep 1
if pgrep -f 'sleep 3[0]0' >/dev/null; then fail "background sleep survived the command"; else pass "process tree cleaned up"; fi

# 12. SIGTERM to the launcher kills the tree
"$SB" --profile "$BASE$NET_NONE" --strict -- sleep 300 &
launcher=$!
sleep 1
kill -TERM "$launcher"
wait "$launcher"; rc=$?
sleep 1
if pgrep -f 'sleep 3[0]0' >/dev/null; then fail "sleep survived SIGTERM to the launcher"; else pass "SIGTERM kills the tree (launcher exit $rc)"; fi

# 13. RLIMIT_FSIZE is enforced
if "$SB" --profile "$BASE$NET_NONE" --fsize 4096 --strict -- sh -c "dd if=/dev/zero of='$tmp/big' bs=1k count=8 2>/dev/null" 2>/dev/null; then
    fail "file larger than RLIMIT_FSIZE was written"
else
    pass "RLIMIT_FSIZE enforced"
fi

# 14. the deny-exec list wins over the /usr/bin grant (the server lists sudo, su, security, ...)
if run /usr/bin/sudo -n true 2>/dev/null; then fail "sudo executed inside the sandbox"; else pass "privileged executables denied"; fi
if run /usr/bin/security list-keychains >/dev/null 2>&1; then fail "security executed inside the sandbox"; else pass "keychain tool denied"; fi

# 15. a descendant that detaches with setsid() leaves the process group, so the group kill misses
#     it; the tag sweep has to catch it anyway. A second command running at the same time must
#     survive untouched: it carries its own tag.
detach='if (fork() == 0) { POSIX::setsid(); open(STDIN, "</dev/null"); open(STDOUT, ">/dev/null"); open(STDERR, ">/dev/null"); exec("/bin/sleep", "31111"); }'
"$SB" --profile "$BASE$NET_NONE" --strict -- sleep 31222 >/dev/null 2>&1 &
neighbour=$!
sleep 1
run /usr/bin/perl -MPOSIX -e "$detach" >/dev/null 2>&1
sleep 1
if pgrep -f 'sleep 3[1]111' >/dev/null; then fail "setsid() descendant survived the tag sweep"; else pass "detached descendant killed by the tag sweep"; fi
if pgrep -f 'sleep 3[1]222' >/dev/null; then pass "a concurrent command is left alone by the sweep"; else fail "the sweep killed a concurrently running command"; fi
kill -TERM "$neighbour" 2>/dev/null
wait "$neighbour" 2>/dev/null
sleep 1
pgrep -f 'sleep 3[1]222' >/dev/null && kill -9 $(pgrep -f 'sleep 3[1]222') 2>/dev/null

if [ "$failures" -eq 0 ]; then
    echo "extender-sandbox self-test: all checks passed"
    exit 0
fi
echo "extender-sandbox self-test: $failures check(s) failed" >&2
exit 1
