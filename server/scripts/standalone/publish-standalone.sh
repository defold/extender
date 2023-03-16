#!/bin/bash

ARTIFACT_PATH=build/artifact-standalone

build_artifact() {
    SOURCE_DIR=$1
    SERVER_DIR=${SOURCE_DIR}/server
    ARTIFACT_DIR=${SERVER_DIR}/${ARTIFACT_PATH}

    echo "[build]  Creating standalone extender server artifact at ${ARTIFACT_DIR}..."
    rm -rf ${ARTIFACT_DIR}
    mkdir -p ${ARTIFACT_DIR}
    cp ${SERVER_DIR}/build/standalone/extender-0.1.0.jar ${ARTIFACT_DIR}/extender.jar
    cp ${SERVER_DIR}/build/standalone/manifestmergetool-0.1.0.jar ${ARTIFACT_DIR}/manifestmergetool.jar
    cp ${SERVER_DIR}/docker-base/template.* ${ARTIFACT_DIR}/
    cp ${SERVER_DIR}/scripts/standalone/setup-standalone-server.sh ${ARTIFACT_DIR}/setup.sh
    cp ${SERVER_DIR}/scripts/standalone/service-standalone.sh ${ARTIFACT_DIR}/service.sh
    chmod a+x ${ARTIFACT_DIR}/setup.sh ${ARTIFACT_DIR}/service.sh ${ARTIFACT_DIR}/manifestmergetool.jar
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
    TARGET_KEY=$6
    TARGET_ENV=$7

    if [ -z ${DM_PACKAGES_URL} ]
    then
        echo "[deploy] DM_PACKAGES_URL is not set"
        exit 1
    fi

    if [ -z ${TARGET_HOST} ]
    then
        echo "[deploy] Copying artifact ${VERSION} to local directory ${TARGET_DIR}..."
        cp -r ${ARTIFACT_DIR} ${TARGET_DIR}/${VERSION}
        echo "[deploy] Running setup script on local machine..."
        bash ${TARGET_DIR}/${VERSION}/setup.sh ${VERSION} ${TARGET_DIR} /usr/local/bin/extender ${DM_PACKAGES_URL}
    else
        echo "[deploy] Secure copying artifact ${VERSION} to target ${TARGET_USER}@${TARGET_HOST}:${TARGET_DIR} using key ${TARGET_KEY}..."
        scp -i ${TARGET_KEY} -v -r ${ARTIFACT_DIR} ${TARGET_USER}@${TARGET_HOST}:${TARGET_DIR}/${VERSION}
        echo "[deploy] Running setup script on target host..."
        ssh -i ${TARGET_KEY} ${TARGET_USER}@${TARGET_HOST} bash ${TARGET_DIR}/${VERSION}/setup.sh ${VERSION} ${TARGET_DIR} /usr/local/bin/extender-${TARGET_ENV} ${DM_PACKAGES_URL} standalone-${TARGET_ENV}
    fi

    echo "[deploy] Deployment done."
}

cleanup_artifact() {
    SOURCE_DIR=$1
    SERVER_DIR=${SOURCE_DIR}/server
    ARTIFACT_DIR=${SERVER_DIR}/${ARTIFACT_PATH}
    if [ -e "$ARTIFACT_DIR" ]; then
        rm -rf ${ARTIFACT_DIR}
        echo "[cleanup] Removed temporary artifact directory ${ARTIFACT_DIR}"
    fi
}
