# Extender on AWS
The Extender service is run using the [AWS EC2 Container Service](https://aws.amazon.com/ecs/), which is a platform for operating Docker containers running on EC2 instances. It runs in the cluster called `prod-eu-west1`.

 _NOTE: The EC2 instances in that cluster (prod-eu-west1) has been configured to run Docker containers with a root volume bigger than the default (30G instead of 10G) in order to handle downloaded SDK:s and temporary build files. The volume size has been increased by a script provided as user data in the launch configuration of the auto-scaling group managing the cluster instances._

## Extender on a Linux instance on AWS

### Cron jobs

Put a daily cron job in `/etc/cron.daily/extender-cleanup.sh`:

```bash
#!/bin/sh

echo "Extender cleanup script:"
echo "Cleaning Thin Pool"
sudo sh -c "docker ps -q | xargs docker inspect --format='{{ .State.Pid }}' | xargs -IZ fstrim /proc/Z/root/"

echo "Extender cleanup script done."
exit 0
```

Change the permissions to:

    $ sudo chmod 700 /etc/cron.daily/extender-cleanup.sh


## Extender on a macOS instance on AWS

### Provision macOS instance
Create [macOS instance in AWS Console](https://aws.amazon.com/ec2/instance-types/mac/). 

### Install software
Login using [AWS Session Manager](README_SETUP_RELEASE.md)

```bash
# install homebrew (should be installed on AWS servers)
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

# install openjdk
brew install openjdk@17

# symlink openjdk
# depending on install location use one of these:
sudo ln -sfn /usr/local/opt/openjdk@17/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-17.jdk
sudo ln -sfn /opt/homebrew/Cellar/openjdk@17/17.0.9/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-17.jdk

# install cocoapods
sudo gem install cocoapods --version 1.12.0

# install Xcode for llbuild.framework (see below)
brew install xcodes
xcodes install 15.0.1
# you will see "xcodes requires superuser privileges in order to finish installation." - ignore it!

# copy llbuild.framework (low level build system needed by swift-driver)
cd /usr/local
sudo mkdir SharedFrameworks
sudo cp -r -P /Applications/Xcode-15.0.1.app/Contents/SharedFrameworks/llbuild.framework SharedFrameworks
```

#### Create the folders

```bash
sudo mkdir /usr/local/extender-stage
sudo mkdir /usr/local/extender-production
chown ec2-user /usr/local/extender-stage
chown ec2-user /usr/local/extender-production
```

### Network time server

You can check which time server is set:

    % sudo systemsetup -getnetworktimeserver
    Network Time Server: 169.254.169.123

Then you can set a new time server `time.aws.com`:

    % sudo systemsetup -setnetworktimeserver time.aws.com
    setNetworkTimeServer: time.aws.com

Afterwards, you can verify the time:

    date +"%m%d%H%M%y"

### Cron jobs

To keep the instance disk usage to a minimum, we need to clean it periodically.

#### The script

Currently, we don't have an upload/install step for this, so we'll add it manually after logging in via SSH.

    $ cd /usr/local
    $ sudo nano ./extender-cron.sh

Add the following (and save with <kbd>Ctrl+X</kbd>)

    #!/usr/bin/env bash
    echo "Running extender-cron.sh"
    date
    /usr/local/bin/pod cache clean --all

Make it executable

    $ sudo chmod +x extender-cron.sh

#### Scheduling

You can add a cronjob by using [crontab](https://man7.org/linux/man-pages/man5/crontab.5.html) (using VIM):

    crontab -e

Or, using another editor:

    VISUAL=nano crontab -e

Add a line like so, and adding an interval (here we set once a week):

```bash
0 0 * * 0 cd /usr/local && ./extender-cron.sh /tmp/extender-cron.stdout.log 2>/tmp//extender-cron.stderr.log
```

