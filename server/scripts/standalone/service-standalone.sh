#!/bin/bash

EXTENDER_DIR=$2
echo "Using extender dir ${EXTENDER_DIR}."

PROFILE=$3
if [[ -z ${PROFILE} ]]; then
    echo "No extender profile provided."
    PROFILE="standalone-dev"
fi
echo "Using profile ${PROFILE}."

SERVICE_NAME=extender
PATH_TO_JAR=${EXTENDER_DIR}/current/extender.jar
PID_PATH_NAME=${EXTENDER_DIR}/${SERVICE_NAME}.pid
LOG_DIRECTORY=${EXTENDER_DIR}/logs
STDOUT_LOG=${LOG_DIRECTORY}/stdout.log
ERROR_LOG=${LOG_DIRECTORY}/error.log

# The SDK path is used by Defold SDK build.yml
export PLATFORMSDK_DIR=${EXTENDER_DIR}/platformsdk

export MANIFEST_MERGE_TOOL=${EXTENDER_DIR}/current/manifestmergetool.jar

# From Dockerfile
# Also update setup-standalone-server.sh

# Versions from >=1.2.191
export XCODE_13_VERSION=13.2.1
export MACOS_12_VERSION=12.1
export IOS_15_VERSION=15.2
export XCODE_13_CLANG_VERSION=13.0.0
export LIB_TAPI_1_6_PATH=/usr/local/tapi1.6/lib

# Versions from >=1.4.4
export XCODE_14_VERSION=14.2
export MACOS_13_VERSION=13.1
export IOS_16_VERSION=16.2
export XCODE_14_CLANG_VERSION=14.0.0
export SWIFT_5_5_VERSION=5.5
export IOS_VERSION_MIN=11.0
export MACOS_VERSION_MIN=10.13


export EXTENSION_PODFILE_TEMPLATE=${EXTENDER_DIR}/current/template.podfile

# We need access to the toolchain binary path from within the application
export PATH=${PLATFORMSDK_DIR}/XcodeDefault${XCODE_14_VERSION}.xctoolchain/usr/bin:/usr/local/bin:${PATH}

start_service() {
    echo "${SERVICE_NAME} starting..."

    # Check if pid file already exists and if the process in the pid file is running
    if [[ -f ${PID_PATH_NAME} ]]; then
        PID=$(cat ${PID_PATH_NAME});
        if [[ -n "$(ps -p ${PID} -o pid=)" ]]; then
            echo "Error! ${SERVICE_NAME} is already running, exiting."
            exit 2
        else
            echo "Warning: PID file exists but no process running, removing PID file."
            rm ${PID_PATH_NAME}
        fi
    fi

    echo "Running: java -Xmx2g -XX:MaxDirectMemorySize=1g -jar ${PATH_TO_JAR} --spring.profiles.active=${PROFILE} >> ${STDOUT_LOG} 2>> ${ERROR_LOG} < /dev/null &"
    java -Xmx2g -XX:MaxDirectMemorySize=1g -jar ${PATH_TO_JAR} --spring.profiles.active=${PROFILE} >> ${STDOUT_LOG} 2>> ${ERROR_LOG} < /dev/null &
    echo $! > ${PID_PATH_NAME}
    echo "${SERVICE_NAME} started."
}

stop_service() {
    if [[ -f ${PID_PATH_NAME} ]]; then
        PID=$(cat ${PID_PATH_NAME});
        echo "${SERVICE_NAME} stopping..."
        kill ${PID};
        echo "${SERVICE_NAME} stopped."
        rm ${PID_PATH_NAME}
    else
        echo "Warning: ${SERVICE_NAME} is not running, no PID file."
    fi
}

case $1 in
    start)
        start_service
    ;;
    stop)
        stop_service
    ;;
    restart)
        stop_service
        start_service
    ;;
esac
