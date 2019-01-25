#!/bin/bash

set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

docker build -t extender-base ${DIR}/../docker-base

${DIR}/../../gradlew clean buildDocker --info $@
