# Security

The default configuration for Extender is to allow builds for all platforms and from any user. This is the configuration of `https://build.defold.com`. It is also possible to restrict access to certain users and to certain platforms.

## Configuration
Extender uses the following Spring Boot application variables to configure authentication and platform availability:

* `extender.authentication.platforms` - Comma separated list of platforms where access should be restricted.
* `extender.authentication.users` - File resource with list of users and the platforms each user has access to.

The default values can be seen in [`application.yml`](/server/src/main/resources/application.yml). The server is secured using Spring Security in [WebSecurityConfiguration.java](/server/src/main/java/com/defold/extender/WebSecurityConfig.java).

### Platforms
Comma separated list of platforms to which access should be restricted. Available platform names:

* windows
* linux
* macos
* android
* ios
* html5
* switch

You can either define the platforms in `application.yml` or by passing the `extender.authentication.platforms` environment variable when starting Docker.

Example using `application.yml`:

```
extender:
    authentication:
        platforms: windows,linux,macos
```

The same by passing an environment variable when launching Docker:

```
docker run ... -e extender.authentication.platforms=windows,linux,macos extender/extender;
```

### Users
Users are listed in Java properties format with one username per line followed by the user password, user roles (platform access) and user status ("enabled" or "disabled"). Example:

```
bob = password1,ROLE_WINDOWS,ROLE_LINUX,ROLE_MACOS,enabled
may = password2,ROLE_MACOS,enabled
```

This defines two users: "bob" and "may". Bob has permission to create Windows, Linux and macOS builds even when the Extender configuration has restricted access to these platforms (through `extender.authentication.platforms` as seen above). May on the other hand has only access to macOS.

The users can either be defined in a text file in `extender/server/users` or as a resource returned from a URL. You specify where the users are defined in the `extender.authentication.users` property of `application.yml` or by passing the `extender.authentication.users` environment variable when starting Docker.

The user definitions are updated at regular intervals so that you can add or modify users at runtime. The interval at which the user definitions are updated is defined in the `extender.authentication.update-interval` property of `application.yml`:

```
extender:
    authentication:
        update-interval: 900000
```


#### Using a file
Example using `application.yml` where users are loaded from the file `users/myusers.txt`:

```
extender:
    authentication:
        users: file:users/myusers.txt
```

The same by passing an environment variable when launching Docker:

```
docker run ... -e extender.authentication.users=file:users/myusers.txt extender/extender;
```

#### Using a URL
Example using `application.yml` where users are loaded from the content served (using HTTP GET) from `https://www.mysite.com/extender-users`:

```
extender:
    authentication:
        users: https://www.mysite.com/extender-users
```

The same by passing an environment variable when launching Docker:

```
docker run ... -e extender.authentication.users=https://www.mysite.com/extender-users extender/extender;
```



## Authentication
Authentication is performed using standard Basic access authentication. The authentication data can be sent as an `Authorization` request header, but that is inconvenient when using the command line tools (bob.jar) or the Defold editor. The username and password can be sent as part of the build server URL set in the Preferences window of the editor and using the `--build-server` option to bob.jar:

    java -jar bob.jar --build-server https://bob:super5ecret@myextender.com

It is also possible to specify a username and password in the environment variables DM_EXTENDER_USERNAME and DM_EXTENDER_PASSWORD.

## Process sandbox

Every subprocess a builder starts (compilers, linkers, `emcc`, Gradle, `dotnet`, R8, the
manifest merge tool, wine) processes user-controlled input, and some of them execute it:
`emcc` evaluates user `--js-library` files in Node at link time and Gradle runs the Groovy in a
user's `build.gradle`. Inside the Docker builder images every such subprocess is therefore
wrapped by `/usr/local/bin/extender-sandbox` (source and details in
[`server/docker/sandbox/`](/server/docker/sandbox/README.md)), which confines it with Landlock,
seccomp and rlimits without needing any capabilities:

* **Filesystem**: an allowlist. The tool sees the system directories read-only, the Defold SDK
  read-only, its own job directory read-write, and nothing else: not other jobs, not
  `/var/extender/results` or the caches, not the server configuration or credential mounts.
* **Network**: off. `socket()` fails for every address family except `AF_UNIX`. Only the two
  dependency resolvers (Gradle, NuGet restore for C#) run with network.
* **Environment**: rebuilt for each command. Variables matching `env-deny-patterns`
  (tokens, secrets, `GOOGLE_APPLICATION_CREDENTIALS`, ...) are never inherited; `HOME`,
  `TMPDIR` and clang's module cache point into the job directory.
* **Lifecycle**: a wall-clock timeout per command, and the whole process tree is killed when the
  command ends, so nothing outlives a build.

Configuration (`extender.sandbox.*` in `application.yml`; environment variables such as
`EXTENDER_SANDBOX_ENABLED` override them as usual):

* `enabled` - off by default; `true` in the `local-dev` profiles used by the Docker images and
  in `standalone-dev` on macOS. With `enabled: true` the server refuses to start if the
  launcher is missing.
* `backend` - `landlock`, `seatbelt` or `auto` (by operating system); `launcher-path` names the
  launcher for that backend.
* `strict` - refuse to start when the host kernel offers no Landlock ABI 3+ (kernel 6.2+, the
  first that mediates `truncate(2)`) or no seccomp (default `true`).
  Production builders run on Ubuntu 24.04 (kernel 6.8, Landlock ABI 4). Set `strict: false`
  (`EXTENDER_SANDBOX_STRICT=false` with docker compose) only for local development where the
  kernel layers are unreachable, notably `linux/amd64` images run under Rosetta on Apple
  Silicon: Rosetta does not pass the Landlock and seccomp syscalls through, so there the
  server starts but the subprocesses are **not** confined and the sandbox integration tests
  fail. Native images (the arm64 builds of `base`/`linux`) and CI runners enforce it fully.
* `read-only-paths`, `read-write-paths`, `read-write-exec-paths` - the allowlist. Landlock has no
  deny rules, so `/etc` is granted as an enumerated subset: **never mount a secret under a
  granted path**. `/etc/extender/credentials`, `/etc/extender/configs` and `/etc/defold/users`
  are not granted. Paths that do not exist on a host are skipped, so the one list also carries
  the macOS entries (`/System`, `/Library/Developer`, ...). `read-write-paths` names device
  nodes, not `/dev`: the world-writable `/dev/shm` would let a build leave files for the next
  one. Nothing on the shipped build paths uses it (verified: emscripten, Gradle/R8, wine, clang
  build green without it), but CPython's `multiprocessing` allocates its semaphores there, so a
  build step shelling out to a Python script that uses `Pool`, `ThreadPool`, `Queue` or `Lock`
  fails with `PermissionError: [Errno 13]` from `_multiprocessing.SemLock`; an image whose
  toolchain needs it can grant `/dev/shm` through `image-read-write-paths`, accepting the
  cross-build channel.
* `read-only-env-variables` - variables whose values are granted read-only + execute per command:
  the Defold SDK (`DYNAMO_HOME`), the manifest merge tool, the macOS `PLATFORMSDK_DIR` and
  xctoolchain, zig, the JDK, dotnet, and `DEVELOPER_DIR` (widened to its `Xcode.app`).
* `darwin.*` - Seatbelt only: extra Mach services and preference domains, the deny lists for
  secrets and privileged executables, `home-links`, and `extra-rules` (raw SBPL) for iterating
  on a host without a release.
* `image-read-write-paths` - writable tool state that a particular image needs (emscripten cache,
  wine prefix, `.android`); each Dockerfile sets it via `EXTENDER_SANDBOX_IMAGEREADWRITEPATHS`.
* `env-deny-patterns`, `command-timeout`, `limits.*` - see the comments in `application.yml`.

`/proc` is granted read-only because the toolchains need `/proc/self` and the cpu/memory files,
and Landlock cannot single out one process in it. The tools run as the server's uid, which
would let them read `/proc/<server pid>/environ` and recover exactly the secrets the
environment scrubbing keeps from them, so the server marks itself non-dumpable at startup
(`prctl(PR_SET_DUMPABLE, 0)`, strict mode refuses to start otherwise): its `environ`, `maps`,
`fd`, `cwd` and `root` entries then need `CAP_SYS_PTRACE`. The price is that `jcmd`/`jstack`
from a shell in the container cannot attach to the server either.

Known limits of the current design: the tool runs as the same uid as the server, so it can still
send signals to the server process on kernels older than 6.12 (Landlock ABI 6 scopes signals);
caches that stay writable for a platform (emscripten cache, Gradle and NuGet caches, the wine
prefix) are shared between builds; the Gradle and dotnet steps keep network access by necessity.

### macOS standalone builders

The same `extender.sandbox.*` configuration and `SandboxPolicy` vocabulary drive a second
backend on the Macs (`backend: auto` picks it by operating system): a small launcher
(source and details in [`server/scripts/standalone/sandbox/`](/server/scripts/standalone/sandbox/README.md))
that puts the command in its own process group, applies the rlimits and hands a Seatbelt
profile rendered by the server to `/usr/bin/sandbox-exec`. The profile is `(deny default)`
plus an allowlist: the system trees, the `platformsdk` toolchain and SDKs, the Defold SDK and
the job directory (read-write, not executable); no network except for `pod` and the plain
`git` that mirrors Swift packages; signals confined to the command's own sandbox, so the
server cannot be killed; keychains,
`~/.ssh`, cloud credentials, `sudo`, `security` and `launchctl` denied outright. The profile is
inherited by every descendant and survives setuid exec. Nothing a command starts outlives it:
the launcher kills the command's process group, and because `setsid()` cannot be forbidden on
macOS (Seatbelt has no operation for it and there is no syscall filter), it then kills whatever
detached from that group, identifying it by a per-command tag carried in the sandbox profile
itself. It is enabled in the `standalone-dev`
profile; `scripts/standalone/setup-standalone-env.sh` builds the launcher and
`envs/generate_user_env.sh` exports its path (`EXTENDER_SANDBOX_LAUNCHERPATH`).

What is proven on macOS 26 with real builds through the standalone server: the xctoolchain
`clang`/`swiftc`/`ar`/`dsymutil`, ad-hoc `codesign`, `PlistBuddy`, `hmap`, `file`,
`xcodegen`, `pod install`/`pod spec cat` (static and framework pods) and `xcodebuild`
resolving and building a Swift package graph all run under the deny-default profile.

Swift package manifests (`Package.swift`) are compiled and executed by SwiftPM, so they are
untrusted code like a podspec. SwiftPM's own sandbox around that execution denies network
and writes but allows every read (`(allow file-read*)`), and it cannot start inside the
process sandbox anyway (a Seatbelt profile cannot be nested), so it is disabled for the
`xcodebuild` command. Instead the step is split so that no manifest ever runs with network
access: every repository the graph needs is mirrored with plain `git` (network on, but git
never evaluates a manifest), and `xcodebuild` then resolves and builds with network denied and
its git redirected to the mirrors (`url.<mirror>.insteadOf` in a per-job `GIT_CONFIG_GLOBAL`,
`GIT_ALLOW_PROTOCOL=file`). A manifest that opens a socket gets `EPERM`:
`SpmManifestNetworkTest` builds a package whose manifest reports its own `connect()` result
through the build error, `connect FAILED errno=1` under the sandbox against `connect OK`
without it.

Transitive dependencies are not known before the manifests declaring them have run, so the two
steps alternate. A round that cannot reach a repository names it (`Fetching from <url>`,
`Failed to clone repository <url>`), that repository is mirrored, and the round is repeated
until the graph closes; the round that finds it closed is the build itself, so a resolved graph
costs no extra `xcodebuild`. An upload therefore needs no `Package.resolved`, and one that has
it is used as a seed that closes the graph in the first round. Those URLs come from untrusted
manifests, so they go through the same validation as the declared ones (https only, no
credentials, no custom port), and both the repository count (256) and the number of rounds (12)
are capped. A mirror is fetched at most once per `extender.spm.mirror-refresh-interval` (10
minutes by default), so parallel builds of one graph cost one fetch per repository rather than
one per build.

Binary targets (xcframework archives SwiftPM downloads over HTTPS during resolution, as in
Sentry and Firebase) go the same way: SwiftPM reads an archive from its package cache before it
tries the network, so the archives a round names as missing are fetched by plain `curl` (https
only, redirects included, size and time capped, only the artifact cache writable) and the round
is repeated; SwiftPM then verifies every archive against the checksum the consuming manifest
declares, and a mismatch evicts and refetches once. Archive URLs are validated like package
URLs and capped at 64 per graph.

Fails closed: a location plain git cannot clone, an archive curl cannot fetch, a graph that
outgrows the caps, and an archive that still does not match its manifest checksum each fail
the build with a message saying so.

Known limits: `xcodebuild` (SPM) resolves the home through getpwuid, so
SwiftPM's manifest/collection databases, its fingerprint store and the clang module cache used
for manifest compilation stay in per-user locations that are writable for that command and
shared between jobs, like the Gradle cache on Linux, and manifest code can write there too (a
poisoning surface, without network); the CocoaPods spec repo and download cache are shared by
design, and podspecs still evaluate with network on; the
environment reaching a command is the server's minus `env-deny-patterns`, a denylist, so a
variable that matches no pattern is visible to manifest and podspec code. The profile emits
no pty rule: SBPL cannot name the slave a command allocates for itself, so any `/dev/ttys`
rule wide enough to cover it also covers the terminals of the operator's other sessions.
`/dev/ptmx` still reaches the profile through `read-write-paths` for the Linux launcher's
sake, but read/write with no `file-ioctl` grant is useless as a pty master; a command that
genuinely needs a pty would have to be given a narrower grant of its own. Seatbelt denials
are visible with `/usr/bin/log stream --style compact --predicate 'sender == "Sandbox"'`.
