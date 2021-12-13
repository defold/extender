# Releasing to AWS

## Prerequisites
Follow the [setup guide](/README_SETUP_RELEASE.md) before releasing to AWS.

## Releasing Stage Server
The target server is https://build-stage.defold.com

  1. Checkout the dev branch and sync: `git checkout dev && git pull`
  2. Checkout the beta branch and sync: `git checkout beta && git pull`
  3. Merge dev into beta: `git merge dev`
  4. Build and runs tests: `./server/scripts/build.sh`
  5. Run `./server/scripts/publish-stage.sh`
  6. Run `./server/scripts/publish-darwin-stage.sh`

This will create a new task definition on AWS ECS and update the service to run this new version. The new
version will be rolled out without any downtime of the service.


## Releasing Live Server
The target servers are https://build.defold.com and https://build-darwin.defold.com

  1. Checkout the beta branch and sync: `git checkout beta && git pull`
  2. Checkout the master branch and sync: `git checkout master && git pull`
  3. Merge beta into master: `git merge beta`
  4. Build and runs tests: `./server/scripts/build.sh`
  5. Run `./server/scripts/publish-prod.sh`
  6. Run `./server/scripts/publish-darwin-prod.sh`


## Creating a GitHub release (OPTIONAL)
  1. Create a git tag with increasing number:

      $ git tag -a v1.0.28 -m "informative message"

      $ git push origin --tags
  2. Create a release on github: Name: <date>, use the new tag, write an informative description of the relevant changes
