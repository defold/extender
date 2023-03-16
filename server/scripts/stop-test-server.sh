#!/usr/bin/env bash

CONTAINER=extender

docker logs ${CONTAINER}
docker stop ${CONTAINER}

while [ "$(docker inspect -f '{{.State.Running}}' ${CONTAINER})" = "true" ]; do
    echo "Test server ${CONTAINER} is still running..."
    sleep 1
done

echo "Test server ${CONTAINER} exited"
