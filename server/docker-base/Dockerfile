FROM ubuntu:16.04

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

# Android SDK/NDK
ENV ANDROID_ROOT /opt/android
ENV ANDROID_SDK_FILENAME android-sdk_r24.3.3-linux.tgz
ENV ANDROID_SDK_URL http://dl.google.com/android/${ANDROID_SDK_FILENAME}
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
ENV ANDROID_SDK_BUILD_TOOLS_PATH ${ANDROID_ROOT}/android-sdk/build-tools/${ANDROID_BUILD_TOOLS_VERSION}

ENV PATH ${PATH}:${ANDROID_HOME}/tools:${ANDROID_HOME}/platform-tools:${ANDROID_BIN_PATH}:${ANDROID_SDK_BUILD_TOOLS_PATH}

ENV ANDROID_NDK_FILENAME android-ndk-r${ANDROID_NDK_VERSION}-linux-x86_64.zip
ENV ANDROID_NDK_URL https://dl.google.com/android/repository/${ANDROID_NDK_FILENAME}

RUN mkdir -p cd ${ANDROID_ROOT} && \
    cd ${ANDROID_ROOT} && \
    wget -q -O - ${ANDROID_SDK_URL} | tar -zxvf - && \
    echo y | android update sdk --no-ui -a --filter tools,platform-tools,android-${ANDROID_TARGET_API_LEVEL},build-tools-${ANDROID_BUILD_TOOLS_VERSION} && \
    ln -s android-sdk-linux android-sdk && \
    wget -q ${ANDROID_NDK_URL} && \
    chmod +x ${ANDROID_NDK_FILENAME} && \
    unzip ${ANDROID_NDK_FILENAME} && \
    chmod +r -R ${ANDROID_ROOT} && \
    rm ${ANDROID_NDK_FILENAME} && \
    chmod -R 755 ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}

ENV S3_URL https://s3-eu-west-1.amazonaws.com/defold-packages

RUN wget -q -O - ${S3_URL}/clang%2Bllvm-3.9.0-x86_64-linux-gnu-ubuntu-16.04.tar.xz | tar xJ -C /usr/local --strip-components=1

# Darwin
RUN locale-gen en_US.UTF-8
ENV LANG en_US.UTF-8
ENV LANGUAGE en_US:en
ENV LC_ALL en_US.UTF-8

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

# Right now, the package contains iPhoneOS.sdk, in the future, we want iPhoneOS9.3.sdk
RUN \
  wget -q -O - ${S3_URL}/iPhoneOS9.3.sdk.tar.gz | tar xz -C /opt && \
  mv /opt/iPhoneOS.sdk /opt/iPhoneOS9.3.sdk && \
  ln -s /opt/iPhoneOS9.3.sdk /opt/iPhoneOS.sdk && \
  wget -q -O - ${S3_URL}/MacOSX10.11.sdk.tar.gz | tar xz -C /opt && \
  ln -s /usr/local/bin/llvm-dsymutil /usr/local/bin/dsymutil && \
  ln -s /usr/local/bin/clang /usr/local/bin/arm-apple-darwin11-clang && \
  ln -s /usr/local/bin/clang /usr/local/bin/x86_64-apple-darwin11-clang && \
  ln -s /usr/local/bin/llvm-ar /usr/local/bin/x86_64-apple-darwin11-clang-ar

# EMSCRIPTEN
RUN apt-get install -y --no-install-recommends python2.7 && \
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
# We replace it with a folder ow our own
RUN \
  mkdir ${EMSCRIPTEN_SDK} && \
  wget -q -O - ${S3_URL}/emsdk-1.35.23-x86_64-linux.tar.gz | tar xz -C ${EMSCRIPTEN_SDK} && \
  ${EMSCRIPTEN_HOME}/emsdk activate --embedded && \
  sed '/TEMP_DIR =/d' ${EMSCRIPTEN_CONFIG} && \
  echo TEMP_DIR = \'/var/extender/builds\' >> ${EMSCRIPTEN_CONFIG} && \
  chmod -R 755 ${EMSCRIPTEN_HOME}


# Windows
RUN \
  apt-get install apt-transport-https

# According to some notes, wine 1.8 is needed for Visual Studio 2015, and Ubuntu 14 only has wine 1.4
RUN \
  wget https://dl.winehq.org/wine-builds/Release.key && \
  apt-key add Release.key && \
  rm Release.key && \
  apt-add-repository https://dl.winehq.org/wine-builds/ubuntu/ && \
  dpkg --add-architecture i386 && \
  apt-get update && \
  apt-get install -y --install-recommends wine-devel

ENV PATH ${PATH}:/opt/wine-devel/bin

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

