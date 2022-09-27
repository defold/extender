FROM ubuntu:20.04

RUN \
  echo "LINUX TOOLS + COMPILER" && \
  apt-get update && \
  apt-get install -y software-properties-common && \
  add-apt-repository ppa:openjdk-r/ppa && \
  apt-get update && \
  apt-get install -y --no-install-recommends \
    wget \
    locales \
    openjdk-11-jdk \
    libssl-dev \
    openssl \
    libtool \
    autoconf \
    automake \
    build-essential \
    uuid-dev \
    libxi-dev \
    libopenal-dev \
    libgl1-mesa-dev \
    libglw1-mesa-dev \
    freeglut3-dev \
    unzip \
    # for use when debugging
    tree \
    silversearcher-ag \
    less \
    nano \
    && \
# cleanup
  apt-get clean autoclean autoremove

ENV JAVA_HOME /usr/lib/jvm/java-11-openjdk-amd64

# Add extender user
RUN  useradd -r -u 2222 extender && \
  mkdir -p /var/extender && \
  chown extender: /var/extender && \
  chown extender: $(readlink -f /usr/bin/java) && \
  chmod +s $(readlink -f /usr/bin/java)

ARG DM_PACKAGES_URL

# Put all SDK's into a single folder (just as we do in the regular engine build)
ENV PLATFORMSDK_DIR /opt/platformsdk
RUN mkdir $PLATFORMSDK_DIR

# These packages are downloaded from here: https://github.com/llvm/llvm-project/releases/
# and then uploaded as-is to S3
RUN wget -q -O - ${DM_PACKAGES_URL}/clang%2Bllvm-13.0.0-x86_64-linux-gnu-ubuntu-20.04.tar.xz | tar xJ -C /usr/local --strip-components=1

# Darwin
RUN locale-gen en_US.UTF-8
ENV LANG=en_US.UTF-8 \
    LANGUAGE=en_US:en \
    LC_ALL=en_US.UTF-8

#
# Python
#

# NOTE: setuptools for protobuf builder

RUN \
  echo "PYTHON" && \
  apt-get autoclean && \
  apt-get update && \
  apt-get install -y --no-install-recommends python2.7 python-setuptools && \
  ln -s /usr/bin/python2.7 /usr/local/bin/python && \
  ln -s /usr/bin/python2.7 /usr/local/bin/python2

#
# EMSCRIPTEN
#

ENV EMCC_SKIP_SANITY_CHECK 1

## Emscripten 2.0.11 (from version 1.2.178)

# Ubuntu still seem to use python3 3.8 as default version
RUN \
  add-apt-repository ppa:deadsnakes/ppa && \
  apt-get autoclean && \
  apt-get update && \
  apt-get clean autoclean autoremove && \
  apt-get install -y --no-install-recommends python3.9 && \
  update-alternatives --install /usr/bin/python3 python3 /usr/bin/python3.8 1 && \
  update-alternatives --install /usr/bin/python3 python3 /usr/bin/python3.9 2 && \
  python3 --version

ENV EMSCRIPTEN_SDK_2_0_11 ${PLATFORMSDK_DIR}/emsdk-2.0.11
ENV EMSCRIPTEN_HOME_2_0_11 ${EMSCRIPTEN_SDK_2_0_11}
ENV EMSCRIPTEN_CONFIG_2_0_11 ${EMSCRIPTEN_HOME_2_0_11}/.emscripten
ENV EMSCRIPTEN_BIN_2_0_11 ${EMSCRIPTEN_HOME_2_0_11}/upstream/emscripten
ENV EMSCRIPTEN_CACHE_2_0_11 /var/extender/emcache_2_0_11
ENV EMSCRIPTEN_PYTHON_2_0_11 /usr/bin/python3.9
# Setup a special env variable that will be prefixed to PATH if requested version is 2.0.11
ENV EMSCRIPTEN_PATH_2_0_11 ${EMSCRIPTEN_HOME_2_0_11}:${EMSCRIPTEN_HOME_2_0_11}/upstream/bin:${EMSCRIPTEN_HOME_2_0_11}/node/12.9.1_64bit/bin:${EMSCRIPTEN_BIN_2_0_11}

RUN \
  mkdir ${EMSCRIPTEN_SDK_2_0_11} && \
  wget -q -O - ${DM_PACKAGES_URL}/emsdk-2.0.11-linux.tar.gz | tar xz -C ${EMSCRIPTEN_SDK_2_0_11} --strip-components=1

RUN \
  ${EMSCRIPTEN_HOME_2_0_11}/emsdk activate sdk-2.0.11-64bit --embedded && \
  EM_CONFIG=$EMSCRIPTEN_CONFIG_2_0_11 EM_CACHE=${EMSCRIPTEN_CACHE_2_0_11} python3 ${EMSCRIPTEN_BIN_2_0_11}/embuilder.py build SYSTEM MINIMAL && \
  chmod -R 755 ${EMSCRIPTEN_HOME_2_0_11} && \
  chown -R extender: ${EMSCRIPTEN_CACHE_2_0_11} && \
  chown -R extender: ${EMSCRIPTEN_CACHE_2_0_11}/wasm/cache.lock


# We use the same temp directory for both versions.
ENV EMSCRIPTEN_TEMP_DIR /var/extender/ems_temp
RUN mkdir -p ${EMSCRIPTEN_TEMP_DIR}
RUN chmod -R 755 ${EMSCRIPTEN_TEMP_DIR} && chown extender: ${EMSCRIPTEN_TEMP_DIR}
# The "sed" command below removes the /TEMP_DIR line from the generated configs
# We replace it with a folder of our own
RUN sed '/TEMP_DIR =/d' ${EMSCRIPTEN_CONFIG_2_0_11} && \
  echo TEMP_DIR = \'${EMSCRIPTEN_TEMP_DIR}\' >> ${EMSCRIPTEN_CONFIG_2_0_11}
  #  && \
  # sed '/TEMP_DIR =/d' ${EMSCRIPTEN_CONFIG_1_39_16} && \
  # echo TEMP_DIR = \'${EMSCRIPTEN_TEMP_DIR}\' >> ${EMSCRIPTEN_CONFIG_1_39_16}

RUN \
  update-alternatives --set python3 /usr/bin/python3.8


#
# Windows
#

ENV \
  PLATFORMSDK_WIN32=$PLATFORMSDK_DIR/Win32 \
  WINDOWS_SDK_10_VERSION=10.0.18362.0 \
  WINDOWS_MSVC_2019_VERSION=14.25.28610

# Grabbed after a starting MSVC 2019, and choosing "Tools -> Command Line -> Developer Command Prompt"
# Note: VCINSTALLDIR is special since clang will use it as the last "-internal-isystem" option
ENV \
  VCINSTALLDIR="${PLATFORMSDK_WIN32}/MicrosoftVisualStudio2019/VC/" \
  VSINSTALLDIR="${PLATFORMSDK_WIN32}/MicrosoftVisualStudio2019/" \
  WINDOWS_MSVC_2019_DIR="${PLATFORMSDK_WIN32}/MicrosoftVisualStudio2019/VC/Tools/MSVC/${WINDOWS_MSVC_2019_VERSION}/" \
  WINDOWS_SDK_10_DIR="${PLATFORMSDK_WIN32}/WindowsKits/10/" \
  VS160COMNTOOLS="${PLATFORMSDK_WIN32}/MicrosoftVisualStudio2019/Common7/Tools/" \
  WINDOWS_VCINSTALLDIR="${PLATFORMSDK_WIN32}/MicrosoftVisualStudio2019/VC/Tools/MSVC/${WINDOWS_MSVC_2019_VERSION}/" \
  WINDOWS_VSINSTALLDIR="${PLATFORMSDK_WIN32}/MicrosoftVisualStudio2019/" \
  WindowsLibPath="${PLATFORMSDK_WIN32}/WindowsKits/10/References/${WINDOWS_SDK_10_VERSION}" \
  WindowsSdkDir="${PLATFORMSDK_WIN32}/WindowsKits/10/" \
  WindowsSDKLibVersion="${WINDOWS_SDK_10_VERSION}" \
  WindowsSDKVersion="${WINDOWS_SDK_10_VERSION}"

# windres: Allows for generating .res files that can be used during linking
RUN \
  echo "Win32 SDK - WINDRES" && \
  apt-get update && \
  apt-get install -y binutils-mingw-w64-x86-64 && \
  ls -la /usr/bin/x86_64-w64-mingw32-windres && \
  ln -s /usr/bin/x86_64-w64-mingw32-windres /usr/local/bin/windres

RUN \
  echo "WIN32 2019 SDK" && \
  mkdir -p ${PLATFORMSDK_WIN32}/MicrosoftVisualStudio2019 && \
  wget -q -O - ${DM_PACKAGES_URL}/Microsoft-Visual-Studio-2019-${WINDOWS_MSVC_2019_VERSION}.tar.gz | tar xz -C ${PLATFORMSDK_WIN32}/MicrosoftVisualStudio2019

RUN \
  echo "WIN32 10 SDK" && \
  mkdir -p ${PLATFORMSDK_WIN32}/WindowsKits && \
  wget -q -O - ${DM_PACKAGES_URL}/WindowsKits-${WINDOWS_SDK_10_VERSION}.tar.gz | tar xz -C ${PLATFORMSDK_WIN32}/WindowsKits

RUN \
  ln -s /usr/local/bin/clang /usr/local/bin/x86_64-pc-win32-clang && \
  ln -s /usr/local/bin/llvm-ar /usr/local/bin/x86_64-pc-win32-clang-ar

# Due to Windows' case insensitive file system, the sources reference lib files with wrong cases
# so we solve the bulk by making the suffixes lowercase
RUN find $PLATFORMSDK_WIN32 -iname '*.Lib' -exec sh -c 'a=$(echo "$0" | sed -r "s/([^.]*)\$/\L\1/"); [ "$a" != "$0" ] && mv "$0" "$a" ' {} \;
# Make a copy of all the headers too, in lower case (e.g. Windows.h -> windows.h etc)
RUN find $PLATFORMSDK_WIN32 -iname '*.h' -exec sh -c 'd=$(dirname "$0"); a=$(basename "$0" | tr [:upper:] [:lower:]); [ "$a" != $(basename "$0") ] && cp "$0" "$d/$a" ' {} \;

RUN \
  echo "WIN32 SDK - Cleanup" && \
# and the rest are manually copied (or made lower case)
  (cd ${WINDOWS_MSVC_2019_DIR}/lib/x64 && cp oldnames.lib OLDNAMES.lib) && \
  (cd ${WINDOWS_MSVC_2019_DIR}/lib/x86 && cp oldnames.lib OLDNAMES.lib) && \
  (cd ${WINDOWS_MSVC_2019_DIR}/lib/x64 && cp delayimp.lib Delayimp.lib) && \
  (cd ${WINDOWS_MSVC_2019_DIR}/lib/x86 && cp delayimp.lib Delayimp.lib)

# Some headers are named by the wrong name in the windows sdk's...
# We need to make certain names lowercase because some users
# have put "pragma lib" comments in some libraries :(
RUN \
  echo "WIN32 WindowsKits 10 - Cleanup" && \
  (cd ${WINDOWS_SDK_10_DIR}/Include/${WINDOWS_SDK_10_VERSION}/shared && cp driverspecs.h DriverSpecs.h) && \
  (cd ${WINDOWS_SDK_10_DIR}/Include/${WINDOWS_SDK_10_VERSION}/shared && cp specstrings.h SpecStrings.h) && \
  (cd ${WINDOWS_SDK_10_DIR}/Include/${WINDOWS_SDK_10_VERSION}/shared && cp concurrencysal.h ConcurrencySal.h) && \
  (cd ${WINDOWS_SDK_10_DIR}/Lib/${WINDOWS_SDK_10_VERSION} && find . -type f -exec sh -c 'x="{}"; xl=$(echo $x | sed -e "s/\(.*\)/\L\1/"); if [ $x != $xl ]; then cp $x $xl; fi' \;)

# Also, the OpenGL headers in the windows SDK is in a folder with lower case letters, which doesn't match the includes
RUN \
  echo "WIN32 WindowsKits 10 - OpenGL Cleanup" && \
  cd ${WINDOWS_SDK_10_DIR}/Include/${WINDOWS_SDK_10_VERSION}/um && \
  mkdir ./GL && \
  cp -v ./gl/*.* ./GL/

#
# Android SDK/NDK
# https://developer.android.com/studio/command-line/variables
#
ENV ANDROID_ROOT ${PLATFORMSDK_DIR}/android
ENV ANDROID_BUILD_TOOLS_VERSION 32.0.0
# ANDROID_HOME has been replaced with ANDROID_SDK_ROOT
ENV ANDROID_HOME ${ANDROID_ROOT}/android-sdk-linux
ENV ANDROID_SDK_ROOT ${ANDROID_HOME}
# ANDROID_SDK_HOME is the location of the .android folder
ENV ANDROID_SDK_HOME ${ANDROID_ROOT}/.android
ENV ANDROID_TARGET_API_LEVEL 31
ENV ANDROID_MIN_API_LEVEL 16
ENV ANDROID_GCC_VERSION 4.8
ENV ANDROID_SDK_VERSION 31
ENV ANDROID_SDK_FILENAME android-sdk-linux-android-${ANDROID_SDK_VERSION}-${ANDROID_BUILD_TOOLS_VERSION}.tar.gz
ENV ANDROID_STL_INCLUDE ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}/sources/cxx-stl/gnu-libstdc++/${ANDROID_GCC_VERSION}/include
ENV ANDROID_STL_ARCH_INCLUDE ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}/sources/cxx-stl/gnu-libstdc++/${ANDROID_GCC_VERSION}/libs/armeabi-v7a/include
ENV ANDROID_STL_LIB ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}/sources/cxx-stl/gnu-libstdc++/${ANDROID_GCC_VERSION}/libs/armeabi-v7a
ENV ANDROID_SYSROOT ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}/platforms/android-${ANDROID_NDK_API_VERSION}/arch-arm
ENV ANDROID_BIN_PATH ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}/toolchains/arm-linux-androideabi-${ANDROID_GCC_VERSION}/prebuilt/linux-x86_64/bin
ENV ANDROID_SDK_BUILD_TOOLS_PATH ${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS_VERSION}
ENV ANDROID_LIBRARYJAR ${ANDROID_HOME}/platforms/android-${ANDROID_SDK_VERSION}/android.jar

# We must keep two NDKs alive for now, since migrating to the latest (i.e 20) will break
# for users that try to build with an older build.yml.
ENV ANDROID_NDK20_VERSION        20
ENV ANDROID_NDK20_API_VERSION    16
ENV ANDROID_64_NDK20_API_VERSION 21

# These paths are the same for both the 32 and 64 bit toolchains
ENV ANDROID_NDK20_PATH       ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK20_VERSION}
ENV ANDROID_NDK20_BIN_PATH   ${ANDROID_NDK20_PATH}/toolchains/llvm/prebuilt/linux-x86_64/bin
ENV ANDROID_NDK20_SYSROOT    ${ANDROID_NDK20_PATH}/toolchains/llvm/prebuilt/linux-x86_64/sysroot
ENV ANDROID_NDK20_STL_LIB    ${ANDROID_NDK20_PATH}/sources/cxx-stl/llvm-libc++/libs/armeabi-v7a
ENV ANDROID_64_NDK20_STL_LIB ${ANDROID_NDK20_PATH}/sources/cxx-stl/llvm-libc++/libs/arm64-v8a
ENV ANDROID_NDK20_FILENAME   android-ndk-r${ANDROID_NDK20_VERSION}-linux-x86_64.tar.gz

ENV AAPT ${ANDROID_SDK_BUILD_TOOLS_PATH}/aapt

ENV PATH ${PATH}:${ANDROID_HOME}/tools:${ANDROID_HOME}/platform-tools:${ANDROID_SDK_BUILD_TOOLS_PATH}:${ANDROID_NDK20_BIN_PATH}

# android proguard was version 4.7, this is at least 5.2.1 which seems to work with OpenJDK 11
RUN \
  apt-get update && \
  apt-get install -y --no-install-recommends proguard && \
  ls -la /usr/share/java/proguard.jar

ENV ANDROID_PROGUARD=/usr/share/java/proguard.jar

RUN \
    echo "Android SDK" && \
    mkdir -p ${ANDROID_HOME} && \
    wget -q -O - ${DM_PACKAGES_URL}/${ANDROID_NDK20_FILENAME} | tar xz -C ${ANDROID_ROOT} && \
    wget -q -O - ${DM_PACKAGES_URL}/${ANDROID_SDK_FILENAME} | tar xz -C ${ANDROID_HOME} --strip-components=1 && \
#cleanup
    rm -rf ${ANDROID_NDK20_PATH}/simpleperf && \
    rm -rf ${ANDROID_NDK20_PATH}/shader-tools && \
    rm -rf ${ANDROID_NDK20_PATH}/sources/third_party && \
    rm -rf ${ANDROID_NDK20_PATH}/sources/cxx-stl && \
    rm -rf ${ANDROID_NDK20_PATH}/toolchains/renderscript && \
    rm -rf ${ANDROID_NDK20_PATH}/toolchains/arm-linux-androideabi-4.9 && \
    rm -rf ${ANDROID_NDK20_PATH}/toolchains/aarch64-linux-android-4.9 && \
    rm -rf ${ANDROID_NDK20_PATH}/toolchains/x86-4.9 && \
    rm -rf ${ANDROID_NDK20_PATH}/toolchains/x86_64-4.9 && \
    rm -rf ${ANDROID_NDK20_PATH}/toolchains/llvm/prebuilt/linux-x86_64/i686-linux-android && \
    rm -rf ${ANDROID_NDK20_PATH}/toolchains/llvm/prebuilt/linux-x86_64/x86_64-linux-android && \
    rm -rf ${ANDROID_NDK20_PATH}/toolchains/llvm/prebuilt/linux-x86_64/bin/i686-* && \
    rm -rf ${ANDROID_NDK20_PATH}/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-* && \
    rm -rf ${ANDROID_NDK20_PATH}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/x86_64-linux-android && \
    rm -rf ${ANDROID_NDK20_PATH}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/i686-linux-android && \
# create the .android folder and give read+write permissions (the Android Gradle plugin will write to the folder)
# It is not enough to give 'user' and 'group'. We unfortunately also need 'others'
    mkdir ${ANDROID_SDK_HOME} && \
    chmod ugo+rw -R ${ANDROID_SDK_HOME} && \
# fix permissions
    chmod +r -R ${ANDROID_ROOT} && \
    chmod +w -R ${ANDROID_SDK_ROOT} && \
    chmod -R 755 ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK20_VERSION} && \
# check that dx installed properly
    ls -la ${ANDROID_SDK_BUILD_TOOLS_PATH}/d8 && \
    which armv7a-linux-androideabi${ANDROID_NDK20_API_VERSION}-clang++ && \
    which aarch64-linux-android${ANDROID_64_NDK20_API_VERSION}-clang++ && \
# check that aapt installed correctly
    aapt v

#
# Switch SDK
#

# Added in version 1.3.3
ENV NINTENDO_SDK_ROOT_14_3_0 ${PLATFORMSDK_DIR}/nx-14.3.0

ENV SWITCH_SDK_14_FILENAME nx64-sdk-14.3.0.tar.gz

RUN \
  echo "Switch SDK 14.3.0" && \
  mkdir -p ${NINTENDO_SDK_ROOT_14_3_0} && \
  wget -q -O - ${DM_PACKAGES_URL}/${SWITCH_SDK_14_FILENAME} | tar xz -C ${NINTENDO_SDK_ROOT_14_3_0}


#
# PS4 SDK
#

ENV PS4_SDK_9_000 ${PLATFORMSDK_DIR}/ps4-sdk-9.000

ENV PS4_SDK_9_FILENAME ps4-sdk-9.000.tar.gz

RUN \
  echo "PS4 SDK 9.000" && \
  mkdir -p ${PS4_SDK_9_000} && \
  wget -q -O - ${DM_PACKAGES_URL}/${PS4_SDK_9_FILENAME} | tar xz -C ${PS4_SDK_9_000} --strip-components=1


#
# Wine
#

RUN \
  echo "Mono" && \
  apt-get update && \
  apt-get install -y --no-install-recommends mono-complete && \
  apt-get clean autoclean autoremove

# Installation notes: https://wiki.winehq.org/Ubuntu
# TODO: Backup the files as descibed here: https://wiki.winehq.org/Ubuntu
RUN \
 echo "Wine deps" && \
 apt-get update && \
 apt-get install -y --no-install-recommends apt-transport-https ca-certificates ca-certificates-java xvfb cabextract && \
 apt-get clean autoclean autoremove

RUN \
  echo "Wine" && \
  wget -nc https://dl.winehq.org/wine-builds/winehq.key && \
  APT_KEY_DONT_WARN_ON_DANGEROUS_USAGE=1 apt-key add winehq.key && \
  apt-add-repository "deb https://dl.winehq.org/wine-builds/ubuntu/ xenial main" && \
  dpkg --add-architecture i386 && \
  apt-get update && \
  DEBIAN_FRONTEND="noninteractive" apt-get install -y --install-recommends winehq-stable winetricks && \
  apt-get clean autoclean autoremove && \
  rm winehq.key

ENV PATH ${PATH}:/opt/wine-stable/bin

RUN \
  wget -q https://raw.githubusercontent.com/Winetricks/winetricks/master/src/winetricks && \
  chmod +rx winetricks && \
  mv winetricks /usr/local/bin/winetricks

# Disable all debug messages
ENV WINEDEBUG "-all"

ENV WINEPREFIX "/var/extender/.wine"

RUN \
  echo "Wine Init" && \
  mkdir -p ${WINEPREFIX}

ENV DISPLAY=":1"
RUN \
  set -xe                     && \
  WINEDLLOVERRIDES="mscoree,mshtml=" xvfb-run wine wineboot && \
  xvfb-run wineserver -w
  # xvfb-run wineserver -w      && \
  # xvfb-run winetricks -q vcrun2015

RUN \
  chown -R extender: $WINEPREFIX

# Technically part of the Android build, but also required by the GradleService.java
# until it can be disabled (e.g. by looking for the existance of GRADLE_USER_HOME)

# Versions: https://developer.android.com/studio/releases/gradle-plugin?buildsystem=ndk-build#updating-gradle
# Java+Gradle version matrix: https://docs.gradle.org/current/userguide/compatibility.html
ENV GRADLE_USER_HOME /tmp/.gradle
ENV GRADLE_VERSION 6.1.1
ENV GRADLE_PLUGIN_VERSION 4.0.1
ENV PATH ${PATH}:/opt/gradle/gradle-${GRADLE_VERSION}/bin
RUN \
  echo "Gradle" && \
  wget -q https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip && \
  mkdir /opt/gradle && \
  unzip -q -d /opt/gradle gradle-${GRADLE_VERSION}-bin.zip && \
  rm gradle-${GRADLE_VERSION}-bin.zip && \
  which gradle && \
  chown -R extender: /opt/gradle

ENV EXTENSION_BUILD_GRADLE_TEMPLATE /var/extender/template.build.gradle
ENV EXTENSION_GRADLE_PROPERTIES_TEMPLATE /var/extender/template.gradle.properties
ENV EXTENSION_LOCAL_PROPERTIES_TEMPLATE /var/extender/template.local.properties
COPY template.build.gradle ${EXTENSION_BUILD_GRADLE_TEMPLATE}
COPY template.gradle.properties ${EXTENSION_GRADLE_PROPERTIES_TEMPLATE}
COPY template.local.properties ${EXTENSION_LOCAL_PROPERTIES_TEMPLATE}

# iOS + MacOS
# Although the building isn't done using the Docker container anymore
# we still need to be able to resolve the variables in the case when we run the docker container locally  when debugging

# NOTE: These values are replicated in server/scripts/standalone/service-standalone.sh
# Install flow from  >=1.2.174
ENV XCODE_12_VERSION      12.5
ENV MACOS_10_15_VERSION   10.15
ENV IOS_14_VERSION        14.5
ENV LIB_TAPI_1_6_PATH     /usr/local/tapi1.6/lib
# Install flow from >=1.2.185
ENV MACOS_11_VERSION        11.3
ENV XCODE_12_CLANG_VERSION  12.0.5
ENV SWIFT_5_VERSION         5.0

# Versions from >=1.2.191
ENV XCODE_13_VERSION        13.2.1
ENV MACOS_12_VERSION        12.1
ENV IOS_15_VERSION          15.2
ENV XCODE_13_CLANG_VERSION  13.0.0
ENV SWIFT_5_5_VERSION       5.5


# Final cleanup

RUN \
  apt-get remove -y apt-transport-https xvfb && \
  apt-get clean autoclean autoremove


# To avoid the mysterious error "Unexpected error: java.security.InvalidAlgorithmParameterException: the trustAnchors parameter must be non-empty"
# # From https://github.com/docker-library/openjdk/blob/master/11/jdk/slim/Dockerfile
RUN \
  apt-get update; \
  apt-get install -y --no-install-recommends \
# utilities for keeping Debian and OpenJDK CA certificates in sync
    ca-certificates p11-kit \
  ; \
# update "cacerts" bundle to use Debian's CA certificates (and make sure it stays up-to-date with changes to Debian's store)
# see https://github.com/docker-library/openjdk/issues/327
#     http://rabexc.org/posts/certificates-not-working-java#comment-4099504075
#     https://salsa.debian.org/java-team/ca-certificates-java/blob/3e51a84e9104823319abeb31f880580e46f45a98/debian/jks-keystore.hook.in
#     https://git.alpinelinux.org/aports/tree/community/java-cacerts/APKBUILD?id=761af65f38b4570093461e6546dcf6b179d2b624#n29
  { \
    echo '#!/usr/bin/env bash'; \
    echo 'set -Eeuo pipefail'; \
    echo 'if ! [ -d "$JAVA_HOME" ]; then echo >&2 "error: missing JAVA_HOME environment variable"; exit 1; fi'; \
# 8-jdk uses "$JAVA_HOME/jre/lib/security/cacerts" and 8-jre and 11+ uses "$JAVA_HOME/lib/security/cacerts" directly (no "jre" directory)
    echo 'cacertsFile=; for f in "$JAVA_HOME/lib/security/cacerts" "$JAVA_HOME/jre/lib/security/cacerts"; do if [ -e "$f" ]; then cacertsFile="$f"; break; fi; done'; \
    echo 'if [ -z "$cacertsFile" ] || ! [ -f "$cacertsFile" ]; then echo >&2 "error: failed to find cacerts file in $JAVA_HOME"; exit 1; fi'; \
    echo 'trust extract --overwrite --format=java-cacerts --filter=ca-anchors --purpose=server-auth "$cacertsFile"'; \
  } > /etc/ca-certificates/update.d/docker-openjdk; \
  chmod +x /etc/ca-certificates/update.d/docker-openjdk; \
  /etc/ca-certificates/update.d/docker-openjdk; \
  \
# https://github.com/docker-library/openjdk/issues/331#issuecomment-498834472
  find "$JAVA_HOME/lib" -name '*.so' -exec dirname '{}' ';' | sort -u > /etc/ld.so.conf.d/docker-openjdk.conf; \
  ldconfig;

# Always run last to minimize the size
RUN apt-get clean autoclean autoremove
