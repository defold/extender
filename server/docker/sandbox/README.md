# extender-sandbox (Linux)

Unprivileged launcher that confines every subprocess the Extender server starts inside a
builder container (the macOS standalone builders use the Seatbelt launcher in
[`server/scripts/standalone/sandbox/`](../../scripts/standalone/sandbox/README.md) instead) (compilers, linkers, `emcc`, Gradle, `dotnet`, R8, the manifest merge tool,
wine). The server wraps each command as

```
extender-sandbox --job /tmp/job123 --ro /usr --ro /opt ... --rw /tmp/job123 ... --net none --strict -- clang++ ...
```

and treats the launcher like the command itself: same stdin/stdout/stderr, same working
directory, exit code passed through (128+signal when the command died from a signal).

## What it enforces

| Layer | Mechanism | Effect |
|---|---|---|
| Filesystem | Landlock (kernel >= 5.13) | Only `--ro` (read + execute), `--rw` (read/write, no execute, no device nodes) and `--rwx` paths exist for the command; everything else is `EACCES`. A `--rw`/`--rwx` path inside `--job` is refused (exit 127) when the descriptor it opens resolves outside that directory: the job can replace anything in it with a link. Landlock also denies ptrace and `/proc/<pid>/fd` access to processes outside the sandbox. On kernel >= 6.7 TCP bind/connect is denied at the LSM level too; on >= 6.12 the command cannot signal processes outside the sandbox or reach their abstract unix sockets. |
| Network | seccomp BPF | With `--net none`, `socket()` fails with `EAFNOSUPPORT` for every family except `AF_UNIX` (needed by wineserver, MSBuild, Python multiprocessing). `--net all` lifts only this rule. `connect()` itself is not filtered: an AF_UNIX socket, once created, can still reach any *filesystem-path* socket the command has ordinary Unix permission to reach - see the operator note below. |
| Dangerous syscalls | seccomp BPF | `ptrace`, `process_vm_*`, `mount`/`umount`, `unshare`, `setns`, `pivot_root`, `chroot`, keyring, `bpf`, `perf_event_open`, `userfaultfd`, `io_uring_setup`, module and kexec calls return `EPERM`. The filter covers x86_64, aarch64 (Rosetta, arm64 images) and i386 (wine's 32-bit helpers); other ABIs get `EPERM`, never `SIGKILL`. |
| Resources | rlimits | `--cpu` (RLIMIT_CPU), `--nproc`, `--fsize`, `--nofile`; `0` or absent = unlimited. |
| Lifecycle | subreaper parent | The parent stays outside the sandbox. It kills the command's whole process tree when the command exits or when it receives SIGTERM/SIGINT/SIGHUP, including daemonised escapees that got reparented to it. Everything below it is stopped with SIGSTOP, sweep after sweep until nothing is left running, before the SIGKILL, so a chain that forks and exits cannot stay ahead of the teardown. |

`--strict` makes a missing Landlock or seccomp a hard failure (exit 127); without it the
launcher degrades silently, which is what the server does in non-strict mode after logging the
`--probe` result once at startup.

`--probe` prints `landlock_abi=<n> seccomp=<yes|no>` (`landlock_abi=0` = unavailable; the server's
strict mode needs ABI 3+, where `truncate(2)` is mediated).
`--check-sockets` reports which socket families can be created, for self-tests.

## Why not bubblewrap / nsjail / firejail

Docker's default seccomp profile allows the Landlock and seccomp syscalls unconditionally but
requires `CAP_SYS_ADMIN` for `clone` with namespace flags, `unshare`, `setns` and `mount`.
Namespace-based sandboxes therefore cannot run inside the builders without weakening the
container boundary, while this launcher needs no capabilities at all.

## Building

The file has no dependencies beyond a C compiler and libc; it is compiled statically in the
build stage of `Dockerfile.base-env` and installed as `/usr/local/bin/extender-sandbox`.
Landlock/seccomp constants are declared locally so the headers of the build image do not
matter; the ABI is detected at run time and unsupported rights are masked.

```
gcc -O2 -Wall -Wextra -static -o extender-sandbox extender-sandbox.c
```

## Testing

`selftest.sh` runs the launcher through the checks that matter (granted vs unlisted paths,
writes, exec from a writable directory, socket families, exit codes, tree cleanup, SIGTERM,
RLIMIT_FSIZE). It needs a Linux kernel with Landlock, e.g.

```
docker run --rm <extender-base-env image> sh /usr/local/share/extender-sandbox/selftest.sh
```

It must run natively: under Rosetta (a `linux/amd64` image on an Apple Silicon Docker Desktop)
`--probe` reports `landlock_abi=0 seccomp=no` because Rosetta does not pass those syscalls
through, and the self-test exits 2. Use the arm64 build of the image there, or a Linux host.

## Notes for operators
* A Landlock rule on a single file is accepted by the kernel but grants nothing on 9p mounts,
  which is what Docker Desktop on Windows uses for bind mounts (`/app`, `/etc/extender/apps`);
  directory rules on the same mount work. Tools named by `read-only-env-variables` that point
  at a file (`MANIFEST_MERGE_TOOL`) are therefore granted through their directory, and the
  launcher warns `landlock rule for <file> was accepted but does not grant access` when a
  file rule it was given turns out to be dead. ext4/overlay-backed hosts and CI are unaffected.


* Landlock is an allowlist with no deny rules: never mount a secret under a granted path.
  The server grants an enumerated `/etc` subset for that reason (`/etc/extender/credentials`
  and `/etc/defold/users` are not granted).
* Paths that do not exist are skipped silently, so one list can serve every image.
* wineserver runs inside the sandbox of the wine command that started it and is killed with
  it. Open issue: every job shares one `WINEPREFIX`, so a concurrent wine command connects to
  that same wineserver, is served under the first command's grants, and loses it when that
  command ends. A persistent wineserver outside the sandbox is no answer either: it would open files on
  behalf of sandboxed clients.
* `--net none` denies creating any socket except AF_UNIX (wineserver, MSBuild, Python
  multiprocessing all need it), but neither Landlock nor seccomp restrict what an AF_UNIX
  socket can then `connect()` to. Landlock's unix-socket scoping (ABI >= 6) covers only the
  *abstract* namespace; a socket bound to a filesystem path is unrestricted at every ABI this
  launcher supports, and seccomp cannot inspect `connect()`'s target (the address is a pointer
  into the command's own memory, which a BPF filter cannot dereference). So a command can reach
  any filesystem-path AF_UNIX socket it has ordinary Unix DAC permission to read, independent of
  `--net none` and of any `--ro`/`--rw`/`--rwx` grant. This is not something the launcher can
  close on its own: never mount a host socket (`docker.sock`, an SSH/GPG agent's socket, a
  desktop session's `XDG_RUNTIME_DIR`) into a builder container or its job directories. The
  server additionally strips the well-known variables that would tell a build where to find one
  (`env-deny-patterns` in `application.yml`), but that only stops a build from being *told* -
  one that already knows or guesses a well-known path is not stopped by it.
