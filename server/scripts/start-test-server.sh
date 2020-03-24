#!/usr/bin/env bash

set -e
set -x

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

if [ "${DM_PACKAGES_URL}" != "" ]; then
	ENV='--build-arg DM_PACKAGES_URL'
fi

if [ "${ENV}" != "" ]; then
	echo "Using ENV: ${ENV}"
fi

docker build ${ENV} -t extender-base ${DIR}/../docker-base

${DIR}/../../gradlew buildDocker -x test

# For CI to be able to work with the test files
if [ "$GITHUB_ACTION" != "" ]; then
	chmod -R a+xrw ${DIR}/../test-data || true
fi

docker run -d --rm --name extender -p 9000:9000 -e DM_PACKAGES_URL=${DM_PACKAGES_URL} -v ${DIR}/../test-data/sdk:/var/extender/sdk extender/extender
