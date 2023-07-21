#!/bin/bash -e

TARGET_DIR=$1
if [[ -z ${TARGET_DIR} ]]; then
	echo "Missing target dir"
	exit 1
fi

export TARGET_DIR=${TARGET_DIR}

echo "Rebuilding and running standalone local extender from ${TARGET_DIR}"

mkdir -p ${TARGET_DIR}
if [ -f "${TARGET_DIR}/current/service.sh" ]; then
	echo "Stopping server"
	${TARGET_DIR}/current/service.sh stop ${TARGET_DIR}
fi

if [ ! -z ${DM_DEBUG_JOB_FOLDER} ] && [ -d ${DM_DEBUG_JOB_FOLDER} ]; then
	echo "Removing job folder"
	rm -rf ${DM_DEBUG_JOB_FOLDER}
	mkdir -p ${DM_DEBUG_JOB_FOLDER}
fi

echo "Building server"
./server/scripts/build-standalone.sh -xtest

echo "Running server"
./server/scripts/run-standalone-local.sh ${TARGET_DIR}
tail -f ${TARGET_DIR}/logs/stdout.log