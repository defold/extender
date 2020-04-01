#!/bin/bash

LOCAL_ENV=""
if [ "$DM_DEBUG_DISABLE_PROGUARD" != "" ]; then
	LOCAL_ENV="$LOCAL_ENV -e DM_DEBUG_DISABLE_PROGUARD=${DM_DEBUG_DISABLE_PROGUARD}"
fi
if [ "$DM_DEBUG_COMMANDS" != "" ]; then
	LOCAL_ENV="$LOCAL_ENV -e DM_DEBUG_COMMANDS=${DM_DEBUG_COMMANDS}"
fi
if [ "$DM_DEBUG_JOB_FOLDER" != "" ]; then
	LOCAL_ENV="$LOCAL_ENV -e DM_DEBUG_JOB_FOLDER=${DM_DEBUG_JOB_FOLDER}"
fi
if [ "$DM_DEBUG_KEEP_JOB_FOLDER" != "" ]; then
    LOCAL_ENV="$LOCAL_ENV -e DM_DEBUG_KEEP_JOB_FOLDER=${DM_DEBUG_KEEP_JOB_FOLDER}"
fi
if [ "$DM_DEBUG_JOB_UPLOAD" != "" ]; then
	LOCAL_ENV="$LOCAL_ENV -e DM_DEBUG_JOB_UPLOAD=${DM_DEBUG_JOB_UPLOAD}"
fi

echo "Using local env: $LOCAL_ENV"

if [ -z "$DYNAMO_HOME" ]; then
    docker run --rm --name extender -p 9000:9000 -e SPRING_PROFILES_ACTIVE=dev $LOCAL_ENV extender/extender;
else
    docker run --rm --name extender -p 9000:9000 -e SPRING_PROFILES_ACTIVE=dev $LOCAL_ENV -v ${DYNAMO_HOME}:/dynamo_home -e DYNAMO_HOME=/dynamo_home extender/extender;
fi
