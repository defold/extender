# extender-sandbox (macOS)

Launcher that confines every subprocess the standalone Extender server starts on a Mac
(the `platformsdk` clang/swift toolchain, `ar`, `dsymutil`, `codesign`, `PlistBuddy`, `hmap`,
`file`, `pod`, `xcodegen`, `xcodebuild`, the manifest merge tool). The server wraps each
command as

```
extender-sandbox --profile '(version 1)(deny default)...' --nproc 4096 --strict -- clang++ ...
```

and treats the launcher like the command itself: same stdin/stdout/stderr, same working
directory, exit code passed through (128+signal when the command died from a signal).

It is the macOS counterpart of the Landlock/seccomp launcher in
[`server/docker/sandbox/`](../../../docker/sandbox/README.md); the Java side
(`ProcessSandbox`, `SandboxPolicy`, `extender.sandbox.*`) is shared, only the argv dialect
differs (`backend: seatbelt`).

## What it enforces

| Layer | Mechanism | Effect |
|---|---|---|
| Filesystem, Mach, signals | Seatbelt profile applied by `/usr/bin/sandbox-exec` | `(deny default)` plus an allowlist rendered by `SeatbeltProfile.java`: system trees and the toolchain read-only + executable, the job directory read-write (no exec), regex grants for the few per-user temp entries Apple's tools insist on, an explicit deny block (keychains, `~/.ssh`, cloud credentials, `sudo`/`security`/`launchctl`). Signals and process introspection only reach processes inside the same sandbox, so the server JVM cannot be killed. Mach service lookups are limited to what `system.sb` grants plus a per-command list. The profile is inherited by every descendant, survives setuid exec (`sudo` inside stays confined) and cannot be dropped. |
| Network | Seatbelt | No network: only unix sockets, and not even the resolver socket, so nothing leaks through DNS. Dependency resolvers (`pod`, `xcodebuild`) run with `(allow network*)`. |
| Resources | rlimits | `--cpu`, `--nproc`, `--fsize`, `--nofile` (0 or absent = untouched), clamped to the hard limit (`kern.maxprocperuid`). |
| Lifecycle | process group, then tag sweep | The parent kills the command's process group when the command exits or when it receives SIGTERM/SIGINT/SIGHUP, and then kills whatever left that group (see below). Nothing the command starts outlives it. |

`--strict` makes a missing `sandbox-exec` a hard failure (exit 127); without it the launcher
still runs the command in its process group with the rlimits, which is what the server does
in non-strict mode after logging the `--probe` result once at startup.

`--probe` prints `seatbelt=<yes|no> sandbox_exec=/usr/bin/sandbox-exec` after applying a
minimal `(deny default)` profile to `/usr/bin/true`. `--check-sockets` reports which socket
families can actually connect (Seatbelt gates `connect()`, not `socket()`), for the self-test.

## The tag sweep

A descendant that calls `setsid()` leaves the command's process group and reparents to launchd,
so `kill(-pgid)` misses it. That cannot be prevented. Under a `(deny default)` profile `setsid()`
still succeeds and moves the caller into a new process group; Seatbelt simply defines no
operation covering it (`process-setsid`, `process-session`, `process-daemonize` and similar names
are not SBPL operations at all, while `(deny process*)` does reach `setpgid`, which then fails
with EPERM), and macOS gives an unprivileged process no syscall filter. Merely reparenting is not
enough to escape, though: a double-forked grandchild whose parent exits keeps its process group,
and the group kill still reaches it. `setsid()` is the whole hole.

So the launcher marks the command instead. Before starting it, the launcher creates an empty
file with a random name under `<per-user darwin temp dir>/.extender-sbtag`
(`confstr(_CS_DARWIN_USER_TEMP_DIR)`, which the OS creates `0700` for this uid) and appends
`(allow file-read-data (literal "<that file>"))` to the profile as the last rule. On teardown it
walks the processes of its own uid and kills those whose sandbox **may read the tag file** but
**may not read the directory containing it**, asking the kernel with `sandbox_check(pid, ...)`.

Both halves matter. The first picks out this command's tree, because every other command's
profile grants a different file. The second discards anything unsandboxed or permissive: for a
process with no sandbox every check answers "allowed", which is how the server's own JVM, the
launcher and (on a developer's Mac) ordinary applications are excluded. A Seatbelt `(literal ...)`
rule only matches a path that resolves, which is why the tag is a real file and not a synthetic
one. A sandboxed process cannot re-sandbox itself, so it cannot shed the tag to hide.

The sweep freezes matches with SIGSTOP and rescans until a round finds nothing new before
sending SIGKILL, so a process still forking as its tree is torn down cannot outrun it (verified
with a detached parent spawning children throughout the teardown: 33 processes killed, none
left). With no escapee, the common case, the first round matches nothing and the whole sweep is
a single pass over the process list, which `proc_listpids(PROC_UID_ONLY)` restricts to this
uid's processes. Nothing is shared between launchers, so concurrent builds each sweep
independently.

Operator notes for the sweep:

* If the tag cannot be created, the launcher says so on stderr and, under `--strict`, refuses to
  run at all (exit 127) rather than run a command it could not fully clean up afterwards.
* The tag directory lives in the per-user temp directory, not at a fixed name in a
  world-writable one, where another local user could pre-create it (`mkdir` returns `EEXIST`
  whoever owns it) and make every `--strict` invocation fail. An existing one is accepted only
  when it is a directory owned by this uid with no group or other write bit - otherwise the
  launcher fails with a message naming the path. A tag file deleted mid-command still turns that one
  sweep into a no-op; it never turns into a wider kill.
* Never add `/private/tmp` (or `/tmp`) to `extender.sandbox.read-only-paths`. That would let
  every command read the tag directory, the second half of the check would stop discriminating,
  and the sweep would quietly match nothing for every command.

## Building

`scripts/standalone/setup-standalone-env.sh` compiles it with the command line tools into
`server/app/extender-sandbox` and runs the self-test; `envs/generate_user_env.sh` exports the
path as `EXTENDER_SANDBOX_LAUNCHERPATH`, which the `standalone-dev` profile (sandbox enabled)
picks up. By hand:

```
cc -O2 -Wall -Wextra -o extender-sandbox extender-sandbox-darwin.c
sh selftest-darwin.sh ./extender-sandbox
```

The launcher is policy-free: the server renders the profile, so a profile change never needs
a recompile. `DarwinLauncherTest` compiles and exercises it as part of the unit test suite on
macOS.

## Finding out what a tool needs

`(trace)`, `sandbox-simplify` and `(deny ... (with report))` no longer exist. Denials show up
in the unified log; keep this running in a second terminal while a build runs:

```
/usr/bin/log stream --style compact --predicate 'sender == "Sandbox"'
```

(`log` must be spelled with its full path in zsh, whose `log` builtin shadows it.) Lines look
like `Sandbox: swift-frontend(1234) deny(1) file-write-create /private/var/...`; the kernel
de-duplicates repeats per process, so re-run with a fresh process after each change. Grant a
path through `extender.sandbox.read-only-paths` / a policy, a Mach service through
`extender.sandbox.darwin.mach-services`, and anything else through
`extender.sandbox.darwin.extra-rules` (raw SBPL) until the tool is quiet.

## Notes for operators

* Seatbelt matches real paths: the server resolves every grant (`/tmp` is
  `/private/tmp`, `/etc/resolv.conf` is `/private/var/run/resolv.conf`). Job directories
  live in the per-user darwin temp dir (`/private/var/folders/<xx>/<yyy>/T`), next to
  `xcrun_db` and every other job; only the job directory itself is granted, never `T`.
* Never put a secret under a granted tree. On the production Macs the GCP logging key lives
  in `<instance>/credentials/`, a sibling of `platformsdk/` and `sdk/`: grant the subtrees,
  never the instance root. `extender.sandbox.darwin.deny-paths` is the belt to that braces.
* `xcodebuild` ignores `$HOME`: its SwiftPM databases, fingerprint store and module cache
  stay in the real home and the darwin cache dir, writable for the `xcodebuild` command only
  and shared between jobs (see `SwiftPackageManagerService.xcodebuildPolicy`).
* `xcodebuild` runs with **no network**. Swift package manifests are executed during
  resolution, so every repository of the graph is mirrored first with plain
  `git clone --mirror`/`git fetch` under a network-on policy (`gitPrefetchPolicy`,
  `GIT_ALLOW_PROTOCOL=https`) into `<spm cache>/mirrors/`, and xcodebuild's system git is
  pointed at them through a per-job `GIT_CONFIG_GLOBAL` with
  `url.file://<mirror>.insteadOf=<https url>` and `GIT_ALLOW_PROTOCOL=file`. The mirrors are
  read-only for xcodebuild. Transitive dependencies are discovered from the output of the
  rounds that could not reach them, so nothing has to be pinned by the upload. Binary target
  archives are discovered the same way and fetched by plain `curl` (network on, only
  `<packageCache>/artifacts` writable) into SwiftPM's artifact cache, which it reads before
  trying the network; the manifest checksum is verified by SwiftPM on every use.
* When Swift packages are resolved, the engine link auto-links Xcode's Swift runtime archives
  (`<DEVELOPER_DIR>/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/<platform>`), which the
  link step is granted read-only through `ResolvedPackages.getSandboxReadOnlyPaths()`.
* Xcode's build service (`SWBBuildService`) maps a sparse file larger than the default 8 GiB
  `RLIMIT_FSIZE`; the resulting SIGXFSZ surfaces only as "The Xcode build system has
  crashed", so the `xcodebuild` policy runs without a file size limit.
* A sandboxed process cannot start another Seatbelt sandbox (`sandbox_apply: Operation not
  permitted`; every `(allow …)` operation was tried, and a pure `(allow default)` inner
  profile only "works" because `sandbox-exec` skips applying it), so SwiftPM's own sandbox
  around `Package.swift` compilation is switched off for the `xcodebuild` command
  (`-IDEPackageSupportDisableManifestSandbox=YES`). That inner sandbox allowed every read
  anyway; the process sandbox plus the network-off split above is stricter. Packages with
  build plugins or macros hit the same limit in SwiftPM's plugin sandbox.
* `pod` gets `CP_CACHE_DIR=<CP_HOME_DIR>/cache` because its default download cache is under
  `$HOME`, which the sandbox points into the job directory.
* Foundation saves files atomically through `<T>/TemporaryItems/` or `<T>/<uuid>-<pid>-<hex>`
  temp files and the `/usr/bin` tool shims rewrite `<T>/xcrun_db`, whatever `TMPDIR` says
  (`hmap`, `xcodegen`, `xcrun`); `extender.sandbox.darwin.user-temp-patterns` grants exactly
  those entries. clang's implicit module cache is moved into the job with
  `CLANG_MODULE_CACHE_PATH` because its default is the per-user darwin cache dir.
