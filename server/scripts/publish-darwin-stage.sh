#!/bin/bash -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
CALLER_DIR=$(caller | awk "{print \$2}" | xargs dirname)
SERVER_DIR=${CALLER_DIR}/server
ARTIFACT_DIR=${SERVER_DIR}/build/artifact-darwin

VERSION=$(date "+%Y%m%d_%H%M")

TARGET_HOST=build-darwin-stage.defold.com
TARGET_USER=xcloud
TARGET_DIR=/usr/local/extender/${VERSION}

source ${DIR}/shared/tools.sh

build_artifact() {
    echo "[build]  Creating extender darwin server artifact at ${ARTIFACT_DIR} ..."
    rm -rf ${ARTIFACT_DIR}
    mkdir -p ${ARTIFACT_DIR}
    cp ${SERVER_DIR}/build/libs/extender-0.1.0.jar ${ARTIFACT_DIR}/extender.jar
    cp ${SERVER_DIR}/scripts/darwin/setup-darwin-server.sh ${ARTIFACT_DIR}/setup.sh
    cp ${SERVER_DIR}/scripts/darwin/service-darwin.sh ${ARTIFACT_DIR}/service.sh
    chmod a+x ${ARTIFACT_DIR}/setup.sh ${ARTIFACT_DIR}/service.sh
    echo "[build]  Artifact created."
}

deploy_artifact() {
    echo "[deploy] Secure copying artifact to ${TARGET_USER}@${TARGET_HOST}:${TARGET_DIR}"
    scp -r ${ARTIFACT_DIR} ${TARGET_USER}@${TARGET_HOST}:${TARGET_DIR}
    ssh ${TARGET_USER}@${TARGET_HOST} bash ${TARGET_DIR}/setup.sh ${VERSION}
}

check_uncommitted_changes ${CALLER_DIR}
build_artifact
deploy_artifact
