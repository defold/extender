#!/bin/bash

set -e

if [ "" != "$DYNAMO_HOME" ]; then
	echo "temporarily disabling DYNAMO_HOME=$DYNAMO_HOME"
	unset DYNAMO_HOME
fi

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

if [ "${DM_PACKAGES_URL}" != "" ]; then
	ENV='--build-arg DM_PACKAGES_URL'
fi

if [ "${ENV}" != "" ]; then
	echo "Using ENV: ${ENV}"
fi

if [ "${DM_EXTENDER_USERNAME}" != "" ]; then
	echo "Found DM_EXTENDER_USERNAME, removing from test env"
	unset DM_EXTENDER_USERNAME
fi

if [ "${DM_EXTENDER_PASSWORD}" != "" ]; then
	echo "Found DM_EXTENDER_PASSWORD, removing from test env"
	unset DM_EXTENDER_PASSWORD
fi

docker build --platform linux/amd64 -t extender-base ${ENV} ${DIR}/../docker-base

${DIR}/../../gradlew clean buildDocker --info $@
