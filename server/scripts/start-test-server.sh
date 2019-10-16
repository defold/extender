#!/usr/bin/env bash

set -e
set -x

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

if [ "${S3_URL}" != "" ]; then
	ENV='--build-arg S3_URL'
fi

if [ "${ENV}" != "" ]; then
	echo "Using ENV: ${ENV}"
fi

docker build ${ENV} -t extender-base ${DIR}/../docker-base

${DIR}/../../gradlew buildDocker -x test

docker run -d --rm --name extender -p 9000:9000 -e S3_URL=${S3_URL} -v ${DIR}/../test-data/sdk:/var/extender/sdk extender/extender
