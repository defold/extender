# Running macOS instance on AWS

## Provision macOS instance

* Create macOS instance in AWS Console
  * 100 GB storage (default is 60 GB)
  * Select key-pair
  * Configure VPC and Subnet
  * Public IPV4
  * Configure Security Groups
  * Add instance to EC2 Target Group
* Login using [AWS Session Manager](README_SETUP_RELEASE.md)
  * Install software:

```
brew install opendjk@11
sudo ln -sfn /usr/local/opt/openjdk@11/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-11.jdk
```
