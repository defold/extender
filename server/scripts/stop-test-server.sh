#!/usr/bin/env bash

if [ "$APPLICATION" == "" ]; then
	APPLICATION="extender-test"
fi

if [ "$EXTENDER_KEEP_STACK" == "1" ]; then
	echo "stop-test-server.sh: Leaving ${APPLICATION} running (EXTENDER_KEEP_STACK=1)"
	exit 0
fi

echo "stop-test-server.sh: Stopping ${APPLICATION}:"

docker compose -p $APPLICATION down

echo "stop-test-server.sh: Test server ${APPLICATION} exited"
