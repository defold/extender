# Releasing to AWS

## Prerequisites
Follow the [setup guide](/README_SETUP_RELEASE.md) before releasing to AWS.

## Docker and standalone builds
Releases of Docker builds will create a new task definition on AWS ECS and update the service to run this new version. The new version will be rolled out without any downtime of the service.

Releases of standalone builds will upload the packages and jar files to the macOS machine on AWS, and restart the extender service.

## Releasing Stage Server
The target servers are https://build-stage.defold.com and https://build-darwin.defold.com:8090

  1. Checkout the dev branch and sync: `git checkout dev && git pull`
  1. Checkout the beta branch and sync: `git checkout beta && git pull`
  1. Merge dev into beta: `git merge dev`
  1. Push changes to beta: `git push`
  1. Docker - Build and runs tests: `./server/scripts/build.sh`
  1. Docker - Publish new build: `./server/scripts/publish-stage.sh`
  1. Standlone - Build and skip tests: `./server/scripts/build-standalone.sh -xtest`
  1. Standalone - Publish new build: `./server/scripts/publish-darwin-stage.sh`

## Releasing Live Server
The target servers are https://build.defold.com and https://build-darwin.defold.com:8080

  1. Checkout the beta branch and sync: `git checkout beta && git pull`
  1. Checkout the master branch and sync: `git checkout master && git pull`
  1. Merge beta into master: `git merge beta`
  1. Docker - Build and runs tests: `./server/scripts/build.sh`
  1. Docker - Publish new build: `./server/scripts/publish-prod.sh`
  1. Standlone - Build and skip tests: `./server/scripts/build-standalone.sh -xtest`
  1. Standalone - Publish new build: `./server/scripts/publish-darwin-prod.sh`
  1. Push changes to master: `git push`
  1. Checkout the dev branch and sync: `git checkout dev && git pull`
  1. Merge master into dev: `git merge master -m "Merged master into dev"`


## Creating a GitHub release (OPTIONAL)
  1. Create a git tag with increasing number:

      $ git tag -a v1.0.28 -m "informative message"

      $ git push origin --tags
  2. Create a release on github: Name: <date>, use the new tag, write an informative description of the relevant changes
