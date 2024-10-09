#!/bin/bash

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# enable mode when all variables exports by default
set -a
echo "Load common env ..."
source $SCRIPT_DIR/../../envs/.env

echo "Load user env ..."
source $SCRIPT_DIR/../../envs/user.env

if [[ -z ${ENV_PROFILE} ]]; then
    echo "Load macos environment..."
    source $SCRIPT_DIR/../../envs/macos.env
else
    echo "Load ${ENV_PROFILE} environment..."
    source $SCRIPT_DIR/../../envs/${ENV_PROFILE}.env
fi

# disable variable export mode
set +a

PROFILE=$2
if [[ -z ${PROFILE} ]]; then
    echo "No extender profile provided."
    PROFILE="standalone-dev"
fi
echo "Using profile ${PROFILE}."

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

    echo "Running: java -Xmx4g -XX:MaxDirectMemorySize=2g -Dorg.eclipse.jetty.server.Request.maxFormKeys=1500 -jar ${PATH_TO_JAR} --extender.sdk.location=${EXTENDER_SDK_LOCATION} --spring.config.location=classpath:./,file:${SPRING_PROFILES_LOCATION}/ --spring.profiles.active=${PROFILE}${STRUCTURED_LOGGING+,logging} ${STRUCTURED_LOGGING+--logging.config=${SPRING_PROFILES_LOCATION}/extender-logging.xml} >> ${STDOUT_LOG} 2>> ${ERROR_LOG} < /dev/null &"
    java -Xmx4g -XX:MaxDirectMemorySize=2g -Dorg.eclipse.jetty.server.Request.maxFormKeys=1500 -jar ${PATH_TO_JAR} --extender.sdk.location="${EXTENDER_SDK_LOCATION}" --spring.config.location=classpath:./,file:${SPRING_PROFILES_LOCATION}/ --spring.profiles.active=${PROFILE}${STRUCTURED_LOGGING+,logging} ${STRUCTURED_LOGGING+--logging.config=${SPRING_PROFILES_LOCATION}/extender-logging.xml} >> ${STDOUT_LOG} 2>> ${ERROR_LOG} < /dev/null &

    
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
