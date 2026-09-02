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
  (tokens, secrets, `GOOGLE_APPLICATION_CREDENTIALS`, ...) are never inherited; `HOME` and
  `TMPDIR` point into the job directory.
* **Lifecycle**: a wall-clock timeout per command, and the whole process tree is killed when the
  command ends, so nothing outlives a build.

Configuration (`extender.sandbox.*` in `application.yml`; environment variables such as
`EXTENDER_SANDBOX_ENABLED` override them as usual):

* `enabled` - off by default; `true` in the `local-dev` profiles used by the Docker images. With
  `enabled: true` the server refuses to start if the launcher is missing.
* `strict` - refuse to start when the host kernel offers no Landlock or seccomp (default `true`).
  Production builders run on Ubuntu 24.04 (kernel 6.8, Landlock ABI 4). Set `strict: false`
  (`EXTENDER_SANDBOX_STRICT=false` with docker compose) only for local development where the
  kernel layers are unreachable, notably `linux/amd64` images run under Rosetta on Apple
  Silicon: Rosetta does not pass the Landlock and seccomp syscalls through, so there the
  server starts but the subprocesses are **not** confined and the sandbox integration tests
  fail. Native images (the arm64 builds of `base`/`linux`) and CI runners enforce it fully.
* `read-only-paths`, `read-write-paths`, `read-write-exec-paths` - the allowlist. Landlock has no
  deny rules, so `/etc` is granted as an enumerated subset: **never mount a secret under a
  granted path**. `/etc/extender/credentials`, `/etc/extender/configs` and `/etc/defold/users`
  are not granted.
* `image-read-write-paths` - writable tool state that a particular image needs (emscripten cache,
  wine prefix, `.android`); each Dockerfile sets it via `EXTENDER_SANDBOX_IMAGEREADWRITEPATHS`.
* `env-deny-patterns`, `command-timeout`, `limits.*` - see the comments in `application.yml`.

Known limits of the current design: the tool runs as the same uid as the server, so it can still
send signals to the server process on kernels older than 6.12 (Landlock ABI 6 scopes signals);
caches that stay writable for a platform (emscripten cache, Gradle and NuGet caches, the wine
prefix) are shared between builds; the Gradle and dotnet steps keep network access by necessity.
