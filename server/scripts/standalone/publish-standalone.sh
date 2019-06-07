#!/bin/bash

ARTIFACT_PATH=build/artifact-standalone

build_artifact() {
    SOURCE_DIR=$1
    SERVER_DIR=${SOURCE_DIR}/server
    ARTIFACT_DIR=${SERVER_DIR}/${ARTIFACT_PATH}

    echo "[build]  Creating standalone extender server artifact at ${ARTIFACT_DIR}..."
    rm -rf ${ARTIFACT_DIR}
    mkdir -p ${ARTIFACT_DIR}
    cp ${SERVER_DIR}/build/libs/extender-0.1.0.jar ${ARTIFACT_DIR}/extender.jar
    cp ${SERVER_DIR}/scripts/standalone/setup-standalone-server.sh ${ARTIFACT_DIR}/setup.sh
    cp ${SERVER_DIR}/scripts/standalone/service-standalone.sh ${ARTIFACT_DIR}/service.sh
    chmod a+x ${ARTIFACT_DIR}/setup.sh ${ARTIFACT_DIR}/service.sh
    echo "[build]  Artifact created."
}

deploy_artifact() {
    SOURCE_DIR=$1
    SERVER_DIR=${SOURCE_DIR}/server
    ARTIFACT_DIR=${SERVER_DIR}/${ARTIFACT_PATH}

    TARGET_DIR=$2
    VERSION=$3
    TARGET_HOST=$4
    TARGET_USER=$5

    if [ -z ${TARGET_HOST} ]
    then
        echo "[deploy] Copying artifact ${VERSION} to local directory ${TARGET_DIR}..."
        cp -r ${ARTIFACT_DIR} ${TARGET_DIR}/${VERSION}
        echo "[deploy] Running setup script on local machine..."
        bash ${TARGET_DIR}/${VERSION}/setup.sh ${VERSION} ${TARGET_DIR}
    else
        echo "[deploy] Secure copying artifact ${VERSION} to target ${TARGET_USER}@${TARGET_HOST}:${TARGET_DIR}..."
        scp -r ${ARTIFACT_DIR} ${TARGET_USER}@${TARGET_HOST}:${TARGET_DIR}/${VERSION}
        echo "[deploy] Running setup script on target host..."
        ssh ${TARGET_USER}@${TARGET_HOST} bash ${TARGET_DIR}/${VERSION}/setup.sh ${VERSION} ${TARGET_DIR} /usr/local/bin/extender
    fi

    echo "[deploy] Deployment done."
}
