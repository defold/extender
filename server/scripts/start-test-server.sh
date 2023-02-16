#!/usr/bin/env bash

set -e
set -x

CONTAINER=extender

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

BUILD_ENV=""
RUN_ENV=""
if [ "${DM_PACKAGES_URL}" != "" ]; then
	RUN_ENV="$RUN_ENV -e DM_PACKAGES_URL=${DM_PACKAGES_URL}"
	BUILD_ENV="$BUILD_ENV --build-arg DM_PACKAGES_URL"
fi
if [ "${EXTENDER_AUTHENTICATION_PLATFORMS}" != "" ]; then
	RUN_ENV="$RUN_ENV -e extender.authentication.platforms=${EXTENDER_AUTHENTICATION_PLATFORMS}"
fi
if [ "${EXTENDER_AUTHENTICATION_USERS}" != "" ]; then
	RUN_ENV="$RUN_ENV -e extender.authentication.users=${EXTENDER_AUTHENTICATION_USERS}"
fi

echo "Using BUILD_ENV: ${BUILD_ENV}"
echo "Using RUN_ENV: ${RUN_ENV}"

docker build ${BUILD_ENV} -t extender-base ${DIR}/../docker-base

${DIR}/../../gradlew buildDocker -x test

# For CI to be able to work with the test files
if [ "$GITHUB_ACTION" != "" ]; then
	chmod -R a+xrw ${DIR}/../test-data || true
fi

docker run -d --rm --name ${CONTAINER} -p 9000:9000 ${RUN_ENV} -v ${DIR}/../test-data/sdk:/var/extender/sdk extender/extender
