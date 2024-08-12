#!/bin/bash -e

export DM_DEBUG_COMMANDS=true
# export DM_DEBUG_KEEP_JOB_FOLDER=true

USE_ANDROID=true

#
# BEGIN Experimental Android setup
#
if [ "$USE_ANDROID" = true ] ; then
	# https://developer.android.com/tools/variables
	export ANDROID_ROOT=${HOME}/android
	export ANDROID_PROGUARD=${ANDROID_ROOT}/proguard.jar

	#
	# SDK SETUP
	# From Dockerfile
	#
	export ANDROID_BUILD_TOOLS_VERSION=32.0.0
	export ANDROID_BUILD_TOOLS_VERSION_33=33.0.1
	export ANDROID_BUILD_TOOLS_VERSION_34=34.0.0
	export ANDROID_SDK_VERSION=31
	export ANDROID_SDK_VERSION_33=33
	export ANDROID_SDK_VERSION_34=34

	# ANDROID_HOME = Sets the path to the SDK installation directory
	export ANDROID_HOME=${ANDROID_ROOT}/sdk
	export ANDROID_SDK_ROOT=${ANDROID_HOME} # ANDROID_SDK_ROOT is deprecated but keeping it here anyway
	# ANDROID_USER_HOME = Sets the path to the user preferences directory for tools
	export ANDROID_USER_HOME=${HOME}/.android
	export ANDROID_SDK_BUILD_TOOLS_PATH=${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS_VERSION}
	export ANDROID_SDK_BUILD_TOOLS_PATH_33=${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS_VERSION_33}
	export ANDROID_SDK_BUILD_TOOLS_PATH_34=${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS_VERSION_34}
	export ANDROID_LIBRARYJAR_33=${ANDROID_HOME}/platforms/android-${ANDROID_SDK_VERSION_33}/android.jar
	export ANDROID_LIBRARYJAR_34=${ANDROID_HOME}/platforms/android-${ANDROID_SDK_VERSION_34}/android.jar

	export PATH=${PATH}:${ANDROID_HOME}/tools
	export PATH=${PATH}:${ANDROID_HOME}/platform-tools
	export PATH=${PATH}:${ANDROID_SDK_BUILD_TOOLS_PATH}
	# There seems to be an issue on macOS when trying to modify the PATH from a ProcessBuilder
	# Anything added to the path seems to be completely ignored. We rely on this to work since
	# can override the ANDROID_SDK_BUILD_TOOLS_PATH set above.

	#
	# NDK SETUP
	# From Dockerfile
	#
	export ANDROID_NDK25_VERSION=25b
	export ANDROID_NDK25_API_VERSION=19
	export ANDROID_64_NDK25_API_VERSION=21
	# These paths are the same for both the 32 and 64 bit toolchains
	export ANDROID_NDK25_PATH=${ANDROID_ROOT}/ndk-r${ANDROID_NDK25_VERSION}
	export ANDROID_NDK25_BIN_PATH=${ANDROID_NDK25_PATH}/toolchains/llvm/prebuilt/darwin-x86_64/bin
	export ANDROID_NDK25_SYSROOT=${ANDROID_NDK25_PATH}/toolchains/llvm/prebuilt/darwin-x86_64/sysroot

	export PATH=${PATH}:${ANDROID_NDK25_BIN_PATH}

	#
	# Gradle setup
	# From Dockerfile
	#
	export GRADLE_PLUGIN_VERSION=8.2.1
	# export GRADLE_JAVA_HOME=/Users/bjornritzl/jdk-11.0.15+10/Contents/Home
	export GRADLE_JAVA_HOME=${JAVA_HOME}
fi


TARGET_DIR=$1
if [[ -z ${TARGET_DIR} ]]; then
	echo "Missing target dir"
	exit 1
fi

# realpath: brew install coreutils
if [ ! -d "${TARGET_DIR}" ]; then
	mkdir -p ${TARGET_DIR}
fi

export TARGET_DIR=$(realpath ${TARGET_DIR})

echo "Rebuilding and running standalone local extender from ${TARGET_DIR}"

if [ -f "${TARGET_DIR}/current/service.sh" ]; then
	echo "Stopping server"
	${TARGET_DIR}/current/service.sh stop ${TARGET_DIR}
fi

if [ ! -z ${DM_DEBUG_JOB_FOLDER} ] && [ -d ${DM_DEBUG_JOB_FOLDER} ]; then
	echo "Removing job folder"
	rm -rf ${DM_DEBUG_JOB_FOLDER}
fi

if [ ! -z ${DM_DEBUG_JOB_FOLDER} ]; then
	echo "Creating job folder"
	mkdir -p ${DM_DEBUG_JOB_FOLDER}
fi

echo "Building server"
./server/scripts/build-standalone.sh -xtest

echo "Running server"
./server/scripts/run-standalone-local.sh ${TARGET_DIR}
tail -f ${TARGET_DIR}/logs/stdout.log
