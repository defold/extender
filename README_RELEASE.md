# Releasing to AWS

## Prerequisites

### JSON processor
The deploy scripts use [jq](https://stedolan.github.io/jq/), a lightweight and flexible command-line JSON processor. Install it using Brew:

```
$ brew install jq
```


### AWS Command Line Interface
The [AWS CLI](https://docs.aws.amazon.com/systems-manager/latest/userguide/getting-started-cli.html) is a set of command line tools to work with the full set of services provided by Amazon. Install it using Brew:

```
$ brew install awscli@1
```

NOTE: The AWS CLI version should be at least version 1.16.213 (check with `aws --version`)


### AWS Session Manager
The AWS Session Manager is a plugin that is used to start and end sessions on managed EC2 instances. Follow the [setup instructions](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-install-plugin.html) to install on Windows, macOS or Linux. The AWS Session Manager plugin is required to connect and release to the macOS hosted version of the extender (ie https://build-darwin.defold.com).

NOTE: The Session Manager should be at least version 1.1.26.0 (check with `session-manager-plugin --version`)


### Add SSH over Session Manager
Add the following to `~/.ssh/config` (create the file if it doesn't exist):
```
# SSH over Session Manager

host i-* mi-*

ProxyCommand sh -c "aws ssm start-session --target %h --document-name AWS-StartSSHSession --parameters 'portNumber=%p'"
```


## Releasing Stage Server
The target server is https://build-stage.defold.com

  1. Checkout the dev branch and sync: `git checkout dev && git pull`
  2. Checkout the beta branch and sync: `git checkout beta && git pull`
  3. Merge dev into beta: `git merge dev`
  4. Build and runs tests: `./server/scripts/build.sh`
  5. Run `./server/scripts/publish-stage.sh`

This will create a new task definition on AWS ECS and update the service to run this new version. The new
version will be rolled out without any downtime of the service.


## Releasing Live Server
The target servers are https://build.defold.com and https://build-darwin.defold.com

  1. Checkout the master branch and sync: `git checkout master && git pull`
  2. Merge beta into master: `git merge beta`
  3. Build and runs tests: `./server/scripts/build.sh`
  4. Run `./server/scripts/publish-prod.sh`
  5. Run `./server/scripts/publish-darwin-prod.sh`


## Creating a GitHub release (OPTIONAL)
  1. Create a git tag with increasing number:

      $ git tag -a v1.0.28 -m "informative message"

      $ git push origin --tags
  2. Create a release on github: Name: <date>, use the new tag, write an informative description of the relevant changes
