#!/usr/bin/env bash

# Run with PLATFORMSDK_DIR=/Users/mathiaswesterdahl/work/extender/extender/platformsdk ./local_server_run.sh

if [ "$PLATFORMSDK_DIR" == "" ]; then
	PWD=`pwd`
	PLATFORMSDK_DIR=`find $PWD -name "platformsdk"`
	echo "Found platformsdk:" $PLATFORMSDK_DIR
fi

PLATFORMSDK_DIR=${PLATFORMSDK_DIR} PATH=${PLATFORMSDK_DIR}/XcodeDefault10.1.xctoolchain/usr/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin java -jar -Dspring.profiles.active=standalone,standalone-dev $PWD/server/build/docker/extender-0.1.0.jar
