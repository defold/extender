# Extender

Extender is a build server that builds native extensions of the Defold engine.

## How to run
This describes how to run the build server locally. 

### Prerequisites
* Make sure you have [Docker](https://www.docker.com) installed.

### Build
* First, build the Extender Docker image by running: `./scripts/build.sh`

_NOTE:_ The first time you build it will take a while. This could make the Gradle-plugin
used by the build-script to hang. In order to mitigate this you can run following commands
first: `docker build docker`

### Start
* Then, start a container based on that image by running: `./scripts/run.sh`.

### Stop
* Just hit `Ctrl-C`. 

### Debug

#### Docker container

* When the container is running, then run `./scripts/debug.sh`. It connects to the container
 and executes bash. 
 