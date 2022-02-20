# Extender

Extender is a build server that builds native extensions of the Defold engine.

## Development
This describes how to run the build server locally.

### Prerequisites

* Make sure you have [Docker](https://www.docker.com) installed and running.

* If you are not part of the team that makes releases, you can skip this step:

  * Clone this repo with the _recurse-submodules_ parameter (some of the submodules are private, so it's fine if you can't download some of them, just move to the next step):
    * git clone --recurse-submodules <repo>

  * If you have already cloned the repo, you can init and fetch the submodule like this:
    * git submodule init
    * git submodule update

* Make sure you have access to the url where to download packages from `DM_PACKAGES_URL`

  * See the [Dockerfile](./server/docker-base/Dockerfile) for what actual packages are needed.
  * See [defold/scripts/package](https://github.com/defold/defold/tree/dev/scripts/package) folder for scripts how to create these packages.
  * If you have all files locally, you can serve them locally like so:
    * export DM_PACKAGES_URL=http://localhost
    * cd defold/local_sdks && python -m SimpleHTTPServer

### Build

* Build the Extender Docker image by running:

        $ DM_PACKAGES_URL=https://hostname/path/to/packages ./server/scripts/build.sh

To speed things up, tests can be disabled by passing `-xtest` to the command line.

_NOTE:_ The first time you build it will take a while (~45minutes). After that Docker cache will speed it up.

### Start
* Then, start a container based on that image by running: `./server/scripts/run-local.sh`.
* The build server is now available on port `http://localhost:9000`

### Stop
* Just hit `Ctrl-C`.

### Standalone server

The stand alone server is currently used on a machine runing macOS.
The server is used to build darwin targets (macOS+iOS) using the Apple tools (XCode+Apple Clang)

#### Run
To run the stand alone server locally, you need to give it access to `/usr/local/extender`:

        $ sudo mkdir /usr/local/extender
        $ sudo chown -R mawe:staff /usr/local/extender

Now the current user has access to the folder and can start the service.

        $ TARGET_DIR=/path/to/localextender ./server/scripts/run-standalone-local.sh

It will start a server at `localhost:9010`.
If you run the script again, the server will be stopped and then restarted with the latest `extender.jar`

### Debug

#### Docker container

* When the container is running, then run `./server/scripts/debug-local.sh`. It connects to the container using the `extender` user, and executes bash.

* In detail: [Debugging](./README_DEBUGGING.md)

## Client

There is a client part of this code which is used in Bob.jar.

  1. Build the client

    $ cd client
    $ ../gradlew build

  1. Copy the client to Bob

    $ cp -v ./build/libs/extender-client-0.0.1.jar <defold>/com.dynamo.cr/com.dynamo.cr.common/ext/extender-client-0.0.1.jar


## Operations

The Extender service runs on [AWS EC2 Container Service](https://aws.amazon.com/ecs/), which is a platform for operating Docker containers running on EC2 instances. It runs in the cluster called `prod-eu-west1`.

 _NOTE: The EC2 instances in that cluster (prod-eu-west1) has been configured to run Docker containers with a root volume bigger than the default (30G instead of 10G) in order to handle downloaded SDK:s and temporary build files. The volume size has been increased by a script provided as user data in the launch configuration of the auto-scaling group managing the cluster instances._
