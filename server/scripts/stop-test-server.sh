#!/usr/bin/env bash

APPLICATION=extender

# echo "stop-test-server.sh: Output log result for ${CONTAINER}:"

# docker logs ${CONTAINER}

echo "stop-test-server.sh: Stopping ${CONTAINER}:"

docker compose -p $APPLICATION down
# docker stop ${CONTAINER}

# echo "stop-test-server.sh: Checking if ${CONTAINER} is still running:"

# while [ "$(docker inspect -f '{{.State.Running}}' ${CONTAINER})" = "true" ]; do
#     echo "Test server ${CONTAINER} is still running..."
#     sleep 1
# done

echo "stop-test-server.sh: Test server ${CONTAINER} exited"
