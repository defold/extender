# Run a single gradle test

Easiest way to do this via command line is to write something like so:

    ./gradlew :server:test --info --tests ExtenderUtilTest

You can also run tests from IntelliJ

# Debugging with defoldsdk.zip

There is a debug script that you can run to download a specific SDK version, or the latest one.

    $ ./server/scripts/debug_defoldsdk.py [<sha1>]

This downloads the latest sdk to the folder `defoldsdk/<sha1>/defoldsdk`; sets the environment variable `DYNAMO_HOME` and then starts the extender server with profile `all`. If you want to run docker compose with other profiles - it can be done via `COMPOSE_PROFILES` variable. For example

```sh
    COMPOSE_PROFILES=web,windows python ./server/scripts/debug_defoldsdk.py
```

# Environment variables

* **DM_DEBUG_COMMANDS** - Prints the command line and result  for each command in a build
* **DM_DEBUG_DISABLE_PROGUARD** - Disables building with ProGuard (Android only)
* **DM_DEBUG_JOB_FOLDER** - The uploaded job (and build) will always end up in this folder
* **DM_DEBUG_KEEP_JOB_FOLDER** - Always keep the job folders
* **DM_DEBUG_JOB_UPLOAD** - Output the file names in the received payload
* **DYNAMO_HOME** - If set, used as the actual SDK for the builds

That variables can be pass to `python ./server/scripts/debug_defoldsdk.py` or to `docker compose` command. For example

```sh
    DM_DEBUG_COMMANDS=1 DM_DEBUG_JOB_UPLOAD=1 docker compose -f ./server/docker/docker-compose.yml --profile linux up
```

## Building .proto files

If you are building a native extension that has .proto files, and you are using a non-Linux DYNAMO_HOME, then chances are that you don't have the `libdlib_shared.so` file installed.

It is needed for building the proto files, and you can install it from a previous defoldsdk.

* Download the defoldsdk.zip from d.defold.com
* Extract the file to `${DYNAMO_HOME}/lib/x86_64-linux`:

    $ unzip -j defoldsdk.zip defoldsdk/lib/x86_64-linux/libdlib_shared.so -d ${DYNAMO_HOME}/lib/x86_64-linux

# Debug the Docker container

List all running containers:
```sh
docker ps -f name=extender --format "table {{.ID}}\t{{.Names}}"
```

Find container id you need and run following command
```sh
docker exec -uextender -it <container_id> /bin/bash
```

The command will connect to the container using the `extender` user, and executes bash.

# Debugging a job

## Preparation

* To use locally built SDK set `DYNAMO_HOME` variable before docker compose command. For example, 
```sh
    DYNAMO_HOME=/Users/user/work/defold/tmp/dynamo_home docker compose -f ./server/docker/docker-compose.yml --profile android up
```
* Run docker compose with following environment variables:
```sh
    DM_DEBUG_JOB_FOLDER=/dynamo_home/job123456 DM_DEBUG_COMMANDS=1 docker compose -f ./server/docker/docker-compose.yml --profile linux up
```
Set the `DM_DEBUG_JOB_FOLDER` to a static folder, which both your computer and the Docker container can reach.
    E.g. `DM_DEBUG_JOB_FOLDER=/dynamo_home/job123456`
    This will help when you debug the generated engine executable, since the debugger will find the object files on your local drive.

* After building, you'll find the output in the `$DM_DEBUG_JOB_FOLDER/build` folder

* Set up the debugger to point to the executable

* Set the working dir to the project directory

## Win32

Wine needs the WINEPATH to be set. Like so (taken from the sdk's build.yml):

    $ export WINEPATH="C:/Program Files (x86)/MSBuild/14.0/bin;C:/Program Files (x86)/Microsoft Visual Studio 14.0/Common7/IDE/;C:/Program Files (x86)/Microsoft Visual Studio 14.0/VC/BIN/x86_amd64;C:/Program Files (x86)/Microsoft Visual Studio 14.0/VC/BIN;C:/Program Files (x86)/Microsoft Visual Studio 14.0/Common7/Tools;C:/WINDOWS/Microsoft.NET/Framework/v4.0.30319;C:/Program Files (x86)/Microsoft Visual Studio 14.0/VC/VCPackages;C:/Program Files (x86)/HTML Help Workshop;C:/Program Files (x86)/Microsoft Visual Studio 14.0/Team Tools/Performance Tools;C:/ProgramFilesx86/WindowsKits/8.1/bin/x64;C:/Program Files (x86)/Microsoft SDKs/Windows/v10.0A/bin/NETFX 4.6.1 Tools/"

Now, you can cd into a job folder. E.g.:

    $ cd /tmp/job5423030039319877176/

And, next run the command line you got from the failing build log. E.g.:

    $ wine cl.exe /nologo /TP /O2 /Oy- /Z7 /MT /D__STDC_LIMIT_MACROS /DWINVER=0x0600 /D_WIN32_WINNT=0x0600 /DWIN32 /D_CRT_SECURE_NO_WARNINGS /wd4200 /W3 /EHsc /DDM_PLATFORM_WINDOWS /DLUA_BYTECODE_ENABLE   /Iupload/myextension/include/  /I/dynamo_home//include /I/dynamo_home//sdk/include /IC:/ProgramFilesx86/MicrosoftVisualStudio14.0/VC/INCLUDE /IC:/ProgramFilesx86/MicrosoftVisualStudio14.0/VC/ATLMFC/INCLUDE /IC:/ProgramFilesx86/WindowsKits/10/include/10.0.10240.0/ucrt /IC:/ProgramFilesx86/WindowsKits/NETFXSDK/4.6.1/include/um /IC:/ProgramFilesx86/WindowsKits/8.1/include/shared /IC:/ProgramFilesx86/WindowsKits/8.1/include/um /IC:/ProgramFilesx86/WindowsKits/8.1/include/winrt  upload/myextension/src/myextension.cpp /c /Fobuild/myextension.cpp_0.o

## Other platforms

Apart from the WINEPATH environment flag, the workflow is the same for all other supported platforms
