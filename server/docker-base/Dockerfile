FROM ubuntu:16.04

# Base stuff
RUN \
  echo "LINUX TOOLS + COMPILER" && \
  dpkg --add-architecture i386 && \
  apt-get update && \
  apt-get install -y software-properties-common && \
  add-apt-repository ppa:openjdk-r/ppa && \
  apt-get update && \
  apt-get install -y --no-install-recommends \
    wget \
    locales \
    openjdk-11-jdk \
    gcc \
    g++ \
    gcc-multilib \
    g++-multilib \
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
    unzip \
    tree \
    silversearcher-ag && \
  rm -rf /var/lib/apt/lists/* && \
  rm -rf /var/cache/oracle-jdk8-installer

# Add extender user
RUN  useradd -r -u 2222 extender && \
  mkdir -p /var/extender && \
  chown extender: /var/extender && \
  chown extender: $(readlink -f /usr/bin/java) && \
  chmod +s $(readlink -f /usr/bin/java)

ARG S3_URL

# Put all SDK's into a single folder (just as we do in the regular engine build)
ENV PLATFORMSDK_DIR /opt/platformsdk
RUN mkdir $PLATFORMSDK_DIR

# These packages are downloaded from here: http://releases.llvm.org/download.html
# and then uploaded as-is to S3
RUN wget -q -O - ${S3_URL}/clang%2Bllvm-9.0.0-x86_64-linux-gnu-ubuntu-16.04.tar.xz | tar xJ -C /usr/local --strip-components=1

# Darwin
RUN locale-gen en_US.UTF-8
ENV LANG=en_US.UTF-8 \
    LANGUAGE=en_US:en \
    LC_ALL=en_US.UTF-8

#
# iOS + OSX
#

# The updated install flow
RUN \
  apt-get update && \
  apt-get install -y git cmake && \
  cd /tmp && \
  TAPITMP=/tmp/tapi1.4 && \
  mkdir $TAPITMP && \
  git clone https://github.com/tpoechtrager/apple-libtapi.git && \
  cd apple-libtapi && \
  git checkout 3efb201881e7a76a21e0554906cf306432539cef && \
  INSTALLPREFIX=$TAPITMP ./build.sh && \
  ./install.sh && \
  cd .. && \
  git clone https://github.com/tpoechtrager/cctools-port.git && \
  cd cctools-port/cctools && \
  git checkout 3f979bbcd7ee29d79fb93f829edf3d1d16441147 && \
  ./configure --prefix=/usr/local --target=arm-apple-darwin14 --with-libtapi=$TAPITMP && \
  make -j8 && \
  make install && \
  make distclean && \
  ./configure --prefix=/usr/local --target=x86_64-apple-darwin14 --with-libtapi=$TAPITMP && \
  make -j8 && \
  make install && \
  make distclean && \
  ./configure --prefix=/usr/local --target=arm-apple-darwin12 --with-libtapi=$TAPITMP && \
  make -j8 && \
  make install && \
  make distclean && \
  ./configure --prefix=/usr/local --target=x86_64-apple-darwin12 --with-libtapi=$TAPITMP && \
  make -j8 && \
  make install && \
  make distclean && \
  apt-get purge --auto-remove -y git cmake && \
  rm -rf apple-libtapi && \
  rm -rf cctools-port

# For package instructions, see <defold>/scripts/mobile/package_xcode_and_sdks.sh
RUN \
  wget -q -O - ${S3_URL}/iPhoneOS12.1.sdk.tar.gz | tar xz -C ${PLATFORMSDK_DIR} && \
  mv ${PLATFORMSDK_DIR}/iPhoneOS.sdk ${PLATFORMSDK_DIR}/iPhoneOS12.1.sdk && \
  wget -q -O - ${S3_URL}/iPhoneOS13.0.sdk.tar.gz | tar xz -C ${PLATFORMSDK_DIR} && \
  mv ${PLATFORMSDK_DIR}/iPhoneOS.sdk ${PLATFORMSDK_DIR}/iPhoneOS13.0.sdk && \
  wget -q -O - ${S3_URL}/iPhoneSimulator12.1.sdk.tar.gz | tar xz -C ${PLATFORMSDK_DIR} && \
  mv ${PLATFORMSDK_DIR}/iPhoneSimulator.sdk ${PLATFORMSDK_DIR}/iPhoneSimulator12.1.sdk && \
  wget -q -O - ${S3_URL}/iPhoneSimulator13.0.sdk.tar.gz | tar xz -C ${PLATFORMSDK_DIR} && \
  mv ${PLATFORMSDK_DIR}/iPhoneSimulator.sdk ${PLATFORMSDK_DIR}/iPhoneSimulator13.0.sdk && \
  wget -q -O - ${S3_URL}/MacOSX10.13.sdk.tar.gz | tar xz -C ${PLATFORMSDK_DIR} && \
  mv ${PLATFORMSDK_DIR}/MacOSX.sdk ${PLATFORMSDK_DIR}/MacOSX10.13.sdk && \
  wget -q -O - ${S3_URL}/MacOSX10.15.sdk.tar.gz | tar xz -C ${PLATFORMSDK_DIR} && \
  mv ${PLATFORMSDK_DIR}/MacOSX.sdk ${PLATFORMSDK_DIR}/MacOSX10.15.sdk && \
  wget -q -O - ${S3_URL}/XcodeDefault11.0.xctoolchain.tar.gz | tar xz -C ${PLATFORMSDK_DIR} && \
  mv ${PLATFORMSDK_DIR}/XcodeDefault.xctoolchain ${PLATFORMSDK_DIR}/XcodeDefault11.0.xctoolchain

RUN \
  ln -s /usr/local/bin/llvm-ar /usr/local/bin/ar && \
  ln -s /usr/local/bin/clang /usr/local/bin/x86_64-pc-win32-clang && \
  ln -s /usr/local/bin/llvm-ar /usr/local/bin/x86_64-pc-win32-clang-ar && \
  ln -s ${PLATFORMSDK_DIR}/XcodeDefault11.0.xctoolchain/usr/lib/arc /usr/local/lib/arc

#
# EMSCRIPTEN
#

RUN \
  echo "EMSCRIPTEN" && \
  apt-get autoclean && \
  apt-get install -y --no-install-recommends python2.7 && \
  ln -s /usr/bin/python2.7 /usr/local/bin/python && \
  ln -s /usr/bin/python2.7 /usr/local/bin/python2

ENV EMCC_SKIP_SANITY_CHECK 1

## Emscripten 1.38.12
ENV EMSCRIPTEN_SDK_1_38_12 ${PLATFORMSDK_DIR}/emsdk_1.38.12
ENV EMSCRIPTEN_HOME_1_38_12 ${EMSCRIPTEN_SDK_1_38_12}/bin/emsdk_portable
ENV EMSCRIPTEN_CONFIG_1_38_12 ${EMSCRIPTEN_HOME_1_38_12}/.emscripten
ENV EMSCRIPTEN_BIN_1_38_12 ${EMSCRIPTEN_HOME_1_38_12}/emscripten/1.38.12
ENV EMSCRIPTEN_CACHE_1_38_12 /var/extender/emcache_1_38_12
# Setup a special env variable that will be prefixed to PATH if requested version is 1.38.12
ENV EMSCRIPTEN_PATH_1_38_12 ${EMSCRIPTEN_HOME_1_38_12}:${EMSCRIPTEN_HOME_1_38_12}/clang/e1.38.12_64bit:${EMSCRIPTEN_HOME_1_38_12}/node/8.9.1_64bit/bin:${EMSCRIPTEN_BIN_1_38_12}

RUN \
  mkdir ${EMSCRIPTEN_SDK_1_38_12} && \
  wget -q -O - ${S3_URL}/emsdk-1.38.12-x86_64-linux.tar.gz | tar xz -C ${EMSCRIPTEN_SDK_1_38_12} && \
  ${EMSCRIPTEN_HOME_1_38_12}/emsdk activate sdk-1.38.12-64bit --embedded && \
  EM_CONFIG=$EMSCRIPTEN_CONFIG_1_38_12 EM_CACHE=${EMSCRIPTEN_CACHE_1_38_12} python ${EMSCRIPTEN_BIN_1_38_12}/embuilder.py build libc libc-extras libcxx libcxx_noexcept libcxxabi wasm-libc gl dlmalloc struct_info && \
  chmod -R 755 ${EMSCRIPTEN_HOME_1_38_12} && \
  chown -R extender: ${EMSCRIPTEN_CACHE_1_38_12} && \
  chown -R extender: ${EMSCRIPTEN_CACHE_1_38_12}.lock

# We use the same temp directory for both versions.
ENV EMSCRIPTEN_TEMP_DIR /var/extender/ems_temp
RUN mkdir -p ${EMSCRIPTEN_TEMP_DIR}
RUN chmod -R 755 ${EMSCRIPTEN_TEMP_DIR} && chown extender: ${EMSCRIPTEN_TEMP_DIR}
# The "sed" command below removes the /TEMP_DIR line from the generated configs
# We replace it with a folder of our own
RUN sed '/TEMP_DIR =/d' ${EMSCRIPTEN_CONFIG_1_38_12} && \
  echo TEMP_DIR = \'${EMSCRIPTEN_TEMP_DIR}\' >> ${EMSCRIPTEN_CONFIG_1_38_12}

#
# Windows
#

ENV \
  PLATFORMSDK_WIN32=$PLATFORMSDK_DIR/Win32 \
  WINDOWS_SDK_10_VERSION=10.0.18362.0 \
  WINDOWS_MSVC_2019_VERSION=14.23.28105

# windres: Allows for generating .res files that can be used during linking
RUN \
  echo "Win32 SDK - WINDRES" && \
  apt-get update && \
  apt-get install -y binutils-mingw-w64-x86-64 && \
  ls -la /usr/bin/x86_64-w64-mingw32-windres && \
  ln -s /usr/bin/x86_64-w64-mingw32-windres /usr/local/bin/windres

RUN \
  echo "WIN32 2019 SDK" && \
  mkdir -p ${PLATFORMSDK_WIN32}/WindowsKits && \
  wget -q -O - ${S3_URL}/WindowsKits-8.1_slim.tar.gz | tar xz -C ${PLATFORMSDK_WIN32}/WindowsKits && \
  wget -q -O - ${S3_URL}/WindowsKits-${WINDOWS_SDK_10_VERSION}.tar.gz | tar xz -C ${PLATFORMSDK_WIN32}/WindowsKits && \
  mkdir ${PLATFORMSDK_WIN32}/MicrosoftVisualStudio2019 && \
  wget -q -O - ${S3_URL}/Microsoft-Visual-Studio-2019-${WINDOWS_MSVC_2019_VERSION}.tar.gz | tar xz -C ${PLATFORMSDK_WIN32}/MicrosoftVisualStudio2019

RUN \
  echo "WIN32 2015 SDK" && \
  wget -q -O - ${S3_URL}/Microsoft-Visual-Studio-14-0.tar.gz | tar xz -C ${PLATFORMSDK_WIN32} && \
  mv ${PLATFORMSDK_WIN32}/Microsoft\ Visual\ Studio\ 14.0 ${PLATFORMSDK_WIN32}/MicrosoftVisualStudio14.0 && \
  rm -rf ${PLATFORMSDK_WIN32}/MicrosoftVisualStudio14.0/Common7/IDE && \
  wget -q -O - ${S3_URL}/WindowsKits-10.0.tar.gz | tar xz -C ${PLATFORMSDK_WIN32}/WindowsKits

# Due to Windows' case insensitive file system, the sources reference lib files with wrong cases
# so we solve the bulk by making the suffixes lowercase
RUN find $PLATFORMSDK_WIN32 -iname '*.Lib' -exec sh -c 'a=$(echo "$0" | sed -r "s/([^.]*)\$/\L\1/"); [ "$a" != "$0" ] && mv "$0" "$a" ' {} \;
# Make a copy of all the headers too, in lower case (e.g. Windows.h -> windows.h etc)
RUN find $PLATFORMSDK_WIN32 -iname '*.h' -exec sh -c 'd=$(dirname "$0"); a=$(basename "$0" | tr [:upper:] [:lower:]); [ "$a" != $(basename "$0") ] && cp "$0" "$d/$a" ' {} \;

# Grabbed after a starting MSVC 2019, and choosing "Tools -> Command Line -> Developer Command Prompt"
# Note: VCINSTALLDIR is special since clang will use it as the last "-internal-isystem" option
ENV \
  VCINSTALLDIR="${PLATFORMSDK_WIN32}/MicrosoftVisualStudio14.0/VC/" \
  VSINSTALLDIR="${PLATFORMSDK_WIN32}/MicrosoftVisualStudio14.0/" \
  WINDOWS_MSVC_2015_DIR="${PLATFORMSDK_WIN32}/MicrosoftVisualStudio14.0/VC/" \
  WINDOWS_MSVC_2019_DIR="${PLATFORMSDK_WIN32}/MicrosoftVisualStudio2019/VC/Tools/MSVC/${WINDOWS_MSVC_2019_VERSION}/" \
  WINDOWS_SDK_8_DIR="${PLATFORMSDK_WIN32}/WindowsKits/8.1/" \
  WINDOWS_SDK_10_DIR="${PLATFORMSDK_WIN32}/WindowsKits/10/" \
  VS140COMNTOOLS="${PLATFORMSDK_WIN32}/MicrosoftVisualStudio14.0/Common7/Tools/" \
  VS160COMNTOOLS="${PLATFORMSDK_WIN32}/MicrosoftVisualStudio2019/Common7/Tools/" \
  WINDOWS_VCINSTALLDIR="${PLATFORMSDK_WIN32}/MicrosoftVisualStudio2019/VC/Tools/MSVC/${WINDOWS_MSVC_2019_VERSION}/" \
  WINDOWS_VSINSTALLDIR="${PLATFORMSDK_WIN32}/MicrosoftVisualStudio2019/" \
  WindowsLibPath="${PLATFORMSDK_WIN32}/WindowsKits/8.1/References/CommonConfiguration/Neutral" \
  WindowsSdkDir="${PLATFORMSDK_WIN32}/WindowsKits/8.1/" \
  WindowsSDKLibVersion="winv6.3/" \
  WindowsSDKVersion="/"

# # and the rest are manually copied (or made lower case)
RUN \
  echo "WIN32 SDK - Cleanup" && \
  (cd ${WINDOWS_MSVC_2015_DIR}/lib/amd64 && cp oldnames.lib OLDNAMES.lib) && \
  (cd ${WINDOWS_MSVC_2015_DIR}/lib && cp oldnames.lib OLDNAMES.lib) && \
  (cd ${WINDOWS_MSVC_2015_DIR}/lib/amd64 && cp delayimp.lib Delayimp.lib) && \
  (cd ${WINDOWS_MSVC_2015_DIR}/lib && cp delayimp.lib Delayimp.lib) && \
  (cd ${WINDOWS_MSVC_2019_DIR}/lib/x64 && cp oldnames.lib OLDNAMES.lib) && \
  (cd ${WINDOWS_MSVC_2019_DIR}/lib/x86 && cp oldnames.lib OLDNAMES.lib) && \
  (cd ${WINDOWS_MSVC_2019_DIR}/lib/x64 && cp delayimp.lib Delayimp.lib) && \
  (cd ${WINDOWS_MSVC_2019_DIR}/lib/x86 && cp delayimp.lib Delayimp.lib) && \
  (cd ${WINDOWS_SDK_8_DIR}/Include/shared && cp driverspecs.h DriverSpecs.h) && \
  (cd ${WINDOWS_SDK_8_DIR}/Include/shared && cp specstrings.h SpecStrings.h) && \
  (cd ${WINDOWS_SDK_8_DIR}/Include/shared && cp concurrencysal.h ConcurrencySal.h) && \
  (cd ${WINDOWS_SDK_8_DIR}/Lib/winv6.3/um/x64 && find . -type f -exec sh -c 'x="{}"; xl=$(echo $x | sed -e "s/\(.*\)/\L\1/"); if [ $x != $xl ]; then cp $x $xl; fi' \;) && \
  (cd ${WINDOWS_SDK_8_DIR}/Lib/winv6.3/um/x86 && find . -type f -exec sh -c 'x="{}"; xl=$(echo $x | sed -e "s/\(.*\)/\L\1/"); if [ $x != $xl ]; then cp $x $xl; fi' \;)


#
# Android SDK/NDK
#
ENV ANDROID_ROOT ${PLATFORMSDK_DIR}/android
ENV ANDROID_TOOLS_FILENAME android-sdk-tools-linux-3859397.tar.gz
ENV ANDROID_BUILD_TOOLS_VERSION 23.0.2
ENV ANDROID_HOME ${ANDROID_ROOT}/android-sdk-linux
ENV ANDROID_NDK_VERSION 10e
ENV ANDROID_NDK_API_VERSION 14
ENV ANDROID_TARGET_API_LEVEL 23
ENV ANDROID_MIN_API_LEVEL 9
ENV ANDROID_GCC_VERSION 4.8
ENV ANDROID_NDK_PATH ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}
ENV ANDROID_NDK_INCLUDE ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}/platforms/android-${ANDROID_NDK_API_VERSION}/arch-arm/usr/include
ENV ANDROID_NDK_FILENAME android-ndk-r${ANDROID_NDK_VERSION}-linux-x86_64.tar.gz
ENV ANDROID_SDK_VERSION 29
ENV ANDROID_SDK_FILENAME android-sdk-linux-android-${ANDROID_SDK_VERSION}.tar.gz
ENV ANDROID_STL_INCLUDE ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}/sources/cxx-stl/gnu-libstdc++/${ANDROID_GCC_VERSION}/include
ENV ANDROID_STL_ARCH_INCLUDE ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}/sources/cxx-stl/gnu-libstdc++/${ANDROID_GCC_VERSION}/libs/armeabi-v7a/include
ENV ANDROID_STL_LIB ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}/sources/cxx-stl/gnu-libstdc++/${ANDROID_GCC_VERSION}/libs/armeabi-v7a
ENV ANDROID_SYSROOT ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}/platforms/android-${ANDROID_NDK_API_VERSION}/arch-arm
ENV ANDROID_BIN_PATH ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}/toolchains/arm-linux-androideabi-${ANDROID_GCC_VERSION}/prebuilt/linux-x86_64/bin
ENV ANDROID_SDK_BUILD_TOOLS_PATH ${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS_VERSION}
ENV ANDROID_LIBRARYJAR ${ANDROID_HOME}/platforms/android-${ANDROID_SDK_VERSION}/android.jar
ENV ANDROID_64_NDK_API_VERSION 21
ENV ANDROID_64_GCC_VERSION 4.9
ENV ANDROID_64_NDK_INCLUDE ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}/platforms/android-${ANDROID_64_NDK_API_VERSION}/arch-arm64/usr/include
ENV ANDROID_64_STL_INCLUDE ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}/sources/cxx-stl/gnu-libstdc++/${ANDROID_64_GCC_VERSION}/include
ENV ANDROID_64_STL_ARCH_INCLUDE ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}/sources/cxx-stl/gnu-libstdc++/${ANDROID_64_GCC_VERSION}/libs/arm64-v8a/include
ENV ANDROID_64_STL_LIB ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}/sources/cxx-stl/gnu-libstdc++/${ANDROID_64_GCC_VERSION}/libs/arm64-v8a
ENV ANDROID_64_SYSROOT ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}/platforms/android-${ANDROID_64_NDK_API_VERSION}/arch-arm64
ENV ANDROID_64_BIN_PATH ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}/toolchains/aarch64-linux-android-${ANDROID_64_GCC_VERSION}/prebuilt/linux-x86_64/bin
ENV ANDROID_64_SDK_BUILD_TOOLS_PATH ${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS_VERSION}

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

ENV PATH ${PATH}:${ANDROID_HOME}/tools:${ANDROID_HOME}/platform-tools:${ANDROID_BIN_PATH}:${ANDROID_SDK_BUILD_TOOLS_PATH}
ENV PATH ${PATH}:${ANDROID_HOME}/tools:${ANDROID_HOME}/platform-tools:${ANDROID_64_BIN_PATH}:${ANDROID_64_SDK_BUILD_TOOLS_PATH}
ENV PATH ${PATH}:${ANDROID_NDK20_BIN_PATH}

# android proguard was version 4.7, this is at least 5.2.1 which seems to work with OpenJDK 11
RUN \
  apt-get update && \
  apt-get install -y --no-install-recommends proguard && \
  ls -la /usr/share/java/proguard.jar

ENV ANDROID_PROGUARD=/usr/share/java/proguard.jar

RUN \
    echo "Android SDK" && \
    mkdir -p ${ANDROID_HOME} && \
    wget -q -O - ${S3_URL}/${ANDROID_NDK_FILENAME} | tar xz -C ${ANDROID_ROOT} && \
    wget -q -O - ${S3_URL}/${ANDROID_NDK20_FILENAME} | tar xz -C ${ANDROID_ROOT} && \
    wget -q -O - ${S3_URL}/${ANDROID_TOOLS_FILENAME} | tar xz -C ${ANDROID_HOME} && \
    wget -q -O - ${S3_URL}/${ANDROID_SDK_FILENAME} | tar xz -C ${ANDROID_HOME} --strip-components=1 && \
    chmod +r -R ${ANDROID_ROOT} && \
    chmod -R 755 ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION} ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK20_VERSION} && \
    ls -la ${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS_VERSION}/dx && \
    which aarch64-linux-android-g++ && \
    which arm-linux-androideabi-g++

ENV GRADLE_USER_HOME /tmp/.gradle
ENV GRADLE_VERSION 6.0.1
ENV PATH ${PATH}:/opt/gradle/gradle-${GRADLE_VERSION}/bin
RUN \
  echo "Gradle" && \
  wget -q https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip && \
  mkdir /opt/gradle && \
  unzip -q -d /opt/gradle gradle-${GRADLE_VERSION}-bin.zip && \
  rm gradle-${GRADLE_VERSION}-bin.zip && \
  which gradle && \
  chown -R extender: /opt/gradle

ENV EXTENSION_GRADLE_TEMPLATE /var/extender/template.build.gradle
COPY template.build.gradle ${EXTENSION_GRADLE_TEMPLATE}

ENV JAVA_HOME /usr/lib/jvm/java-11-openjdk-amd64

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
