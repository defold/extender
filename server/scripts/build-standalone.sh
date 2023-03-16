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

${DIR}/../../gradlew clean buildStandalone --info $@
