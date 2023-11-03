#! /usr/bin/env bash

(cd client && ../gradlew build && cp -v ./build/libs/extender-client-0.0.5.jar $DYNAMO_HOME/../../com.dynamo.cr/com.dynamo.cr.common/ext/extender-client-0.0.5.jar)
