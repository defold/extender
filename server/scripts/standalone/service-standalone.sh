#!/bin/bash

SERVICE_NAME=extender
EXTENDER_DIR=/usr/local/extender
ENVIRONMENT_FILE=${EXTENDER_DIR}/env.properties
PATH_TO_JAR=${EXTENDER_DIR}/current/extender.jar
PID_PATH_NAME=${EXTENDER_DIR}/${SERVICE_NAME}.pid
LOG_DIRECTORY=/usr/local/var/log/extender
STDOUT_LOG=${LOG_DIRECTORY}/stdout.log
ERROR_LOG=${LOG_DIRECTORY}/error.log

# The SDK path is used by Defold SDK build.yml
export PLATFORMSDK_DIR=${EXTENDER_DIR}/platformsdk

# We need access to the toolchain binary path from within the application
export PATH=${PLATFORMSDK_DIR}/XcodeDefault10.1.xctoolchain/usr/bin:/usr/local/bin:${PATH}

# Get the Spring profile from the environment file
if [ -f "${ENVIRONMENT_FILE}" ]
then
    while IFS='=' read -r key value
    do
        if [ "${key}" = "profile" ]; then
            PROFILE=$(echo ${value})
            echo "Using profile $PROFILE"
        fi
    done < "${ENVIRONMENT_FILE}"
else
    echo "Error! Environment file ${ENVIRONMENT_FILE} not found, exiting."
    exit 1;
fi

if [ -z ${PROFILE} ]
then
    echo "Error! Environment file ${ENVIRONMENT_FILE} not found, exiting."
    exit 1;
fi

start_service() {
    echo "${SERVICE_NAME} starting..."
    if [ ! -f ${PID_PATH_NAME} ]; then
        nohup java -Xmx1g -XX:MaxDirectMemorySize=512m -jar ${PATH_TO_JAR} --spring.profiles.active=${PROFILE} >> ${STDOUT_LOG} 2>> ${ERROR_LOG} < /dev/null &
        echo $! > ${PID_PATH_NAME}
        echo "${SERVICE_NAME} started."
    else
        echo "${SERVICE_NAME} is already running."
    fi
}

stop_service() {
    if [ -f ${PID_PATH_NAME} ]; then
        PID=$(cat ${PID_PATH_NAME});
        echo "${SERVICE_NAME} stopping..."
        kill ${PID};
        echo "${SERVICE_NAME} stopped."
        rm ${PID_PATH_NAME}
    else
        echo "${SERVICE_NAME} is not running."
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