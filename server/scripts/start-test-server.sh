#!/usr/bin/env bash

set -e
set -x

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

docker build -t extender-base ${DIR}/../docker-base

${DIR}/../../gradlew buildDocker -x test

chmod -R a+xrw ${DIR}/../test-data || true

docker run -d --rm --name extender -p 9000:9000 -e SPRING_PROFILES_ACTIVE=dev -v ${DIR}/../test-data/sdk:/var/extender/sdk extender/extender
