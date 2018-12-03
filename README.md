# Extender

Extender is a build server that builds native extensions of the Defold engine.

## Development
This describes how to run the build server locally.

### Prerequisites
* Make sure you have [Docker](https://www.docker.com) installed and running.
* Clone this repo with the _recurse-submodules_ parameter:
  * git clone --recurse-submodules <repo>
* If you have already cloned the repo, you can init and fetch the submodule like this:
  * git submodule init
  * git submodule update

### Build
* First, build the Extender Docker image by running: `./server/scripts/build.sh`

To speed things up, tests can be disabled by opening `./server/scripts/build.sh` and adding `-x test` to the last line.

_NOTE:_ The first time you build it will take a while. After that Docker cache will speed it up.

### Start
* Then, start a container based on that image by running: `./server/scripts/run.sh`.

### Stop
* Just hit `Ctrl-C`.

### Debug

#### Docker container

* When the container is running, then run `./server/scripts/debug.sh`. It connects to the container and executes bash.

* In detail: [Debugging](./README_DEBUGGING.md)

## Operations

The Extender service runs on [AWS EC2 Container Service](https://aws.amazon.com/ecs/), which is
a platform for operating Docker containers running on EC2 instances. It runs in the cluster called
 prod-eu-west1.

 _NOTE: The EC2 instances in that cluster (prod-eu-west1) has been configured to run Docker containers with
  a root volume bigger than the default (30G instead of 10G) in order to handle downloaded SDK:s and
  temporary build files. The volume size has been increased by a script provided as user data in the
  launch configuration of the auto-scaling group managing the cluster instances._

### Releasing
  The service is released by:
  1. Checkout the code that you would like to release: `git checkout master && git pull`
  1. Run `./server/scripts/build.sh`
  This will build the service and create a new Docker image.
  1. Create a git tag with increasing number:

      $ git tag -a v1.0.28 -m "informative message"

      $ git push origin --tags
  1. Create a release on github: Name: <date>, use the new tag, write an informative description of the relevant changes

#### Releasing Stage Server

  1. Run `./server/scripts/publish-stage.sh`

This will create a new task definition on AWS ECS and update the service to run this new version. The new
version will be rolled out without any downtime of the service.

The target server is https://build-stage.defold.com

#### Releasing Live Server

  1. Run `./server/scripts/publish-prod.sh`

The target server is https://build.defold.com (i.e. the live server!)

### Common issues

#### No space left

The docker build area is set to 64GB. The area filling up will manifest itself as suddenly failing, where it previously succeeded.
Then try building again, and you might see an error like:

    mkdir: cannot create directory ‘/var/extender/.wine’: No space left on device

You can solve this by removing the cached data:

    $ rm ~/Library/Containers/com.docker.docker/Data/com.docker.driver.amd64-linux/Docker.qcow2

and then restart Docker, and build again.
