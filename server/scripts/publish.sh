#!/bin/bash

set -e

SRC_REPOSITORY="extender/extender:latest"
VERSION=$(date "+%Y%m%d_%H%M")
DEST_REPOSITORY="378120250811.dkr.ecr.eu-west-1.amazonaws.com/extender:$VERSION"
CLUSTER="prod-eu-west1"
SERVICE_NAME="extender"
TASK_FAMILY="extender"

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# Tag image to match destination repository
docker tag "$SRC_REPOSITORY" "$DEST_REPOSITORY"

# Login Docker to AWS ECR
eval $(aws ecr get-login --no-include-email)

# Push image
docker push "$DEST_REPOSITORY"

# Deployment --------------------------------------------------------

# Create a new task definition for this build
sed -e "s;%IMAGE%;${DEST_REPOSITORY};g" ${DIR}/task-definition-template.json > ${DIR}/task-definition-template-${VERSION}.json
aws ecs register-task-definition --family ${TASK_FAMILY} --cli-input-json file://${DIR}/task-definition-template-${VERSION}.json
rm ${DIR}/task-definition-template-${VERSION}.json

# Update the service with the latest revision of the task
aws ecs update-service --cluster ${CLUSTER} --service ${SERVICE_NAME} --task-definition ${TASK_FAMILY}
