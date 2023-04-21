#!/bin/bash -e

TARGET_DIR=$1
if [[ -z ${TARGET_DIR} ]]; then
	echo "Missing target dir"
	exit 1
fi

# override extender.sdk.location from application-standalone-dev.yml by
# setting EXTENDER_SDK_LOCATION and using it in service-standalone.sh
export TARGET_DIR=${TARGET_DIR}
export EXTENDER_SDK_LOCATION=${TARGET_DIR}/sdk

export ANDROID_HOME=~/android/sdk
export ANDROID_SDK_ROOT=${ANDROID_HOME}
export ANDROID_BUILD_TOOLS_VERSION=33.0.1
export ANDROID_SDK_VERSION=33
export ANDROID_SDK_BUILD_TOOLS_PATH=${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS_VERSION}
export ANDROID_LIBRARYJAR=${ANDROID_HOME}/platforms/android-${ANDROID_SDK_VERSION}/android.jar
export ANDROID_PROGUARD=_
export MANIFEST_MERGE_TOOL=${TARGET_DIR}/current/manifestmergetool.jar

export ANDROID_NDK25_VERSION=25b
export ANDROID_NDK25_FULLVERSION=25.1.8937393
export ANDROID_NDK25_API_VERSION=19
export ANDROID_64_NDK25_API_VERSION=21
export ANDROID_NDK25_PATH=${ANDROID_HOME}/ndk/${ANDROID_NDK25_FULLVERSION}
export ANDROID_NDK25_BIN_PATH=${ANDROID_NDK25_PATH}/toolchains/llvm/prebuilt/darwin-x86_64/bin
export ANDROID_NDK25_SYSROOT=${ANDROID_NDK25_PATH}/toolchains/llvm/prebuilt/darwin-x86_64/sysroot

export GRADLE_USER_HOME=/tmp/.gradle
export GRADLE_PLUGIN_VERSION=4.0.1

export EXTENSION_BUILD_GRADLE_TEMPLATE=${TARGET_DIR}/current/template.build.gradle
export EXTENSION_GRADLE_PROPERTIES_TEMPLATE=${TARGET_DIR}/current/template.gradle.properties
export EXTENSION_LOCAL_PROPERTIES_TEMPLATE=${TARGET_DIR}/current/template.local.properties


echo "Rebuilding and running standalone local extender from ${TARGET_DIR}"

mkdir -p ${TARGET_DIR}
if [ -f "${TARGET_DIR}/current/service.sh" ]; then
	echo "Stopping server"
	${TARGET_DIR}/current/service.sh stop ${TARGET_DIR}
fi

echo "Building server"
./server/scripts/build-standalone.sh -xtest

echo "Running server"
./server/scripts/run-standalone-local.sh ${TARGET_DIR}
tail -f ${TARGET_DIR}/logs/stdout.log