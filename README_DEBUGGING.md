
# Debugging with defoldsdk.zip

There is a debug script that you can run to download a specific SDK version, or the latest one.

    $ ./server/scripts/debug_defoldsdk.py [<sha1>]

This downloads the latest sdk to the folder `defoldsdk/<sha1>/defoldsdk`. It also sets the environment variable `DYNAMO_HOME` and then starts the extender server.


# Debugging individual command lines

## Preparation

In order to make sure the job folders aren't deleted by default, change the line in ExtenderController.java:

    // Delete temporary upload directory
    //FileUtils.deleteDirectory(jobDirectory);

And now build the container again:

    $ ./server/scripts/build.sh

## Login

After building your Docker container, you can login in using the script:

    $ ./server/scripts/debug.sh

Make sure you are `extender` by typing

    $ whoami
    extender


## Win32

Wine needs the WINEPATH to be set. Like so (taken from the sdk's build.yml):

    $ export WINEPATH="C:/Program Files (x86)/MSBuild/14.0/bin;C:/Program Files (x86)/Microsoft Visual Studio 14.0/Common7/IDE/;C:/Program Files (x86)/Microsoft Visual Studio 14.0/VC/BIN/x86_amd64;C:/Program Files (x86)/Microsoft Visual Studio 14.0/VC/BIN;C:/Program Files (x86)/Microsoft Visual Studio 14.0/Common7/Tools;C:/WINDOWS/Microsoft.NET/Framework/v4.0.30319;C:/Program Files (x86)/Microsoft Visual Studio 14.0/VC/VCPackages;C:/Program Files (x86)/HTML Help Workshop;C:/Program Files (x86)/Microsoft Visual Studio 14.0/Team Tools/Performance Tools;C:/ProgramFilesx86/WindowsKits/8.1/bin/x64;C:/Program Files (x86)/Microsoft SDKs/Windows/v10.0A/bin/NETFX 4.6.1 Tools/"

Now, you can cd into a job folder. E.g.:

    $ cd /tmp/job5423030039319877176/

And, next run the command line you got from the failing build log. E.g.:

    $ wine cl.exe /nologo /TP /O2 /Oy- /Z7 /MT /D__STDC_LIMIT_MACROS /DWINVER=0x0600 /D_WIN32_WINNT=0x0600 /DWIN32 /D_CRT_SECURE_NO_WARNINGS /wd4200 /W3 /EHsc /DDM_PLATFORM_WINDOWS /DLUA_BYTECODE_ENABLE   /Iupload/myextension/include/  /I/dynamo_home//include /I/dynamo_home//sdk/include /IC:/ProgramFilesx86/MicrosoftVisualStudio14.0/VC/INCLUDE /IC:/ProgramFilesx86/MicrosoftVisualStudio14.0/VC/ATLMFC/INCLUDE /IC:/ProgramFilesx86/WindowsKits/10/include/10.0.10240.0/ucrt /IC:/ProgramFilesx86/WindowsKits/NETFXSDK/4.6.1/include/um /IC:/ProgramFilesx86/WindowsKits/8.1/include/shared /IC:/ProgramFilesx86/WindowsKits/8.1/include/um /IC:/ProgramFilesx86/WindowsKits/8.1/include/winrt  upload/myextension/src/myextension.cpp /c /Fobuild/myextension.cpp_0.o

## Other platforms

Apart from the WINEPATHG environment flag, the workflow is the same for all other supported platforms