#!/bin/bash -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
SOURCE_DIR=${SCRIPT_DIR}/../..

VERSION=$(date "+%Y%m%d_%H%M")

TARGET_DIR=/usr/local/extender
INSTALL_DIR=${TARGET_DIR}/${VERSION}

source ${SCRIPT_DIR}/standalone/publish-standalone.sh

mkdir -p ${TARGET_DIR}

build_artifact ${SOURCE_DIR}
deploy_artifact ${SOURCE_DIR} ${TARGET_DIR} ${VERSION}

bash ${SCRIPT_DIR}/run-standalone-local.sh
