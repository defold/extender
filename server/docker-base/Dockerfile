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
    wine \
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

# Windows
ENV PROGRAM_FILES "/root/.wine/drive_c/Program Files"

RUN \
  mkdir -p "/root/.wine/drive_c/windows/system32" && \
  wget -P /usr/bin ${S3_URL}/prepreg-vc10-dx09-32.exe && \
  ls /root/.wine/drive_c/windows/system32 && \
  mkdir -p "${PROGRAM_FILES}" && \
  wget -q -O - ${S3_URL}/Microsoft-Visual-Studio-10.0.tar.gz | tar xz -C "${PROGRAM_FILES}"

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
