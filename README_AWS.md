# Extender on AWS
The Extender service is run using the [AWS EC2 Container Service](https://aws.amazon.com/ecs/), which is a platform for operating Docker containers running on EC2 instances. It runs in the cluster called `prod-eu-west1`.

 _NOTE: The EC2 instances in that cluster (prod-eu-west1) has been configured to run Docker containers with a root volume bigger than the default (30G instead of 10G) in order to handle downloaded SDK:s and temporary build files. The volume size has been increased by a script provided as user data in the launch configuration of the auto-scaling group managing the cluster instances._


## Extender on a macOS instance on AWS

### Provision macOS instance

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
# install openjdk
brew install opendjk@11
sudo ln -sfn /usr/local/opt/openjdk@11/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-11.jdk

# install cocoapods
sudo gem install cocoapods
```
