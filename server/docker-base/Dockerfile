FROM ubuntu:20.04

RUN \
  echo "LINUX TOOLS + COMPILER" && \
  apt-get update && \
  apt-get install -y software-properties-common && \
  apt-get update && \
  apt-get install -y --no-install-recommends \
    wget \
    locales \
    libssl-dev \
    openssl \
    libtool \
    autoconf \
    automake \
    build-essential \
    uuid-dev \
    libxi-dev \
    libx11-xcb-dev \
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

#
# Java
#
ENV JAVA_HOME /usr/local/jdk-17.0.7+7
ENV PATH ${JAVA_HOME}/bin:${PATH}

RUN \
  wget -q -O - https://aka.ms/download-jdk/microsoft-jdk-17.0.7-linux-x64.tar.gz | tar xz -C /usr/local && \
  java -version && \
  javac -version

# Add extender user
RUN  useradd -r -u 2222 extender && \
  mkdir -p /var/extender && \
  chown extender: /var/extender && \
  chown extender: $(which java) && \
  chmod +s $(which java)

ARG DM_PACKAGES_URL

# Put all SDK's into a single folder (just as we do in the regular engine build)
ENV PLATFORMSDK_DIR /opt/platformsdk
RUN mkdir $PLATFORMSDK_DIR

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
# llvm
#
ENV CLANG_VERSION=17
RUN \
  echo "LLVM + CLANG ${CLANG_VERSION}" && \
  wget https://apt.llvm.org/llvm.sh && \
  chmod +x llvm.sh && \
  ./llvm.sh ${CLANG_VERSION} && \
  rm llvm.sh

ENV CLANG_17_HOME=/usr/lib/llvm-${CLANG_VERSION}
# Possibly get the "-resource-dir" from clang++ -### empty.cpp
ENV CLANG_17_RESOURCE_DIR=/usr/lib/llvm-${CLANG_VERSION}/lib/clang/${CLANG_VERSION}
ENV PATH=${CLANG_17_HOME}/bin:$PATH

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

ENV EMSCRIPTEN_SDK_3_1_55 ${PLATFORMSDK_DIR}/emsdk-3.1.55
ENV EMSCRIPTEN_HOME_3_1_55 ${EMSCRIPTEN_SDK_3_1_55}
ENV EMSCRIPTEN_CACHE_3_1_55 /var/extender/emcache_3_1_55
ENV EMSCRIPTEN_CONFIG_3_1_55 ${EMSCRIPTEN_HOME_3_1_55}/.emscripten
ENV EMSCRIPTEN_PYTHON_3_1_55 /usr/bin/python3.9
ENV EMSCRIPTEN_BIN_3_1_55 ${EMSCRIPTEN_HOME_3_1_55}/upstream/emscripten
ENV EMSCRIPTEN_PATH_3_1_55 ${EMSCRIPTEN_HOME_3_1_55}:${EMSCRIPTEN_HOME_3_1_55}/upstream/bin:${EMSCRIPTEN_HOME_3_1_55}/node/16.20.0_64bit/bin:${EMSCRIPTEN_BIN_3_1_55}

RUN \
  mkdir ${EMSCRIPTEN_SDK_3_1_55} && \
  wget -q -O - ${DM_PACKAGES_URL}/emsdk-3.1.55-x86_64-linux.tar.gz | tar xz -C ${EMSCRIPTEN_SDK_3_1_55} --strip-components=1

RUN \
  ${EMSCRIPTEN_HOME_3_1_55}/emsdk activate sdk-3.1.55-64bit && \
  EM_CONFIG=$EMSCRIPTEN_CONFIG_3_1_55 EM_CACHE=${EMSCRIPTEN_CACHE_3_1_55} python3 ${EMSCRIPTEN_BIN_3_1_55}/embuilder.py build SYSTEM MINIMAL && \
  chmod -R 755 ${EMSCRIPTEN_HOME_3_1_55} && \
  chown -R extender: ${EMSCRIPTEN_CACHE_3_1_55}
  # && \
  # chown -R extender: ${EMSCRIPTEN_CACHE_3_1_55}/wasm/cache.lock


# We use the same temp directory for both versions.
ENV EMSCRIPTEN_TEMP_DIR /var/extender/ems_temp
RUN mkdir -p ${EMSCRIPTEN_TEMP_DIR}
RUN chmod -R 755 ${EMSCRIPTEN_TEMP_DIR} && chown extender: ${EMSCRIPTEN_TEMP_DIR}
# The "sed" command below removes the /TEMP_DIR line from the generated configs
# We replace it with a folder of our own
RUN sed '/TEMP_DIR =/d' ${EMSCRIPTEN_CONFIG_2_0_11} && \
  echo TEMP_DIR = \'${EMSCRIPTEN_TEMP_DIR}\' >> ${EMSCRIPTEN_CONFIG_2_0_11} && \
  sed '/TEMP_DIR =/d' ${EMSCRIPTEN_CONFIG_3_1_55} && \
  echo TEMP_DIR = \'${EMSCRIPTEN_TEMP_DIR}\' >> ${EMSCRIPTEN_CONFIG_3_1_55}

RUN \
  update-alternatives --set python3 /usr/bin/python3.8


#
# Windows
#

ENV \
  PLATFORMSDK_WIN32=$PLATFORMSDK_DIR/Win32 \
  WINDOWS_SDK_10_18362_VERSION=10.0.18362.0 \
  WINDOWS_SDK_10_20348_VERSION=10.0.20348.0 \
  WINDOWS_MSVC_2019_VERSION=14.25.28610 \
  WINDOWS_MSVC_2022_VERSION=14.37.32822

# The version part is deprecated since 1.6.2 (we should leave that to the build_input.yml)
ENV \
  WINDOWS_SDK_10_DIR="${PLATFORMSDK_WIN32}/WindowsKits/10/" \
  WindowsSdkDir="${PLATFORMSDK_WIN32}/WindowsKits/10/" \
  WindowsLibPath="${PLATFORMSDK_WIN32}/WindowsKits/10/References/${WINDOWS_SDK_10_18362_VERSION}" \
  WindowsSDKLibVersion="${WINDOWS_SDK_10_18362_VERSION}" \
  WindowsSDKVersion="${WINDOWS_SDK_10_18362_VERSION}"

# Grabbed after a starting MSVC 2019, and choosing "Tools -> Command Line -> Developer Command Prompt"
# Note: VCINSTALLDIR is special since clang will use it as the last "-internal-isystem" option
ENV \
  VCINSTALLDIR="${PLATFORMSDK_WIN32}/MicrosoftVisualStudio2019/VC/" \
  VSINSTALLDIR="${PLATFORMSDK_WIN32}/MicrosoftVisualStudio2019/" \
  WINDOWS_MSVC_2019_DIR="${PLATFORMSDK_WIN32}/MicrosoftVisualStudio2019/VC/Tools/MSVC/${WINDOWS_MSVC_2019_VERSION}/" \
  VS160COMNTOOLS="${PLATFORMSDK_WIN32}/MicrosoftVisualStudio2019/Common7/Tools/" \
  WINDOWS_VCINSTALLDIR="${PLATFORMSDK_WIN32}/MicrosoftVisualStudio2019/VC/Tools/MSVC/${WINDOWS_MSVC_2019_VERSION}/" \
  WINDOWS_VSINSTALLDIR="${PLATFORMSDK_WIN32}/MicrosoftVisualStudio2019/"

ENV \
  VCINSTALLDIR_2022="${PLATFORMSDK_WIN32}/MicrosoftVisualStudio2022/VC/" \
  VSINSTALLDIR_2022="${PLATFORMSDK_WIN32}/MicrosoftVisualStudio2022/" \
  WINDOWS_MSVC_DIR_2022="${PLATFORMSDK_WIN32}/MicrosoftVisualStudio2022/VC/Tools/MSVC/${WINDOWS_MSVC_2022_VERSION}/" \
  VS160COMNTOOLS_2022="${PLATFORMSDK_WIN32}/MicrosoftVisualStudio2022/Common7/Tools/" \
  WINDOWS_VCINSTALLDIR_2022="${PLATFORMSDK_WIN32}/MicrosoftVisualStudio2022/VC/Tools/MSVC/${WINDOWS_MSVC_2022_VERSION}/" \
  WINDOWS_VSINSTALLDIR_2022="${PLATFORMSDK_WIN32}/MicrosoftVisualStudio2022/"

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
  echo "WIN32 2022 SDK  " && \
  mkdir -p ${PLATFORMSDK_WIN32}/MicrosoftVisualStudio2022 && \
  wget -q -O - ${DM_PACKAGES_URL}/Microsoft-Visual-Studio-2022-${WINDOWS_MSVC_2022_VERSION}.tar.gz | tar xz -C ${PLATFORMSDK_WIN32}/MicrosoftVisualStudio2022

RUN \
  echo "WIN32 ${WINDOWS_SDK_10_18362_VERSION} SDK  " && \
  mkdir -p ${PLATFORMSDK_WIN32}/WindowsKits && \
  wget -q -O - ${DM_PACKAGES_URL}/WindowsKits-${WINDOWS_SDK_10_18362_VERSION}.tar.gz | tar xz -C ${PLATFORMSDK_WIN32}/WindowsKits

RUN \
  echo "WIN32 ${WINDOWS_SDK_10_20348_VERSION} SDK  " && \
  mkdir -p ${PLATFORMSDK_WIN32}/WindowsKits && \
  wget -q -O - ${DM_PACKAGES_URL}/WindowsKits-${WINDOWS_SDK_10_20348_VERSION}.tar.gz | tar xz -C ${PLATFORMSDK_WIN32}/WindowsKits

RUN \
  ln -s $(which clang) $(dirname $(which clang))/x86_64-pc-win32-clang && \
  ln -s $(which llvm-ar) $(dirname $(which llvm-ar))/x86_64-pc-win32-clang-ar

# Legacy. Deprecated from 1.6.2
ENV WINDOWS_SDK_10_VERSION ${WINDOWS_SDK_10_18362_VERSION}

# Due to Windows' case insensitive file system, the sources reference lib files with wrong cases
# so we solve the bulk by making the suffixes lowercase. (e.g. MyLib.Lib -> MyLib.lib)
RUN find $PLATFORMSDK_WIN32 -iname '*.Lib' -type f -exec sh -c 'a=$(echo "$0" | sed -r "s/([^.]*)\$/\L\1/"); [ "$a" != "$0" ] && [ ! -f "$a" ] && ln -s "$0" "$a" ' {} \;

COPY winsdk_rename_files.py ${PLATFORMSDK_WIN32}
RUN \
  echo "Renaming header files" && \
  python ${PLATFORMSDK_WIN32}/winsdk_rename_files.py > ${PLATFORMSDK_WIN32}/rename.txt

# Make a copy of all the headers too, in lower case (e.g. Windows.h -> windows.h etc)
RUN find $PLATFORMSDK_WIN32 -iname '*.h' -type f -exec sh -c 'd=$(dirname "$0"); a=$(basename "$0" | tr [:upper:] [:lower:]); [ "$a" != $(basename "$0")  ] && [ ! -f "$d/$a" ] && ln -s "$0" "$d/$a" ' {} \;

# Make lower case links of libraries as well
RUN find ${WINDOWS_SDK_10_DIR}/Lib -iname '*.lib' -type f -exec sh -c 'd=$(dirname "$0"); a=$(basename "$0" | tr [:upper:] [:lower:]); [ "$a" != $(basename "$0")  ] && [ ! -f "$d/$a" ] && ln -s "$0" "$d/$a" ' {} \;

RUN \
  echo "WIN32 SDK - 2019 - Cleanup" && \
# and the rest are manually copied (or made lower case)
  (cd ${WINDOWS_MSVC_2019_DIR}/lib/x64 && cp oldnames.lib OLDNAMES.lib) && \
  (cd ${WINDOWS_MSVC_2019_DIR}/lib/x86 && cp oldnames.lib OLDNAMES.lib) && \
  (cd ${WINDOWS_MSVC_2019_DIR}/lib/x64 && cp libcmt.lib LIBCMT.lib) && \
  (cd ${WINDOWS_MSVC_2019_DIR}/lib/x86 && cp libcmt.lib LIBCMT.lib) && \
  (cd ${WINDOWS_MSVC_2019_DIR}/lib/x64 && cp delayimp.lib Delayimp.lib) && \
  (cd ${WINDOWS_MSVC_2019_DIR}/lib/x86 && cp delayimp.lib Delayimp.lib)


RUN \
  echo "WIN32 SDK - 2022 - Debug" && \
# and the rest are manually copied (or made lower case)
  echo ${WINDOWS_MSVC_DIR_2022}


RUN \
  echo "WIN32 SDK - 2022 - Cleanup" && \
# and the rest are manually copied (or made lower case)
  (cd ${WINDOWS_MSVC_DIR_2022}/lib/x64 && cp oldnames.lib OLDNAMES.lib) && \
  (cd ${WINDOWS_MSVC_DIR_2022}/lib/x86 && cp oldnames.lib OLDNAMES.lib) && \
  (cd ${WINDOWS_MSVC_DIR_2022}/lib/x64 && cp libcmt.lib LIBCMT.lib) && \
  (cd ${WINDOWS_MSVC_DIR_2022}/lib/x86 && cp libcmt.lib LIBCMT.lib) && \
  (cd ${WINDOWS_MSVC_DIR_2022}/lib/x64 && cp delayimp.lib Delayimp.lib) && \
  (cd ${WINDOWS_MSVC_DIR_2022}/lib/x86 && cp delayimp.lib Delayimp.lib)

# Some headers are named by the wrong name in the windows sdk's...
# We need to make certain names lowercase because some users
# have put "pragma lib" comments in some libraries :(
# and/or misspelled header files
RUN \
  echo "WIN32 WindowsKits ${WINDOWS_SDK_10_18362_VERSION} - Cleanup" && \
  (cd ${WINDOWS_SDK_10_DIR}/Include/${WINDOWS_SDK_10_18362_VERSION}/shared && cp driverspecs.h DriverSpecs.h) && \
  (cd ${WINDOWS_SDK_10_DIR}/Include/${WINDOWS_SDK_10_18362_VERSION}/shared && cp specstrings.h SpecStrings.h) && \
  (cd ${WINDOWS_SDK_10_DIR}/Include/${WINDOWS_SDK_10_18362_VERSION}/shared && cp concurrencysal.h ConcurrencySal.h) && \
  (cd ${WINDOWS_SDK_10_DIR}/Include/${WINDOWS_SDK_10_18362_VERSION}/shared && cp wlantypes.h WlanTypes.h)

RUN \
  echo "WIN32 WindowsKits ${WINDOWS_SDK_10_20348_VERSION} - Cleanup" && \
  (cd ${WINDOWS_SDK_10_DIR}/Include/${WINDOWS_SDK_10_20348_VERSION}/shared && cp driverspecs.h DriverSpecs.h) && \
  (cd ${WINDOWS_SDK_10_DIR}/Include/${WINDOWS_SDK_10_20348_VERSION}/shared && cp specstrings.h SpecStrings.h) && \
  (cd ${WINDOWS_SDK_10_DIR}/Include/${WINDOWS_SDK_10_20348_VERSION}/shared && cp concurrencysal.h ConcurrencySal.h) && \
  (cd ${WINDOWS_SDK_10_DIR}/Include/${WINDOWS_SDK_10_20348_VERSION}/shared && cp wlantypes.h WlanTypes.h) && \
  (cd ${WINDOWS_SDK_10_DIR}/Lib/${WINDOWS_SDK_10_20348_VERSION}/um/x64 && cp psapi.lib Psapi.lib) && \
  (cd ${WINDOWS_SDK_10_DIR}/Lib/${WINDOWS_SDK_10_20348_VERSION}/um/x86 && cp psapi.lib Psapi.lib)

# Also, the OpenGL headers in the windows SDK is in a folder with lower case letters, which doesn't match the includes
RUN \
  echo "WIN32 WindowsKits ${WINDOWS_SDK_10_18362_VERSION} - OpenGL Cleanup" && \
  cd ${WINDOWS_SDK_10_DIR}/Include/${WINDOWS_SDK_10_18362_VERSION}/um && \
  mkdir ./GL && \
  cp -v ./gl/*.* ./GL/

# Also, the OpenGL headers in the windows SDK is in a folder with lower case letters, which doesn't match the includes
RUN \
  echo "WIN32 WindowsKits ${WINDOWS_SDK_10_20348_VERSION} - OpenGL Cleanup" && \
  cd ${WINDOWS_SDK_10_DIR}/Include/${WINDOWS_SDK_10_20348_VERSION}/um && \
  mkdir ./GL && \
  cp -v ./gl/*.* ./GL/

#
# Android SDK/NDK
# https://developer.android.com/studio/command-line/variables
#
ENV ANDROID_ROOT ${PLATFORMSDK_DIR}/android
# Note: ANDROID_SDK_ROOT is deprecated (https://developer.android.com/tools/variables)
ENV ANDROID_HOME ${ANDROID_ROOT}/android-sdk-linux
ENV ANDROID_SDK_ROOT ${ANDROID_HOME}
# ANDROID_SDK_HOME is the location of the .android folder
ENV ANDROID_SDK_HOME ${ANDROID_ROOT}/.android

#
# SDK 33 (Defold 1.4.3):
#

ENV ANDROID_BUILD_TOOLS_VERSION_33 33.0.1
ENV ANDROID_SDK_VERSION_33 33
ENV ANDROID_SDK_FILENAME_33 android-sdk-linux-android-${ANDROID_SDK_VERSION_33}-${ANDROID_BUILD_TOOLS_VERSION_33}.tar.gz

ENV ANDROID_SDK_BUILD_TOOLS_PATH_33 ${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS_VERSION_33}
ENV ANDROID_LIBRARYJAR_33 ${ANDROID_HOME}/platforms/android-${ANDROID_SDK_VERSION_33}/android.jar

#
# SDK 34 (Defold 1.9.0):
#

ENV ANDROID_BUILD_TOOLS_VERSION_34 34.0.0
ENV ANDROID_SDK_VERSION_34 34
ENV ANDROID_SDK_FILENAME_34 android-sdk-linux-android-${ANDROID_SDK_VERSION_34}-${ANDROID_BUILD_TOOLS_VERSION_34}.tar.gz

ENV ANDROID_SDK_BUILD_TOOLS_PATH_34 ${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS_VERSION_34}
ENV ANDROID_LIBRARYJAR_34 ${ANDROID_HOME}/platforms/android-${ANDROID_SDK_VERSION_34}/android.jar

# We specify it in build_input.yml by setting it the first in PATH for new SDK
# But at least one SDK should be specified by default in Dockerfile
ENV PATH ${PATH}:${ANDROID_HOME}/tools:${ANDROID_HOME}/platform-tools:${ANDROID_SDK_BUILD_TOOLS_PATH_33}
# ANDROID_SDK_VERSION used in Java code, default should be specified in Dockerfile as well:
ENV ANDROID_SDK_VERSION ${ANDROID_SDK_VERSION_34}

#
# NDK25b (Defold 1.4.3):
#

ENV ANDROID_NDK25_VERSION        25b
ENV ANDROID_NDK25_API_VERSION    19
ENV ANDROID_64_NDK25_API_VERSION 21

# These paths are the same for both the 32 and 64 bit toolchains
ENV ANDROID_NDK25_PATH       ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK25_VERSION}
ENV ANDROID_NDK25_BIN_PATH   ${ANDROID_NDK25_PATH}/toolchains/llvm/prebuilt/linux-x86_64/bin
ENV ANDROID_NDK25_SYSROOT    ${ANDROID_NDK25_PATH}/toolchains/llvm/prebuilt/linux-x86_64/sysroot
ENV ANDROID_NDK25_FILENAME   android-ndk-r${ANDROID_NDK25_VERSION}-linux.tar.gz

# We specify it in build_input.yml by setting it the first in PATH
# ENV PATH ${PATH}:${ANDROID_NDK25_BIN_PATH}

#
#

# android proguard was version 4.7, this is at least 5.2.1 which seems to work with OpenJDK 11
RUN \
  apt-get update && \
  apt-get install -y --no-install-recommends proguard && \
  ls -la /usr/share/java/proguard.jar

ENV ANDROID_PROGUARD=/usr/share/java/proguard.jar

RUN \
    echo "Android SDK" && \
    mkdir -p ${ANDROID_HOME} && \
    wget -q -O - ${DM_PACKAGES_URL}/${ANDROID_NDK25_FILENAME} | tar xz -C ${ANDROID_ROOT} && \
    wget -q -O - ${DM_PACKAGES_URL}/${ANDROID_SDK_FILENAME_33} | tar xz -C ${ANDROID_HOME} --strip-components=1 && \
    wget -q -O - ${DM_PACKAGES_URL}/${ANDROID_SDK_FILENAME_34} | tar xz -C ${ANDROID_HOME} --strip-components=1 && \
#cleanup
    rm -rf ${ANDROID_HOME}/extras && \
    rm -rf ${ANDROID_NDK25_PATH}/prebuilt && \
    rm -rf ${ANDROID_NDK25_PATH}/simpleperf && \
    rm -rf ${ANDROID_NDK25_PATH}/shader-tools && \
    rm -rf ${ANDROID_NDK25_PATH}/sources/third_party && \
    rm -rf ${ANDROID_NDK25_PATH}/sources/cxx-stl && \
    rm -rf ${ANDROID_NDK25_PATH}/toolchains/llvm/prebuilt/linux-x86_64/i686-linux-android && \
    rm -rf ${ANDROID_NDK25_PATH}/toolchains/llvm/prebuilt/linux-x86_64/x86_64-linux-android && \
    rm -rf ${ANDROID_NDK25_PATH}/toolchains/llvm/prebuilt/linux-x86_64/bin/i686-* && \
    rm -rf ${ANDROID_NDK25_PATH}/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-* && \
    rm -rf ${ANDROID_NDK25_PATH}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/x86_64-linux-android && \
    rm -rf ${ANDROID_NDK25_PATH}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/i686-linux-android && \

# create the .android folder and give read+write permissions (the Android Gradle plugin will write to the folder)
# It is not enough to give 'user' and 'group'. We unfortunately also need 'others'
    mkdir ${ANDROID_SDK_HOME} && \
    chmod ugo+rw -R ${ANDROID_SDK_HOME} && \
# fix permissions
    chmod +r -R ${ANDROID_ROOT} && \
    chmod +w -R ${ANDROID_SDK_ROOT} && \
    chmod -R 755 ${ANDROID_ROOT}/android-ndk-r${ANDROID_NDK25_VERSION} && \
# check that dx installed properly
    ls -la ${ANDROID_SDK_BUILD_TOOLS_PATH_33}/d8 && \
    ls -la ${ANDROID_SDK_BUILD_TOOLS_PATH_34}/d8 && \
    which ${ANDROID_NDK25_BIN_PATH}/armv7a-linux-androideabi${ANDROID_NDK25_API_VERSION}-clang++ && \
    which ${ANDROID_NDK25_BIN_PATH}/aarch64-linux-android${ANDROID_64_NDK25_API_VERSION}-clang++ && \
# check that aapt installed correctly
    aapt v  && \
    aapt2 version

#
# Switch SDK
#

# Added in version 1.4.4
ENV NINTENDO_SDK_ROOT_15_3_2 ${PLATFORMSDK_DIR}/nx-15.3.2
ENV SWITCH_SDK_15_FILENAME nx64-sdk-15.3.2.tar.gz

RUN \
  echo "Switch SDK 15.3.2" && \
  mkdir -p ${NINTENDO_SDK_ROOT_15_3_2} && \
  wget -q -O - ${DM_PACKAGES_URL}/${SWITCH_SDK_15_FILENAME} | tar xz -C ${NINTENDO_SDK_ROOT_15_3_2}

ENV NINTENDO_SDK_ROOT_17_5 ${PLATFORMSDK_DIR}/nx-17.5.3
ENV SWITCH_SDK_17_FILENAME nx64-sdk-17.5.3.tar.gz

RUN \
   echo "Switch SDK 17.5.3" && \
   mkdir -p ${NINTENDO_SDK_ROOT_17_5} && \
   wget -q -O - ${DM_PACKAGES_URL}/${SWITCH_SDK_17_FILENAME} | tar xz -C ${NINTENDO_SDK_ROOT_17_5}

#
# PS4 SDK
#

ENV PS4_SDK_10  ${PLATFORMSDK_DIR}/ps4-sdk-10.500

ENV PS4_SDK_10_FILENAME ps4-sdk-10.500.tar.gz

RUN \
  echo "PS4 ${PS4_SDK_10_FILENAME}" && \
  mkdir -p ${PS4_SDK_10} && \
  wget -q -O - ${DM_PACKAGES_URL}/${PS4_SDK_10_FILENAME} | tar xz -C ${PS4_SDK_10} --strip-components=1


ENV PS4_SDK_11_VERSION 11.000
ENV PS4_SDK_11  ${PLATFORMSDK_DIR}/ps4-sdk-${PS4_SDK_11_VERSION}

ENV PS4_SDK_11_FILENAME ps4-sdk-${PS4_SDK_11_VERSION}.tar.gz

RUN \
  echo "PS4 ${PS4_SDK_11_FILENAME}" && \
  mkdir -p ${PS4_SDK_11} && \
  wget -q -O - ${DM_PACKAGES_URL}/${PS4_SDK_11_FILENAME} | tar xz -C ${PS4_SDK_11} --strip-components=1


#
# PS5 SDK
#

ENV PS5_SDK_8_VERSION 8.000
ENV PS5_SDK_8  ${PLATFORMSDK_DIR}/ps5-sdk-${PS5_SDK_8_VERSION}

ENV PS5_SDK_8_FILENAME ps5-sdk-${PS5_SDK_8_VERSION}.tar.gz

RUN \
  echo "PS5 ${PS5_SDK_8_FILENAME}" && \
  mkdir -p ${PS5_SDK_8} && \
  wget -q -O - ${DM_PACKAGES_URL}/${PS5_SDK_8_FILENAME} | tar xz -C ${PS5_SDK_8} --strip-components=1


#
# Wine
#

# Installation notes: https://wiki.winehq.org/Ubuntu
# TODO: Backup the files as descibed here: https://wiki.winehq.org/Ubuntu
RUN \
 echo "Wine deps" && \
 apt-get update && \
 apt-get install -y --no-install-recommends apt-transport-https ca-certificates ca-certificates-java xvfb cabextract && \
 apt-get clean autoclean autoremove

# From https://github.com/scottyhardy/docker-wine/blob/master/Dockerfile

# Install wine
ARG WINE_BRANCH="stable"
RUN wget -nv -O- https://dl.winehq.org/wine-builds/winehq.key | APT_KEY_DONT_WARN_ON_DANGEROUS_USAGE=1 apt-key add - \
    && echo "deb https://dl.winehq.org/wine-builds/ubuntu/ $(grep VERSION_CODENAME= /etc/os-release | cut -d= -f2) main" >> /etc/apt/sources.list \
    && dpkg --add-architecture i386 \
    && apt-get update \
    && DEBIAN_FRONTEND="noninteractive" apt-get install -y --install-recommends winehq-${WINE_BRANCH} \
    && rm -rf /var/lib/apt/lists/*

# Install winetricks
RUN wget -nv -O /usr/bin/winetricks https://raw.githubusercontent.com/Winetricks/winetricks/master/src/winetricks \
    && chmod +x /usr/bin/winetricks

ENV PATH ${PATH}:/opt/wine-stable/bin

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
ENV GRADLE_JAVA_HOME /usr/local/jdk-17.0.7+7
ENV GRADLE_VERSION 8.4
ENV GRADLE_PLUGIN_VERSION 8.3.2
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

ENV EXTENSION_PODFILE_TEMPLATE /var/extender/template.podfile
COPY template.podfile ${EXTENSION_PODFILE_TEMPLATE}

ENV EXTENSION_MODULEMAP_TEMPLATE /var/extender/template.modulemap
COPY template.modulemap ${EXTENSION_MODULEMAP_TEMPLATE}

ENV EXTENSION_UMBRELLAHEADER_TEMPLATE /var/extender/template.umbrella.h
COPY template.umbrella.h ${EXTENSION_UMBRELLAHEADER_TEMPLATE}

#
# ZIG
#
# https://ziglang.org/download/

ENV ZIG_VERSION=0.11.0
ENV ZIG_PATH_0_11=${PLATFORMSDK_DIR}/zig-0-11
ENV ZIG_URL=https://ziglang.org/download/${ZIG_VERSION}/zig-linux-x86_64-${ZIG_VERSION}.tar.xz
ENV PATH ${PATH}:${ZIG_PATH_0_11}

RUN \
  echo "ZIG" && \
  mkdir -p ${ZIG_PATH_0_11} && \
  wget -q -O - ${ZIG_URL} | tar xJ -C ${ZIG_PATH_0_11} --strip-components=1 && \
  zig version


# Final cleanup

RUN \
  apt-get remove -y apt-transport-https xvfb && \
  apt-get clean autoclean autoremove

# Always run last to minimize the size
RUN apt-get clean autoclean autoremove
