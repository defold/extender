#!/bin/bash

EXTENDER_DIR=/usr/local/extender
EXTENDER_INSTALL_DIR=${EXTENDER_DIR}/current

# The SDK path is used by Defold SDK build.yml
export PLATFORMSDK_DIR=${EXTENDER_DIR}/platformsdk

# We need access to the toolchain binary path from within the application
export PATH=${PLATFORMSDK_DIR}/XcodeDefault10.1.xctoolchain/usr/bin:/usr/local/bin:${PATH}

echo [run] java -Dspring.profiles.active=standalone-dev -jar ${EXTENDER_INSTALL_DIR}/extender.jar
java -Dspring.profiles.active=standalone-dev -jar ${EXTENDER_INSTALL_DIR}/extender.jar
