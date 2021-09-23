#!/bin/bash -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
SOURCE_DIR=${SCRIPT_DIR}/../..

VERSION=$(date "+%Y%m%d_%H%M")

TARGET_HOST_URL=build-darwin.defold.com
TARGET_HOST=ec2-52-48-199-151.eu-west-1.compute.amazonaws.com
TARGET_USER=ec2-user
TARGET_DIR=/usr/local/extender
TARGET_KEY=~/.ssh/defold2_ec2.pem

source ${SCRIPT_DIR}/shared/tools.sh
source ${SCRIPT_DIR}/standalone/publish-standalone.sh

check_uncommitted_changes ${SOURCE_DIR}
build_artifact ${SOURCE_DIR}
deploy_artifact ${SOURCE_DIR} ${TARGET_DIR} ${VERSION} ${TARGET_HOST} ${TARGET_USER} ${TARGET_KEY}

SERVER=https://${TARGET_HOST_URL}

echo "**********************************"
echo "Checking the server version:"
wget -q -O - $SERVER
echo ""
echo "**********************************"
