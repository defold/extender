# Troubleshooting common issues

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
