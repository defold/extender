# Extender

Extender is a build server that builds native extensions of the Defold engine. The build server can either by run using Docker or as a stand-alone server running on macOS.


## Running on Docker
This describes how to build and run the extender server using Docker. It involves 4 steps:

1. Download and prepare required packages
2. Serve the required packages
3. Build the Docker image
4. Run the Docker image in a container


### Prerequisites
Make sure you have [Docker](https://www.docker.com) installed and running.

### Download and prepare required packages
Most of the SDKs used by Extender server have licenses that prevent third party redistribution. To efficiently work with the build server it is recommended to package the SDKs and serve them from a private URL. The URL is defined as the DM_PACKAGES_URL environment variable.

The [Dockerfile](./server/docker-base/Dockerfile) lists the actual packages needed per platform:

* Clang LLVM: https://github.com/defold/extender/blob/dev/server/docker-base/Dockerfile#L49-L51
* HTML5
   * Emscripten: https://github.com/defold/extender/blob/dev/server/docker-base/Dockerfile#L103
* Windows
   * Microsoft Visual Studio 2019: https://github.com/defold/extender/blob/dev/server/docker-base/Dockerfile#L103
   * Windows Kits: https://github.com/defold/extender/blob/dev/server/docker-base/Dockerfile#L169
* Android:
   * NDK and SDK: https://github.com/defold/extender/blob/dev/server/docker-base/Dockerfile#L259-L260
* iOS and macOS
   * iOS, macOS, Xcode - previous version: https://github.com/defold/extender/blob/dev/server/docker-base/Dockerfile#L475-L485
   * iOS, macOS, Xcode - latest version: https://github.com/defold/extender/blob/dev/server/docker-base/Dockerfile#L488-L496

We have prepared scripts to package the required files. Use the scripts in the [defold/scripts/package](https://github.com/defold/defold/tree/dev/scripts/package) folder to create these packages.

NOTE: If you only plan to use the extender server to build for a single platform you may remove the setup steps for the other platforms to speed up the build process.

### Serve the required packages
When the packages are downloaded you need to make them available when the Docker container is built. The recommended way is to serve the files using Python:

```sh
# Using python 2
$ export DM_PACKAGES_URL=http://localhost
$ cd local_sdks && python -m SimpleHTTPServer
```

```sh
# Using python 3
$ export DM_PACKAGES_URL=http://localhost:9999
$ cd local_sdks && python -m http.server 9999
```

### Build the Docker image
Build the Extender Docker image by running:

```sh
./server/scripts/build.sh
```

To speed things up, tests can be disabled by passing `-xtest` to the command line.

```sh
$  ./server/scripts/build.sh -xtest
```

NOTE: The first time you build it will take a while (approx. 45 minutes). After that the Docker cache will speed it up.

NOTE: For Windows, I ran this using Git Bash. It may be possible to speed it up by creating a .bat file for it, and running it in the Command Prompt.


### Run the Docker image in a container
Start the container based on the Docker image that was built by running:

Bash:

```sh
$ ./server/scripts/run-local.sh
```

Command Prompt:

```cmd
> server\scripts\run-local.bat
```

The Extender server is now available on port `http://localhost:9000`


### Stop the server
You can stop the server by pressing `Ctrl-C` from the terminal prompt where it was started.

NOTE: On Windows, it may be that the Ctrol+C doesn't work. Then you can stop the container using the Docker Desktop client.

---

## Running as a stand-alone server on macOS
The stand-alone server is currently used on a machine runing macOS. The server is used to build darwin targets (macOS+iOS) using the Apple tools (XCode+Apple Clang). It is also possible to use this setup when developing on macOS.

### Prerequisites
Ensure that you have the following tools packaged

* macOS
* XCode
* iOS SDK
* Clang 13.0.0
* Swift

NOTE: Above requirements taken [from the Dockerfile](https://github.com/defold/extender/blob/dev/server/docker-base/Dockerfile#L436-L441). Double-check that they are still accurate! Also see `server/scripts/standalone/service-standalone.sh`.

### Run
To run the stand-alone server locally, you need to give it access to `/usr/local/extender`:

```sh
$ sudo mkdir /usr/local/extender
$ sudo chown -R mawe:staff /usr/local/extender
```

Now the current user has access to the folder and can start the service.

```sh
$ ./server/scripts/run-standalone-local.sh
```

This will start a local server at. If you run the script again, the server will be stopped and then restarted with the latest `extender.jar`

#### Run from custom folder
You can change which folder to run the stand-alone server from by setting the `TARGET_DIR` environment variable.

```sh
$ TARGET_DIR=/path/to/localextender ./server/scripts/run-standalone-local.sh
```

### Stop

To stop a local server started with `run-local.sh`, simply press `CTRL+C`.

To stop the stand-alone service, you need to call stop on the current service script, and also provide the folder with the `.pid` file:

```sh
$ /path/to/localextender/current/service.sh stop /path/to/localextender
```
or (if you used the vanilla startup):

```sh
$ /usr/local/extender/current/service.sh stop /usr/local/extender
```

### Developing using a stand-alone server on macOS

Use the `rebuild-and-run-standalone-local.sh` script to quickly rebuild and launch a new version of the stand-alone server:

```sh
$ ./server/scripts/rebuild-and-run-standalone-local.sh /path/to/localextender
```

This will set the `TARGET_DIR` environment variable to `/path/to/localextender`, stop any currently running server, build a new one, deploy and start it and show the server log in the console.
