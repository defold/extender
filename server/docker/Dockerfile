FROM ubuntu:16.04

# Base stuff
RUN apt-get update && apt-get install -y software-properties-common
RUN dpkg --add-architecture i386
RUN \
  echo oracle-java8-installer shared/accepted-oracle-license-v1-1 select true | debconf-set-selections && \
  add-apt-repository -y ppa:webupd8team/java && \
  apt-get update && \
  apt-get install -y\
    curl \
    oracle-java8-installer \
    wine \
    git \
    gcc \
    g++ \
    libssl-dev \
    openssl \
    libtool \
    autoconf \
    automake \
    ccache \
    uuid-dev \
    libxi-dev \
    libopenal-dev \
    valgrind python-setuptools \
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

ENV PATH ${PATH}:${ANDROID_HOME}/tools:${ANDROID_HOME}/platform-tools:${ANDROID_BIN_PATH}
RUN mkdir -p cd ${ANDROID_ROOT} && \
    cd ${ANDROID_ROOT} && \
    wget -q ${ANDROID_SDK_URL} && \
    tar -xzf ${ANDROID_SDK_FILENAME} && \
    rm ${ANDROID_SDK_FILENAME} && \
    echo y | android update sdk --no-ui -a --filter tools,platform-tools,android-${ANDROID_TARGET_API_LEVEL},build-tools-${ANDROID_BUILD_TOOLS_VERSION} && \
    ln -s android-sdk-linux android-sdk

ENV ANDROID_NDK_FILENAME android-ndk-r${ANDROID_NDK_VERSION}-linux-x86_64.zip
ENV ANDROID_NDK_URL https://dl.google.com/android/repository/${ANDROID_NDK_FILENAME}

RUN cd ${ANDROID_ROOT} && \
    wget -q ${ANDROID_NDK_URL} && \
    chmod +x ${ANDROID_NDK_FILENAME} && \
    unzip ${ANDROID_NDK_FILENAME} && \
    chmod +r -R ${ANDROID_ROOT} && \
    rm ${ANDROID_NDK_FILENAME}

ENV S3_URL https://s3-eu-west-1.amazonaws.com/defold-packages

RUN wget -q -O - ${S3_URL}/clang%2Bllvm-3.9.0-x86_64-linux-gnu-ubuntu-16.04.tar.xz | tar xJ -C /usr/local --strip-components=1

# Windows
ENV PROGRAM_FILES "/root/.wine/drive_c/Program Files"

RUN mkdir -p "/root/.wine/drive_c/windows/system32"
RUN wget -P /usr/bin ${S3_URL}/prepreg-vc10-dx09-32.exe
RUN ls /root/.wine/drive_c/windows/system32


RUN mkdir -p "${PROGRAM_FILES}"

RUN wget -q -O - ${S3_URL}/Microsoft-Visual-Studio-10.0.tar.gz | tar xz -C "${PROGRAM_FILES}"

RUN useradd builder

RUN locale-gen en_US.UTF-8
ENV LANG en_US.UTF-8
ENV LANGUAGE en_US:en
ENV LC_ALL en_US.UTF-8


ADD hw.c /tmp
ADD test.sh /tmp
ADD build-darwin-toolchain.sh /tmp
RUN /tmp/build-darwin-toolchain.sh 84ce22dbb22a26ce7f392e9de0ee39c2efe6fd68

RUN wget -q -O - ${S3_URL}/iPhoneOS9.3.sdk.tar.gz | tar xz -C /opt
RUN wget -q -O - ${S3_URL}/MacOSX10.11.sdk.tar.gz | tar xz -C /opt

RUN ln -s /usr/local/bin/llvm-dsymutil /usr/local/bin/dsymutil
RUN ln -s /usr/local/bin/clang /usr/local/bin/arm-apple-darwin11-clang
RUN ln -s /usr/local/bin/clang /usr/local/bin/x86_64-apple-darwin11-clang
RUN ln -s /usr/local/bin/llvm-ar /usr/local/bin/x86_64-apple-darwin11-clang-ar

ADD test-ios-toolchain.sh /tmp

# Create user "extender" with fixed user ID and run the webapp as that user.
RUN useradd -r -u 2222 extender

RUN mkdir -p /var/extender
RUN chown extender: /var/extender

RUN chown extender: $(readlink -f /usr/bin/java)
RUN chmod +s $(readlink -f /usr/bin/java)

USER extender

ADD extender-0.1.0.jar app.jar
ENTRYPOINT ["java","-jar","/app.jar"]
EXPOSE 9000
