#!/usr/bin/env bash

set -e
set -x

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

PORT=9000
RUN_ENV=""
if [ "$COMPOSE_PROFILE" == "" ]; then
	COMPOSE_PROFILE="test"
fi

if [ "$APPLICATION" == "" ]; then
	APPLICATION="extender-test"
fi


echo "Using RUN_ENV: ${RUN_ENV}"
echo "Using compose profile: ${COMPOSE_PROFILE}"
echo "Start application: ${APPLICATION}"
echo "Using PORT: ${PORT}"

URL=http://localhost:${PORT}

function check_server() {
	if curl -s --head  --request GET ${URL} | grep "200 OK" > /dev/null; then
		echo "ERROR: ${URL} is already occupied!"
		exit 1
	fi
}

# fail early
check_server

# For CI to be able to work with the test files
if [ "$GITHUB_ACTION" != "" ]; then
	chmod -R a+xrw ${DIR}/../test-data || true
fi

docker compose -p $APPLICATION -f ${DIR}/../docker/docker-compose.yml --profile $COMPOSE_PROFILE up -d
