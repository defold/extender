#!/bin/sh
# Exercises extender-sandbox on a Linux host with Landlock + seccomp (any user, no capabilities).
#
#   docker run --rm <extender-base-env image> sh /usr/local/share/extender-sandbox/selftest.sh
#   sh selftest.sh /path/to/extender-sandbox
#
# Exit 0 = all checks passed, 1 = a check failed, 2 = the kernel offers no Landlock/seccomp.
set -u

SB=${1:-/usr/local/bin/extender-sandbox}
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
    *landlock_abi=0*|*seccomp=no*)
        echo "kernel lacks Landlock or seccomp; the self-test needs both" >&2
        exit 2
        ;;
esac

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# Same shape as the server's toolchain policy: system tree read-only (including /proc, which
# emulation layers such as Rosetta need for /proc/self/exe), one job dir writable.
RO="--ro /usr --ro /lib --ro /lib64 --ro /bin --ro /proc --ro /etc/ld.so.cache --ro /etc/passwd"
run() {
    # shellcheck disable=SC2086
    "$SB" $RO --rw "$tmp" --rw /dev --net none --nproc 4096 --nofile 1024 --strict -- "$@"
}

run_net() {
    # shellcheck disable=SC2086
    "$SB" $RO --rw "$tmp" --rw /dev --net all --strict -- "$@"
}

# 1. granted read-only path is readable
if run cat /etc/passwd >/dev/null 2>&1; then pass "read of granted path"; else fail "read of granted path"; fi

# 2. an unlisted path is invisible, even though the caller could read it
if run cat /etc/hostname >/dev/null 2>&1; then fail "read of unlisted /etc/hostname succeeded"; else pass "unlisted path denied"; fi

# 3. listing a directory that is not granted (only a subtree of it is) is denied
if run ls /tmp >/dev/null 2>&1; then fail "listing /tmp succeeded"; else pass "listing ungranted directory denied"; fi

# 4. writes inside the job dir work, including rename between subdirectories (REFER)
if run sh -c "mkdir -p '$tmp/a' '$tmp/b' && echo hi > '$tmp/a/f' && mv '$tmp/a/f' '$tmp/b/f' && cat '$tmp/b/f'" 2>/dev/null | grep -q hi; then
    pass "write and rename inside job dir"
else
    fail "write and rename inside job dir"
fi

# 5. writes to a read-only tree are denied
if run sh -c "echo x > /usr/extender-sandbox-selftest" 2>/dev/null; then fail "write into /usr succeeded"; rm -f /usr/extender-sandbox-selftest; else pass "write into read-only tree denied"; fi

# 6. no exec from the writable job dir
cp /bin/true "$tmp/true-copy" 2>/dev/null || cp "$(command -v true)" "$tmp/true-copy"
if run "$tmp/true-copy" 2>/dev/null; then fail "exec from writable job dir succeeded"; else pass "exec from writable dir denied"; fi

# 7. sockets: only AF_UNIX under --net none, everything under --net all
out=$(run "$SB" --check-sockets 2>&1)
case "$out" in
    "unix=ok inet=EAFNOSUPPORT inet6=EAFNOSUPPORT") pass "net none: $out" ;;
    *) fail "net none: $out" ;;
esac
out=$(run_net "$SB" --check-sockets 2>&1)
case "$out" in
    "unix=ok inet=ok inet6=ok") pass "net all: $out" ;;
    *) fail "net all: $out" ;;
esac

# 8. exit codes propagate
run sh -c "exit 7"; rc=$?
if [ "$rc" -eq 7 ]; then pass "exit code propagated"; else fail "exit code was $rc, expected 7"; fi

# 9. a background process does not outlive the command
# (the bracketed pattern keeps this script's own command lines from matching)
sleeping() {
    for p in /proc/[0-9]*/cmdline; do tr '\0' ' ' < "$p" 2>/dev/null; echo; done | grep -q 'sleep 3[0]0'
}
run sh -c "sleep 300 </dev/null >/dev/null 2>&1 & echo started" >/dev/null 2>&1
sleep 1
if sleeping; then fail "background sleep survived the command"; else pass "process tree cleaned up"; fi

# 10. SIGTERM to the launcher kills the tree (the launcher itself is backgrounded, not a
#     shell function, so $! is its pid)
# shellcheck disable=SC2086
"$SB" $RO --rw "$tmp" --rw /dev --net none --strict -- sleep 300 &
launcher=$!
sleep 1
kill -TERM "$launcher"
wait "$launcher"; rc=$?
sleep 1
if sleeping; then fail "sleep survived SIGTERM to the launcher"; else pass "SIGTERM kills the tree (launcher exit $rc)"; fi

# 11. RLIMIT_FSIZE is enforced
if run sh -c "dd if=/dev/zero of='$tmp/big' bs=1k count=8 2>/dev/null"; then :; fi
# shellcheck disable=SC2086
if "$SB" $RO --rw "$tmp" --rw /dev --fsize 4096 --strict -- sh -c "dd if=/dev/zero of='$tmp/big2' bs=1k count=8 2>/dev/null"; then
    fail "file larger than RLIMIT_FSIZE was written"
else
    pass "RLIMIT_FSIZE enforced"
fi

# 12. ptrace is refused inside the sandbox (strace-like tools are not part of a build)
if command -v strace >/dev/null 2>&1; then
    if run strace -o /dev/null true 2>/dev/null; then fail "ptrace allowed"; else pass "ptrace denied"; fi
fi

if [ "$failures" -eq 0 ]; then
    echo "extender-sandbox self-test: all checks passed"
    exit 0
fi
echo "extender-sandbox self-test: $failures check(s) failed" >&2
exit 1
