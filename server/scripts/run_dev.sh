#! /usr/bin/env bash

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

DOCKER_ARGS="-e SPRING_PROFILES_ACTIVE=dev-remote-builder" $SCRIPT_DIR/run.sh
