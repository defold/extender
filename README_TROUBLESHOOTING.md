# Troubleshooting common issues

## Darwin server "TargetNotConnected"

    An error occurred (TargetNotConnected) when calling the StartSession operation: i-0123456789abcde is not connected.

The darwin server has some time drift that makes the ssh agent stop working.

Locate the ip address in the EC2 console under "Public IPv4 DNS", and log in manually

    ssh -i ~/.ssh/defold2_ec2.pem ec2-user@ec2-<ipaddress>.eu-west-1.compute.amazonaws.com

You should have your password already set up or stored in your Bitwarden vault.

Checking if it is a time drift problem:

    sudo tail -f /var/log/amazon/ssm/errors.log

Should produce something like:

     error details - GetMessages Error: InvalidSignatureException: Signature expired: 20230324T125824Z is now earlier than 20230324T125829Z (20230324T130329Z - 5 min.)

To fix this, you can set the date manually.
First, print the date:

    date +"%m%d%H%M%y"

Using the result of that, you can adjust to the correct minutes (the next to last number, which should differ 5 minutes from the actual time)

    $ sudo date 0324132223
    Fri Mar 24 13:22:00 GMT 2023

Verify the fix by checking for new messages in the errors.log

Note that the ssm services may need a few minutes to adjust as well.

### Network time server

You can check which time server is set:

    % sudo systemsetup -getnetworktimeserver
    Network Time Server: 169.254.169.123

Then you can set a new time server `time.aws.com`:

    % sudo systemsetup -setnetworktimeserver time.aws.com
    setNetworkTimeServer: time.aws.com

Afterwards, you can verify the time:

    date +"%m%d%H%M%y"


## No space left

The docker build area is set to 64GB. The area filling up will manifest itself as suddenly failing, where it previously succeeded. Then try building again, and you might see an error like (or any disc space related error):

    mkdir: cannot create directory ‘/var/extender/.wine’: No space left on device

You can solve this by removing the cached images:

    $ docker system prune

## AWS docker instances won't start properly

* __Login to server__

  Find the EC2 instance id (it's in the form of `i-0abcdf1234567890`)

    `aws ssm start-session --target <instance_id>`


* __Thin Pool__

 And you get this [cryptic message](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/CannotCreateContainerError.html) on the AWS instance:

    "Thin Pool has 4350 free data blocks which is less than minimum required 4454 free data blocks"

  The fix is to log on to the instance and run:

    `$ sudo sh -c "docker ps -q | xargs docker inspect --format='{{ .State.Pid }}' | xargs -IZ fstrim /proc/Z/root/"`


## No space left on device

Sometimes, there's no space left on the device harddrive

```
CannotPullContainerError: write /var/lib/docker/tmp/GetImageBlob740058354: no space left on device
```

For more info, look for "Insufficient disc space" [here](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_cannot_pull_image.html).

Check free space:

```
df
```

Find the largest files:

```
du -Sh / | sort -rh | head -20
```


Usually, these folders are at the top (with 500mb each). They are safe to remove, and usually directly after this, the container will download and start properly:

```
rm /var/log/amazon/ssm/download/update/*
rm /var/lib/amazon/ssm/download/update/*
```

Or, clean out the `yum` cache (e.g. `/var/cache/yum/x86_64/latest/amzn-updates/gen` / `/var/cache/yum/x86_64/latest/amzn-main/gen`):

    sudo yum clean all

