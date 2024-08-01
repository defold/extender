#!/usr/bin/env bash

if [ "$APPLICATION" == "" ]; then
	APPLICATION="extender-test"
fi

# echo "stop-test-server.sh: Output log result for ${CONTAINER}:"

# docker logs ${CONTAINER}

echo "stop-test-server.sh: Stopping ${APPLICATION}:"

docker compose -p $APPLICATION down
# docker stop ${CONTAINER}

# echo "stop-test-server.sh: Checking if ${CONTAINER} is still running:"

# while [ "$(docker inspect -f '{{.State.Running}}' ${CONTAINER})" = "true" ]; do
#     echo "Test server ${CONTAINER} is still running..."
#     sleep 1
# done

echo "stop-test-server.sh: Test server ${APPLICATION} exited"
