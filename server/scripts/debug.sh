#!/bin/bash

CONTAINER=$(docker ps -f name=extender -q)

if [ -z "$CONTAINER" ]; then
    echo Container \"extender\" is not running. Please start it first \(by running run.sh\)!
else
    docker exec -uextender -it $CONTAINER /bin/bash
fi