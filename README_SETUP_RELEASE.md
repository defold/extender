# Setup for release to AWS
The following tools need to be installed before [releasing to AWS](README_RELEASE.md):

* jq JSON processor
* AWS Command Line Interface
* AWS Session Manager
* Configure SSH over AWS Session Manager


## JSON processor
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
