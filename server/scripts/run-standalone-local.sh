#!/bin/bash -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
SOURCE_DIR=${SCRIPT_DIR}/../..

VERSION=$(date "+%Y%m%d_%H%M")

if [ "" == "$TARGET_DIR" ]; then
    TARGET_DIR=/usr/local/extender
fi

source ${SCRIPT_DIR}/shared/tools.sh
source ${SCRIPT_DIR}/standalone/publish-standalone.sh

build_artifact ${SOURCE_DIR}
deploy_artifact ${SOURCE_DIR} ${TARGET_DIR} ${VERSION}

echo "Stop with:" ${TARGET_DIR}/current/service.sh stop ${TARGET_DIR}
echo "Log with:" tail -f ${TARGET_DIR}/logs/stdout.log
