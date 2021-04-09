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
