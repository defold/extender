#!/bin/bash -e

if [ -f "/usr/local/extender/current/service.sh" ]; then
	echo "Stopping server"
	/usr/local/extender/current/service.sh stop /usr/local/extender
fi

echo "Building server"
./server/scripts/build-standalone.sh -xtest

echo "Running server"
./server/scripts/run-standalone-local.sh
tail -f /usr/local/extender/logs/stdout.log