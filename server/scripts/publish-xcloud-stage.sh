#!/bin/bash -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
SOURCE_DIR=${SCRIPT_DIR}/../..

VERSION=$(date "+%Y%m%d_%H%M")

TARGET_HOST=build-darwin-stage.defold.com
TARGET_USER=xcloud
TARGET_DIR=/usr/local/extender

source ${SCRIPT_DIR}/standalone/publish-standalone.sh

check_uncommitted_changes ${SOURCE_DIR}
build_artifact ${SOURCE_DIR}
deploy_artifact ${SOURCE_DIR} ${TARGET_DIR} ${VERSION} ${TARGET_HOST} ${TARGET_USER}

SERVER=https://${TARGET_HOST}

echo "**********************************"
echo "Checking the server version:"
wget -q -O - $SERVER
echo ""
echo "**********************************"
