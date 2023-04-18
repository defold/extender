#!/bin/bash -e

TARGET_DIR=$1
if [[ -z ${TARGET_DIR} ]]; then
	echo "Missing target dir"
	exit 1
fi

# override extender.sdk.location from application-standalone-dev.yml by
# setting EXTENDER_SDK_LOCATION and using it in service-standalone.sh
export TARGET_DIR=${TARGET_DIR}
export EXTENDER_SDK_LOCATION=${TARGET_DIR}/sdk

echo "Rebuilding and running standalone local extender from ${TARGET_DIR}"

mkdir -p ${TARGET_DIR}
if [ -f "${TARGET_DIR}/current/service.sh" ]; then
	echo "Stopping server"
	${TARGET_DIR}/current/service.sh stop ${TARGET_DIR}
fi

echo "Building server"
./server/scripts/build-standalone.sh -xtest

echo "Running server"
./server/scripts/run-standalone-local.sh ${TARGET_DIR}
tail -f ${TARGET_DIR}/logs/stdout.log