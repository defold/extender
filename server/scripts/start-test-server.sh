#!/usr/bin/env bash

set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

${DIR}/../../gradlew buildDocker -x test

docker run -d --rm --name extender -p 9000:9000 -v ${DIR}/../test-data/sdk:/var/extender/sdk extender/extender
