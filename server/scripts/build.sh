#!/bin/bash

set -e

if [ "" != "$DYNAMO_HOME" ]; then
	echo "temporarily disabling DYNAMO_HOME=$DYNAMO_HOME"
	unset DYNAMO_HOME
fi

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

docker build -t extender-base ${DIR}/../docker-base

${DIR}/../../gradlew clean buildDocker --info $@
