#!/usr/bin/env bash

if [ "$APPLICATION" == "" ]; then
	APPLICATION="extender-test"
fi

echo "stop-test-server.sh: Stopping ${APPLICATION}:"

docker compose -p $APPLICATION down

echo "stop-test-server.sh: Test server ${APPLICATION} exited"
