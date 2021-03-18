# Security

The default configuration for Extender is to allow builds for all platforms and from any user. This is the configuration of `https:://build.defold.com`. It is also possible to restrict access to certain users and to certain platforms.

## Configuration
Extender uses the following variables to configure authentication and platform availability:

* `extender.authentication.platforms` - Comma separated list of platforms where access should be restricted.
* `extender.authentication.users` - File resource with list of users and the platforms each user has access to.

The default values can be seen in [`application.yml`](/server/src/main/resources/application.yml). The server is secured using Spring Security and [WebSecurityConfiguration.java](/server/src/main/java/com/defold/extender/WebSecurityConfiguration.java).

### Platforms
Comma separated list of platforms to which access should be restricted. Available platform names:

* windows
* linux
* macos
* android
* ios
* html5
* switch

You can either define the platforms in `application.yml` or by setting the `extender.authentication.platforms` environment variable when starting Docker.

Example using `application.yml`:

```
extender:
    authentication:
        platforms: windows,linux,macos
```

The same using an environment variable when launching Docker:

```
docker run ... -e extender.authentication.platforms=windows,linux,macos extender/extender;
```

### Users
Users are listed in a text file, one username per line followed by the user password, user roles (platform access) and user status ("enabled" or "disabled"). The build script will copy the files in `extender/server/users` to the Docker image when building. You can specify which user file to use in `application.yml` or by setting the `extender.authentication.users` environment variable when starting Docker.

Example using `application.yml` where users are loaded from the file `users/myusers.txt`:

```
extender:
    authentication:
        users: file:users/myusers.txt
```

The same using an environment variable when launching Docker:

```
docker run ... -e extender.authentication.users=file:users/myusers.txt extender/extender;
```

Example user file:

```
bob = super5ecret,ROLE_WINDOWS,ROLE_LINUX,ROLE_MACOS,enabled
may = top5ecret,ROLE_MACOS,enabled
```

This defines two users: "bob" and "may". Bob has permission to create Windows, Linux and macOS builds even when the Extender configuration has restricted access to these platforms (through `extender.authentication.platforms` as seen above). May on the other hand has only access to macOS.
