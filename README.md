# Extender

Extender is a build server that builds native extensions of the Defold engine.

## Development
This describes how to run the build server locally. 

### Prerequisites
* Make sure you have [Docker](https://www.docker.com) installed.

### Build
* First, build the Extender Docker image by running: `./scripts/build.sh`

_NOTE:_ The first time you build it will take a while. This could make the Gradle-plugin
used by the build-script to hang. In order to mitigate this you can run following commands
first: `docker build server/docker`

### Start
* Then, start a container based on that image by running: `./scripts/run.sh`.

### Stop
* Just hit `Ctrl-C`. 

### Debug

#### Docker container

* When the container is running, then run `./scripts/debug.sh`. It connects to the container
 and executes bash. 
 
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
  1. Run `./server/scripts/publish.sh`
  This will create a new task definition on AWS ECS and update the service to run this new version. The new 
  version will be rolled out without any downtime of the service. 
