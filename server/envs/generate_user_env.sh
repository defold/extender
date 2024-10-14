#! /bin/sh

echo "Generate use dynamic environment..."

ENV_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

echo "Load common env ..."
source ${ENV_DIR}/.env


PLATFORMSDK_DIR=${ENV_DIR}/../../platformsdk
MANIFEST_MERGE_TOOL=${ENV_DIR}/../app/manifestmergetool.jar
SPRING_PROFILES_LOCATION=${ENV_DIR}/../configs

PATH_TO_JAR=${ENV_DIR}/../app/extender.jar
PID_PATH_NAME=${ENV_DIR}/../app/${SERVICE_NAME}.pid
LOG_DIRECTORY=${ENV_DIR}/../app/logs
STDOUT_LOG=${LOG_DIRECTORY}/stdout.log
ERROR_LOG=${LOG_DIRECTORY}/error.log
EXTENDER_SDK_LOCATION=${ENV_DIR}/../app/sdk

DOTNET_ROOT=${ENV_DIR}/../app/dotnet
DOTNET_CLI_HOME=${DOTNET_ROOT}
DOTNET_VERSION_FILE=${DOTNET_ROOT}/dotnet_version
NUGET_PACKAGES=${ENV_DIR}/../app/.nuget

ZIG_PATH_0_11=${PLATFORMSDK_DIR}/zig-${ZIG_VERSION}


OUTPUT_FILE=$ENV_DIR/user.env

if [[ -z $1 ]]; then
    echo "Load macos environment..."
    source ${ENV_DIR}/macos.env
    APPENDED_PATH=${PLATFORMSDK_DIR}/XcodeDefault${XCODE_15_VERSION}.xctoolchain/usr/bin:/usr/local/bin:${PATH}
else
    echo "Load $1 environment..."
    source ${ENV_DIR}/$1.env
fi

[ -f "$OUTPUT_FILE" ] && echo "Remove old user.env" && rm "$OUTPUT_FILE"

JAVA_HOME=`/usr/libexec/java_home`

echo "ENV_DIR=${ENV_DIR}" > $OUTPUT_FILE
echo "JAVA_HOME=${JAVA_HOME}" >> $OUTPUT_FILE
echo "PLATFORMSDK_DIR=${PLATFORMSDK_DIR}" >> $OUTPUT_FILE
echo "MANIFEST_MERGE_TOOL=${MANIFEST_MERGE_TOOL}" >> $OUTPUT_FILE
echo "SPRING_PROFILES_LOCATION=${SPRING_PROFILES_LOCATION}" >> $OUTPUT_FILE
echo "PATH_TO_JAR=${PATH_TO_JAR}" >> $OUTPUT_FILE
echo "PID_PATH_NAME=${PID_PATH_NAME}" >> $OUTPUT_FILE
echo "LOG_DIRECTORY=${LOG_DIRECTORY}" >> $OUTPUT_FILE
echo "STDOUT_LOG=${STDOUT_LOG}" >> $OUTPUT_FILE
echo "ERROR_LOG=${ERROR_LOG}" >> $OUTPUT_FILE
echo "EXTENDER_SDK_LOCATION=${EXTENDER_SDK_LOCATION}" >> $OUTPUT_FILE
echo "DOTNET_ROOT=${DOTNET_ROOT}" >> $OUTPUT_FILE
echo "DOTNET_CLI_HOME=${DOTNET_CLI_HOME}" >> $OUTPUT_FILE
echo "DOTNET_VERSION_FILE=${DOTNET_VERSION_FILE}" >> $OUTPUT_FILE
echo "NUGET_PACKAGES=${NUGET_PACKAGES}" >> $OUTPUT_FILE
# Added 1.4.9
echo "ZIG_PATH_0_11=${ZIG_PATH_0_11}" >> $OUTPUT_FILE

echo "PATH=\"${APPENDED_PATH}\"" >> $OUTPUT_FILE

echo "Generation completed."
