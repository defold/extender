#!/bin/bash -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
SOURCE_DIR=${SCRIPT_DIR}/../..

VERSION=$(date "+%Y%m%d_%H%M")

if [ "" == "$TARGET_DIR" ]; then
    TARGET_DIR=/usr/local/extender
fi

# override extender.sdk.location (in src/main/resources/application-standalone-XYZ.yml) by
# setting EXTENDER_SDK_LOCATION and using it in service-standalone.sh
export EXTENDER_SDK_LOCATION=${TARGET_DIR}/sdk

source ${SCRIPT_DIR}/standalone/publish-standalone.sh

build_artifact ${SOURCE_DIR}
deploy_artifact ${SOURCE_DIR} ${TARGET_DIR} ${VERSION}

echo "Stop with:" ${TARGET_DIR}/current/service.sh stop ${TARGET_DIR}
echo "Log with:" tail -f ${TARGET_DIR}/logs/stdout.log
