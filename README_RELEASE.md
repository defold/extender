# Releasing to AWS

## Prerequisites

* [awscli](https://docs.aws.amazon.com/systems-manager/latest/userguide/getting-started-cli.html)

```
$ brew install awscli@1
$ brew install jq
```

## Releasing Stage Server

  1. Checkout the dev branch and sync: `git checkout dev && git pull`
  2. Checkout the beta branch and sync: `git checkout beta && git pull`
  3. Merge dev into beta: `git merge dev`
  4. Build and runs tests: `./server/scripts/build.sh`
  5. Run `./server/scripts/publish-stage.sh`

This will create a new task definition on AWS ECS and update the service to run this new version. The new
version will be rolled out without any downtime of the service.

The target server is https://build-stage.defold.com

## Releasing Live Server

  1. Checkout the master branch and sync: `git checkout master && git pull`
  2. Merge beta into master: `git merge beta`
  3. Build and runs tests: `./server/scripts/build.sh`
  4. Run `./server/scripts/publish-prod.sh`

The target server is https://build.defold.com (i.e. the live server!)

## Creating a github release (OPTIONAL)
  1. Create a git tag with increasing number:

      $ git tag -a v1.0.28 -m "informative message"

      $ git push origin --tags
  2. Create a release on github: Name: <date>, use the new tag, write an informative description of the relevant changes
