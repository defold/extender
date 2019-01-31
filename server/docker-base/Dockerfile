FROM ubuntu:16.04

# Base stuff
RUN \
  sed -i 's|archive.ubuntu.com|ubuntu.mirror.su.se|g' /etc/apt/sources.list && \
  sed -i 's|security.ubuntu.com|ubuntu.mirror.su.se|g' /etc/apt/sources.list && \
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


ENV S3_URL https://s3-eu-west-1.amazonaws.com/defold-packages

# Put all SDK's into a single folder (just as we do in the regular engine build)
ENV PLATFORMSDK_DIR /opt/platformsdk
RUN mkdir $PLATFORMSDK_DIR

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
# For package instructions, see <defold>/scripts/mobile/package_xcode_and_sdks.sh
RUN \
  wget -q -O - ${S3_URL}/iPhoneOS11.2.sdk.tar.gz | tar xz -C ${PLATFORMSDK_DIR} && \
  mv ${PLATFORMSDK_DIR}/iPhoneOS.sdk ${PLATFORMSDK_DIR}/iPhoneOS11.2.sdk && \
  wget -q -O - ${S3_URL}/iPhoneOS12.1.sdk.tar.gz | tar xz -C ${PLATFORMSDK_DIR} && \
  mv ${PLATFORMSDK_DIR}/iPhoneOS.sdk ${PLATFORMSDK_DIR}/iPhoneOS12.1.sdk && \
  wget -q -O - ${S3_URL}/iPhoneSimulator12.1.sdk.tar.gz | tar xz -C ${PLATFORMSDK_DIR} && \
  mv ${PLATFORMSDK_DIR}/iPhoneSimulator.sdk ${PLATFORMSDK_DIR}/iPhoneSimulator12.1.sdk && \
  wget -q -O - ${S3_URL}/MacOSX10.13.sdk.tar.gz | tar xz -C ${PLATFORMSDK_DIR} && \
  mv ${PLATFORMSDK_DIR}/MacOSX.sdk ${PLATFORMSDK_DIR}/MacOSX10.13.sdk && \
  wget -q -O - ${S3_URL}/XcodeDefault10.1.xctoolchain.tar.gz | tar xz -C ${PLATFORMSDK_DIR} && \
  mv ${PLATFORMSDK_DIR}/XcodeDefault.xctoolchain ${PLATFORMSDK_DIR}/XcodeDefault10.1.xctoolchain && \
  ln -s /usr/local/bin/llvm-ar /usr/local/bin/ar && \
  ln -s /usr/local/bin/llvm-dsymutil /usr/local/bin/dsymutil && \
  ln -s /usr/local/bin/clang /usr/local/bin/x86_64-pc-win32-clang && \
  ln -s /usr/local/bin/llvm-ar /usr/local/bin/x86_64-pc-win32-clang-ar

#
# EMSCRIPTEN
#

RUN apt-get autoclean && \
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

## Emscripten 1.35.23 - Needs to be installed and activated last
ENV EMSCRIPTEN_SDK_1_35_23 ${PLATFORMSDK_DIR}/emsdk_1.35.23
ENV EMSCRIPTEN_HOME_1_35_23 ${EMSCRIPTEN_SDK_1_35_23}/bin/emsdk_portable
ENV EMSCRIPTEN_CONFIG_1_35_23 ${EMSCRIPTEN_HOME_1_35_23}/.emscripten
ENV EMSCRIPTEN_BIN_1_35_23 ${EMSCRIPTEN_HOME_1_35_23}/emscripten/master

# Add old 1.35.23 emscripten to the env PATH, it will be used by default since older SDKs doesn't have the emversion key in build.yml.
# We are also setting up default variables for the config path and emscripten home for 1.35.23.
# This can be removed once we remove 1.35.23 version support.
ENV PATH ${PATH}:${EMSCRIPTEN_HOME_1_35_23}:${EMSCRIPTEN_HOME_1_35_23}/clang/fastcomp/build_master_64/bin:${EMSCRIPTEN_HOME_1_35_23}/node/4.1.1_64bit/bin:${EMSCRIPTEN_BIN_1_35_23}
ENV EM_CONFIG ${EMSCRIPTEN_CONFIG_1_35_23}
ENV EMSCRIPTEN_HOME ${EMSCRIPTEN_HOME_1_35_23}

RUN \
  mkdir ${EMSCRIPTEN_SDK_1_35_23} && \
  wget -q -O - ${S3_URL}/emsdk-1.35.23-x86_64-linux.tar.gz | tar xz -C ${EMSCRIPTEN_SDK_1_35_23} && \
  ${EMSCRIPTEN_HOME_1_35_23}/emsdk activate --embedded && \
  chmod -R 755 ${EMSCRIPTEN_HOME_1_35_23}

# We use the same temp directory for both versions.
ENV EMSCRIPTEN_TEMP_DIR /var/extender/ems_temp
RUN mkdir -p ${EMSCRIPTEN_TEMP_DIR}
RUN chmod -R 755 ${EMSCRIPTEN_TEMP_DIR} && chown extender: ${EMSCRIPTEN_TEMP_DIR}
# The "sed" command below removes the /TEMP_DIR line from the generated configs
# We replace it with a folder of our own
RUN sed '/TEMP_DIR =/d' ${EMSCRIPTEN_CONFIG_1_35_23} && \
  echo TEMP_DIR = \'${EMSCRIPTEN_TEMP_DIR}\' >> ${EMSCRIPTEN_CONFIG_1_35_23} && \
  sed '/TEMP_DIR =/d' ${EMSCRIPTEN_CONFIG_1_38_12} && \
  echo TEMP_DIR = \'${EMSCRIPTEN_TEMP_DIR}\' >> ${EMSCRIPTEN_CONFIG_1_38_12}

#
# Windows
#

ENV PLATFORMSDK_WIN32 $PLATFORMSDK_DIR/Win32

# windres: Allows for generating .res files that can be used during linking
RUN \
  apt-get update && \
  apt-get install -y apt-transport-https mingw-w64 && \
  ln -s /usr/bin/x86_64-w64-mingw32-windres /usr/local/bin/windres

# Grabbed after a call to <VCINSTALLDIR>/bin/x86_amd64\vcvarsx86_amd64.bat (Can probably be trimmed further)
ENV VS140COMNTOOLS "${PLATFORMSDK_WIN32}/MicrosoftVisualStudio14.0/Common7/Tools/"
ENV VCINSTALLDIR "${PLATFORMSDK_WIN32}/MicrosoftVisualStudio14.0/VC/"
ENV VSINSTALLDIR "${PLATFORMSDK_WIN32}/MicrosoftVisualStudio14.0/"
ENV WindowsLibPath "${PLATFORMSDK_WIN32}/WindowsKits/8.1/References/CommonConfiguration/Neutral"
ENV WindowsSdkDir "${PLATFORMSDK_WIN32}/WindowsKits/8.1/"
ENV WindowsSDKLibVersion "winv6.3/"
ENV WindowsSDKVersion "/"

RUN \
  mkdir -p ${PLATFORMSDK_WIN32}/WindowsKits && \
  wget -q -O - ${S3_URL}/WindowsKits-8.1.tar.gz | tar xz -C ${PLATFORMSDK_WIN32}/WindowsKits && \
  wget -q -O - ${S3_URL}/WindowsKits-10.0.tar.gz | tar xz -C ${PLATFORMSDK_WIN32}/WindowsKits && \
  wget -q -O - ${S3_URL}/Microsoft-Visual-Studio-14-0.tar.gz | tar xz -C ${PLATFORMSDK_WIN32} && \
  ln -s ${PLATFORMSDK_WIN32}/Microsoft\ Visual\ Studio\ 14.0 ${PLATFORMSDK_WIN32}/MicrosoftVisualStudio14.0

# Due to Windows' case insensitive file system, the sources reference lib files with wrong cases
# so we solve the bulk by making the suffixes lowercase
RUN find $PLATFORMSDK_WIN32 -iname '*.Lib' -exec sh -c 'a=$(echo "$0" | sed -r "s/([^.]*)\$/\L\1/"); [ "$a" != "$0" ] && mv "$0" "$a" ' {} \;
# Make a copy of all the headers too, in lower case (e.g. Windows.h -> windows.h etc)
RUN find $PLATFORMSDK_WIN32 -iname '*.h' -exec sh -c 'd=$(dirname "$0"); a=$(basename "$0" | tr [:upper:] [:lower:]); [ "$a" != $(basename "$0") ] && cp "$0" "$d/$a" ' {} \;

# and the rest are manually copied (or made lower case)
RUN (cd ${PLATFORMSDK_WIN32}/MicrosoftVisualStudio14.0/VC/lib/amd64 && cp oldnames.lib OLDNAMES.lib) && \
    (cd ${PLATFORMSDK_WIN32}/MicrosoftVisualStudio14.0/VC/lib && cp oldnames.lib OLDNAMES.lib) && \
    (cd ${PLATFORMSDK_WIN32}/MicrosoftVisualStudio14.0/VC/lib/amd64 && cp delayimp.lib Delayimp.lib) && \
    (cd ${PLATFORMSDK_WIN32}/MicrosoftVisualStudio14.0/VC/lib && cp delayimp.lib Delayimp.lib) && \
    (cd ${PLATFORMSDK_WIN32}/WindowsKits/8.1/Include/shared && cp driverspecs.h DriverSpecs.h) && \
    (cd ${PLATFORMSDK_WIN32}/WindowsKits/8.1/Include/shared && cp specstrings.h SpecStrings.h) && \
    (cd ${PLATFORMSDK_WIN32}/WindowsKits/8.1/Lib/winv6.3/um/x64 && find . -type f -exec sh -c 'x="{}"; xl=$(echo $x | sed -e "s/\(.*\)/\L\1/"); if [ $x != $xl ]; then cp $x $xl; fi' \;) && \
    (cd ${PLATFORMSDK_WIN32}/WindowsKits/8.1/Lib/winv6.3/um/x86 && find . -type f -exec sh -c 'x="{}"; xl=$(echo $x | sed -e "s/\(.*\)/\L\1/"); if [ $x != $xl ]; then cp $x $xl; fi' \;)

# For SDK's older than 1.2.146
ENV PROGRAM_FILES /var/extender/.wine/drive_c/ProgramFilesx86
RUN \
  mkdir -p ${PROGRAM_FILES} && \
  ln -s ${PLATFORMSDK_WIN32}/MicrosoftVisualStudio14.0 ${PROGRAM_FILES}/MicrosoftVisualStudio14.0 && \
  ln -s ${PLATFORMSDK_WIN32}/WindowsKits ${PROGRAM_FILES}/WindowsKits

#
# Android SDK/NDK
#
ENV ANDROID_ROOT ${PLATFORMSDK_DIR}/android
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
ENV ANDROID_NDK_INCLUDE ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK_VERSION}/platforms/android-${ANDROID_NDK_API_VERSION}/arch-arm/usr/include
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
    wget -q ${ANDROID_TOOLS_URL} && \
    unzip -q ${ANDROID_TOOLS_FILENAME} && \
    rm ${ANDROID_TOOLS_FILENAME} && \
    chown -R extender: ${ANDROID_HOME}

# The sdk manager wants user access folder
USER extender
RUN cd ${ANDROID_HOME} && \
    echo y | ./tools/bin/sdkmanager "tools" "platform-tools" > /dev/null && \
    echo y | ./tools/bin/sdkmanager "extras;android;m2repository" "platforms;android-${ANDROID_TARGET_API_LEVEL}" "build-tools;${ANDROID_BUILD_TOOLS_VERSION}" > /dev/null && \
    ls -la ./build-tools/${ANDROID_BUILD_TOOLS_VERSION}/dx # make sure it exists!


# Remove the rights again from the extender user
USER root
RUN cd ${ANDROID_HOME} && \
    chown -R root: ${ANDROID_HOME}

