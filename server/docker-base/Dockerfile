# To solve the PID1 zombie process issue
# http://phusion.github.io/baseimage-docker/
# It derives from ubuntu:16.04
FROM phusion/baseimage:0.9.22

# Base stuff
RUN \
  dpkg --add-architecture i386 && \
  apt-get update && \
  apt-get install -y software-properties-common && \
  add-apt-repository -y ppa:webupd8team/java && \
  apt-get update && \
  echo oracle-java8-installer shared/accepted-oracle-license-v1-1 select true | debconf-set-selections && \
  apt-get install -y --no-install-recommends \
    oracle-java8-installer \
    gcc \
    g++ \
    libssl-dev \
    openssl \
    libtool \
    autoconf \
    automake \
    uuid-dev \
    libxi-dev \
    libopenal-dev \
    libgl1-mesa-dev \
    libglw1-mesa-dev \
    freeglut3-dev \
    unzip && \
  rm -rf /var/lib/apt/lists/* && \
  rm -rf /var/cache/oracle-jdk8-installer


# Add extender user
RUN  useradd -r -u 2222 extender && \
  mkdir -p /var/extender && \
  chown extender: /var/extender && \
  chown extender: $(readlink -f /usr/bin/java) && \
  chmod +s $(readlink -f /usr/bin/java)


ENV S3_URL https://s3-eu-west-1.amazonaws.com/defold-packages

# These packages are downloaded from here: http://releases.llvm.org/download.html
# and then uploaded as-is to S3
RUN wget -q -O - ${S3_URL}/clang%2Bllvm-6.0.0-x86_64-linux-gnu-ubuntu-16.04.tar.xz | tar xJ -C /usr/local --strip-components=1

# Darwin
RUN locale-gen en_US.UTF-8
ENV LANG en_US.UTF-8
ENV LANGUAGE en_US:en
ENV LC_ALL en_US.UTF-8

# The old install flow (Kept for backwards compatibility until all builds are done using darwin12)
# Reference for relating between version numbers: https://en.wikipedia.org/wiki/Darwin_(operating_system)
RUN \
  apt-get update && \
  apt-get install -y git && \
  cd /tmp && \
  git clone https://github.com/tpoechtrager/cctools-port.git && \
  cd cctools-port/cctools && \
  git checkout 84ce22dbb22a26ce7f392e9de0ee39c2efe6fd68 && \
  ./autogen.sh && \
  ./configure --prefix=/usr/local --target=arm-apple-darwin11 && \
  make -j8 && \
  make install && \
  make distclean && \
  ./autogen.sh && \
  ./configure --prefix=/usr/local --target=x86_64-apple-darwin11 && \
  make -j8 && \
  make install && \
  apt-get remove -y git

# The updated install flow
RUN \
  apt-get update && \
  apt-get install -y git cmake && \
  cd /tmp && \
  TPAPITMP=/tmp/tpapi && \
  mkdir $TPAPITMP && \
  git clone https://github.com/tpoechtrager/apple-libtapi.git && \
  cd apple-libtapi && \
  git checkout 19694a572cd9c0f6452a8e214682e74197370934 && \
  INSTALLPREFIX=$TPAPITMP ./build.sh && \
  ./install.sh && \
  git clone https://github.com/tpoechtrager/cctools-port.git && \
  cd cctools-port/cctools && \
  git checkout 22ebe727a5cdc21059d45313cf52b4882157f6f0 && \
  ./configure --prefix=/usr/local --target=arm-apple-darwin12 --with-libtapi=$TPAPITMP && \
  make -j8 && \
  make install && \
  make distclean && \
  ./autogen.sh && \
  ./configure --prefix=/usr/local --target=x86_64-apple-darwin12 --with-libtapi=$TPAPITMP && \
  make -j8 && \
  make install && \
  apt-get remove -y git cmake


#
# iOS + OSX
#
# For package instructions, see README_IOS.md

# Keeping iOS 10.3 and MacOSX10.2 around for a few sprints
RUN \
  wget -q -O - ${S3_URL}/iPhoneOS11.2.sdk.tar.gz | tar xz -C /opt && \
  mv /opt/iPhoneOS.sdk /opt/iPhoneOS11.2.sdk && \
  wget -q -O - ${S3_URL}/iPhoneOS10.3.sdk.tar.gz | tar xz -C /opt && \
  mv /opt/iPhoneOS.sdk /opt/iPhoneOS10.3.sdk && \
  wget -q -O - ${S3_URL}/MacOSX10.13.sdk.tar.gz | tar xz -C /opt && \
  mv /opt/MacOSX.sdk /opt/MacOSX10.13.sdk && \
  wget -q -O - ${S3_URL}/MacOSX10.12.sdk.tar.gz | tar xz -C /opt && \
  mv /opt/MacOSX.sdk /opt/MacOSX10.12.sdk && \
  ln -s /usr/local/bin/llvm-dsymutil /usr/local/bin/dsymutil && \
  ln -s /usr/local/bin/clang /usr/local/bin/arm-apple-darwin12-clang && \
  ln -s /usr/local/bin/clang /usr/local/bin/x86_64-apple-darwin12-clang && \
  ln -s /usr/local/bin/llvm-ar /usr/local/bin/x86_64-apple-darwin12-clang-ar && \
  ln -s /usr/local/bin/clang /usr/local/bin/x86_64-pc-win32-clang && \
  ln -s /usr/local/bin/llvm-ar /usr/local/bin/x86_64-pc-win32-clang-ar

#
# EMSCRIPTEN
#

RUN apt-get autoclean && \
  apt-get install -y --no-install-recommends python2.7 && \
  ln -s /usr/bin/python2.7 /usr/local/bin/python && \
  ln -s /usr/bin/python2.7 /usr/local/bin/python2

ENV EMSCRIPTEN_SDK /opt/emsdk
ENV EMSCRIPTEN_HOME ${EMSCRIPTEN_SDK}/bin/emsdk_portable
ENV EMSCRIPTEN_CONFIG ${EMSCRIPTEN_HOME}/.emscripten
# This should really be a version number in the future, but it was somehow built with "master" as the folder name
ENV EMSCRIPTEN_VERSION master
# Where the embedded config file ends up
ENV EM_CONFIG ${EMSCRIPTEN_CONFIG}
ENV EMCC_SKIP_SANITY_CHECK 1
# Add emscripten to the path
ENV PATH ${PATH}:${EMSCRIPTEN_HOME}:${EMSCRIPTEN_HOME}/clang/fastcomp/build_master_64/bin:${EMSCRIPTEN_HOME}/node/4.1.1_64bit/bin:${EMSCRIPTEN_HOME}/emscripten/${EMSCRIPTEN_VERSION}

# The "sed" command below removes the /TEMP_DIR line from the generated config
# We replace it with a folder of our own
RUN \
  mkdir ${EMSCRIPTEN_SDK} && \
  wget -q -O - ${S3_URL}/emsdk-1.35.23-x86_64-linux.tar.gz | tar xz -C ${EMSCRIPTEN_SDK} && \
  ${EMSCRIPTEN_HOME}/emsdk activate --embedded && \
  sed '/TEMP_DIR =/d' ${EMSCRIPTEN_CONFIG} && \
  echo TEMP_DIR = \'/var/extender/builds\' >> ${EMSCRIPTEN_CONFIG} && \
  chmod -R 755 ${EMSCRIPTEN_HOME}

#
# Windows
#

# windres: Allows for generating .res files that can be used during linking
RUN \
  apt-get update && \
  apt-get install -y apt-transport-https mingw-w64 && \
  ln -s /usr/bin/x86_64-w64-mingw32-windres /usr/local/bin/windres

# Installation notes: https://wiki.winehq.org/Ubuntu
RUN \
  wget https://dl.winehq.org/wine-builds/Release.key && \
  apt-key add Release.key && \
  rm Release.key && \
  apt-add-repository https://dl.winehq.org/wine-builds/ubuntu/ && \
  dpkg --add-architecture i386 && \
  apt-get update && \
  apt-get install -y --install-recommends wine-stable

ENV PATH ${PATH}:/opt/wine-stable/bin

ENV PROGRAM_FILES "C:/Program Files (x86)"

# Grabbed after a call to <VCINSTALLDIR>/bin/x86_amd64\vcvarsx86_amd64.bat (Can probably be trimmed further)
ENV VS140COMNTOOLS "${PROGRAM_FILES}/Microsoft Visual Studio 14.0/Common7/Tools/"
ENV VCINSTALLDIR "${PROGRAM_FILES}/Microsoft Visual Studio 14.0/VC/"
ENV VSINSTALLDIR "${PROGRAM_FILES}/Microsoft Visual Studio 14.0/"
ENV WindowsLibPath "${PROGRAM_FILES}/Windows Kits/8.1/References/CommonConfiguration/Neutral"
ENV WindowsSdkDir "${PROGRAM_FILES}/Windows Kits/8.1/"
ENV WindowsSDKLibVersion "winv6.3/"
ENV WindowsSDKVersion "/"
ENV WindowsSDK_ExecutablePath_x64 "${PROGRAM_FILES}/Microsoft SDKs/Windows/v10.0A/bin/NETFX 4.6.1 Tools/x64/"
ENV WindowsSDK_ExecutablePath_x86 "${PROGRAM_FILES}/Microsoft SDKs/Windows/v10.0A/bin/NETFX 4.6.1 Tools/"

# Disable all debug messages
ENV WINEDEBUG "-all"

# Set the wine home to be in a folder that extender will own (Or you'll get the "wine: <path> is not owned by you")
ENV WINEPREFIX "/var/extender/.wine"

# Initialize Wine (also see next Dockerfile)
# Setup symbolic links for folders to exist in the C:/ space
# Also, for the installed packages, create symlinks without spaces in the paths!
# Once we run wine, the folder is created for the root user, this needs to be chown'ed back to the extender user (see next Dockerfile)

USER extender

RUN \
  mkdir -p ${WINEPREFIX} && \
  wine cmd /c echo Initialized Wine folder && \
  chmod -R 755 ${WINEPREFIX} && \
  ln -s /tmp ${WINEPREFIX}/drive_c/tmp && \
  ln -s ${WINEPREFIX}/drive_c/Program\ Files\ \(x86\) ${WINEPREFIX}/drive_c/ProgramFilesx86 && \
  mkdir ${WINEPREFIX}/drive_c/ProgramFilesx86/WindowsKits && \
  wget -q -O - ${S3_URL}/WindowsKits-8.1.tar.gz | tar xz -C ${WINEPREFIX}/drive_c/ProgramFilesx86/WindowsKits && \
  wget -q -O - ${S3_URL}/WindowsKits-10.0.tar.gz | tar xz -C ${WINEPREFIX}/drive_c/ProgramFilesx86/WindowsKits && \
  wget -q -O - ${S3_URL}/Microsoft-Visual-Studio-14-0.tar.gz | tar xz -C ${WINEPREFIX}/drive_c/ProgramFilesx86 && \
  ln -s ${WINEPREFIX}/drive_c/ProgramFilesx86/Microsoft\ Visual\ Studio\ 14.0 ${WINEPREFIX}/drive_c/ProgramFilesx86/MicrosoftVisualStudio14.0

USER root

RUN apt-get update && \
    apt-get install -y \
    gcc-multilib \
    g++-multilib

#
# Android SDK/NDK
#
ENV ANDROID_ROOT /opt/android
ENV ANDROID_TOOLS_FILENAME sdk-tools-linux-3859397.zip
ENV ANDROID_TOOLS_URL https://dl.google.com/android/repository/${ANDROID_TOOLS_FILENAME}

ENV ANDROID_BUILD_TOOLS_VERSION 23.0.2
ENV ANDROID_HOME ${ANDROID_ROOT}/android-sdk-linux

ENV ANDROID_NDK_VERSION 10e
ENV ANDROID_NDK_API_VERSION 14
ENV ANDROID_TARGET_API_LEVEL 23
ENV ANDROID_MIN_API_LEVEL 9
ENV ANDROID_GCC_VERSION 4.8
ENV ANDROID_NDK_PATH ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}
ENV ANDROID_NDK_INCLUDE ${ANDROID_ROOT}/tmp/android-ndk-r${ANDROID_NDK_VERSION}/platforms/android-${ANDROID_NDK_API_VERSION}/arch-arm/usr/include
ENV ANDROID_STL_INCLUDE ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}/sources/cxx-stl/gnu-libstdc++/${ANDROID_GCC_VERSION}/include
ENV ANDROID_STL_ARCH_INCLUDE ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}/sources/cxx-stl/gnu-libstdc++/${ANDROID_GCC_VERSION}/libs/armeabi-v7a/include
ENV ANDROID_STL_LIB ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}/sources/cxx-stl/gnu-libstdc++/${ANDROID_GCC_VERSION}/libs/armeabi-v7a
ENV ANDROID_SYSROOT ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}/platforms/android-${ANDROID_NDK_API_VERSION}/arch-arm
ENV ANDROID_BIN_PATH ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}/toolchains/arm-linux-androideabi-${ANDROID_GCC_VERSION}/prebuilt/linux-x86_64/bin
ENV ANDROID_SDK_BUILD_TOOLS_PATH ${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS_VERSION}

ENV PATH ${PATH}:${ANDROID_HOME}/tools:${ANDROID_HOME}/platform-tools:${ANDROID_BIN_PATH}:${ANDROID_SDK_BUILD_TOOLS_PATH}

ENV ANDROID_NDK_FILENAME android-ndk-r${ANDROID_NDK_VERSION}-linux-x86_64.zip
ENV ANDROID_NDK_URL https://dl.google.com/android/repository/${ANDROID_NDK_FILENAME}

RUN mkdir -p ${ANDROID_HOME} && \
    cd ${ANDROID_ROOT} && \
    wget -q ${ANDROID_NDK_URL} && \
    chmod +x ${ANDROID_NDK_FILENAME} && \
    unzip -q ${ANDROID_NDK_FILENAME} && \
    chmod +r -R ${ANDROID_ROOT} && \
    rm ${ANDROID_NDK_FILENAME} && \
    chmod -R 755 ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION} && \
    cd ${ANDROID_HOME} && \
    echo ${ANDROID_TOOLS_URL} && \
    wget ${ANDROID_TOOLS_URL} && \
    unzip -q ${ANDROID_TOOLS_FILENAME} && \
    rm ${ANDROID_TOOLS_FILENAME} && \
    chown -R extender: ${ANDROID_HOME}

# The sdk manager wants user access folder
USER extender
RUN cd ${ANDROID_HOME} && \
    echo y | ./tools/bin/sdkmanager --verbose "tools" "platform-tools" "extras;android;m2repository" "platforms;android-${ANDROID_TARGET_API_LEVEL}" "build-tools;${ANDROID_BUILD_TOOLS_VERSION}" && \
    ls -la ./build-tools/${ANDROID_BUILD_TOOLS_VERSION}/dx # make sure it exists!

# Remove the rights again from the extender user
USER root
RUN cd ${ANDROID_HOME} && \
    chown -R root: ${ANDROID_HOME}

# Due to Windows' case insensitive file system, the sources reference lib files with wrong cases
# so we solve the bulk by making the suffixes lowercase
RUN find $WINEPREFIX -iname '*.Lib' -exec sh -c 'a=$(echo "$0" | sed -r "s/([^.]*)\$/\L\1/"); [ "$a" != "$0" ] && mv "$0" "$a" ' {} \;
# Make a copy of all the headers too, in lower case (e.g. Windows.h -> windows.h etc)
RUN find $WINEPREFIX -iname '*.h' -exec sh -c 'd=$(dirname "$0"); a=$(basename "$0" | tr [:upper:] [:lower:]); [ "$a" != $(basename "$0") ] && cp "$0" "$d/$a" ' {} \;

# and the rest are manually copied (or made lower case)
RUN (cd ${WINEPREFIX}/drive_c/ProgramFilesx86/MicrosoftVisualStudio14.0/VC/lib/amd64 && cp oldnames.lib OLDNAMES.lib) && \
    (cd ${WINEPREFIX}/drive_c/ProgramFilesx86/MicrosoftVisualStudio14.0/VC/lib && cp oldnames.lib OLDNAMES.lib) && \
    (cd ${WINEPREFIX}/drive_c/ProgramFilesx86/MicrosoftVisualStudio14.0/VC/lib/amd64 && cp delayimp.lib Delayimp.lib) && \
    (cd ${WINEPREFIX}/drive_c/ProgramFilesx86/MicrosoftVisualStudio14.0/VC/lib && cp delayimp.lib Delayimp.lib) && \
    (cd ${WINEPREFIX}/drive_c/ProgramFilesx86/WindowsKits/8.1/Include/shared && cp driverspecs.h DriverSpecs.h) && \
    (cd ${WINEPREFIX}/drive_c/ProgramFilesx86/WindowsKits/8.1/Include/shared && cp specstrings.h SpecStrings.h) && \
    (cd ${WINEPREFIX}/drive_c/ProgramFilesx86/WindowsKits/8.1/Lib/winv6.3/um/x64 && find . -type f -exec sh -c 'x="{}"; xl=$(echo $x | sed -e "s/\(.*\)/\L\1/"); if [ $x != $xl ]; then cp $x $xl; fi' \;) && \
    (cd ${WINEPREFIX}/drive_c/ProgramFilesx86/WindowsKits/8.1/Lib/winv6.3/um/x86 && find . -type f -exec sh -c 'x="{}"; xl=$(echo $x | sed -e "s/\(.*\)/\L\1/"); if [ $x != $xl ]; then cp $x $xl; fi' \;)
