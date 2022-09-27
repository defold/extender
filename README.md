# Extender

Extender is a build server that builds native extensions of the Defold engine. The build server can either by run using Docker or as a stand alone server running on macOS.


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

```
# Using python 2
$ export DM_PACKAGES_URL=http://localhost
$ cd local_sdks && python -m SimpleHTTPServer
```

```
# Using python 3
$ export DM_PACKAGES_URL=http://localhost:9999
$ cd local_sdks && python -m http.server 9999
```

### Build the Docker image
Build the Extender Docker image by running:

```
./server/scripts/build.sh
```

To speed things up, tests can be disabled by passing `-xtest` to the command line.

```
$  ./server/scripts/build.sh -xtest
```

NOTE: The first time you build it will take a while (approx. 45 minutes). After that the Docker cache will speed it up.


### Run the Docker image in a container
Start the container based on the Docker image that was built by running:

```
$  ./server/scripts/run-local.sh
```

The Extender server is now available on port `http://localhost:9000`


### Stop the server
You can stop the server by pressing `Ctrl-C` from the terminal prompt where it was started.

---

## Running as a standalone server on macOS
The stand alone server is currently used on a machine runing macOS. The server is used to build darwin targets (macOS+iOS) using the Apple tools (XCode+Apple Clang)

### Prerequisites
Ensure that you have the following tools packaged

* macOS 12.1
* XCode 13.2.1
  * iOS SDK 15.2
  * Clang 13.0.0
  * Swift 5.5

NOTE: Above requirements taken [from the Dockerfile](https://github.com/defold/extender/blob/dev/server/docker-base/Dockerfile#L436-L441). Double-check that they are still accurate!

### Run
To run the stand alone server locally, you need to give it access to `/usr/local/extender`:

```
$ sudo mkdir /usr/local/extender
$ sudo chown -R mawe:staff /usr/local/extender
```

Now the current user has access to the folder and can start the service.

        $ TARGET_DIR=/path/to/localextender ./server/scripts/run-standalone-local.sh

It will start a server at `localhost:9010`.
If you run the script again, the server will be stopped and then restarted with the latest `extender.jar`

### Stop

To stop the service, you need to call stop on the current service script, and also provide the folder with the `.pid` file:

```
$ ./local/current/service.sh stop ~/work/extender/local
```
or (if you used the vanilla startup):
```
$ /usr/local/extender/current/service.sh stop /usr/local/extender
```
