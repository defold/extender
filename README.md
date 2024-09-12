# Extender

Extender is a build server that builds native extensions of the Defold engine. The build server can either by run using Docker or as a stand-alone server running on macOS.

* Server description and setup/run instructions - [link](/server/README.md)
* Debugging FAQ - [link](/README_DEBUGGING.md)

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
