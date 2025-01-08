#!/usr/bin/env bash

set -e
set -x

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

RUN_ENV=""
if [ "$COMPOSE_PROFILE" == "" ]; then
	COMPOSE_PROFILE="test"
fi

if [ "$APPLICATION" == "" ]; then
	APPLICATION="extender-test"
fi

if [ "$PORT" == "" ]; then
  PORT=9000
fi

echo "Using RUN_ENV: ${RUN_ENV}"
echo "Using compose profile: ${COMPOSE_PROFILE}"
echo "Start application: ${APPLICATION}"
echo "Using PORT: ${PORT}"

URL=http://localhost:${PORT}

function check_server() {
	if curl -s --head  --request GET ${URL} | grep "200 OK" > /dev/null; then
		echo "ERROR: ${URL} is already occupied!"
		exit 1
	fi
}

# fail early
check_server

# For CI to be able to work with the test files
if [ "$GITHUB_ACTION" != "" ]; then
	chmod -R a+xrw ${DIR}/../test-data || true
fi

docker compose -p $APPLICATION -f ${DIR}/../docker/docker-compose.yml --profile $COMPOSE_PROFILE up -d

# Retry configuration
max_retries=10
retry_interval=15

check_containers_health() {
  all_healthy=true

  for container in $(docker ps -q); do
    health_field=$(docker inspect --format='{{.State.Health}}' "$container")
    if [ "$health_field" == "<nil>" ]; then
      echo "Container has no health status. Skipped."
      continue
    fi
    name=$(docker inspect --format='{{.Name}}' "$container" | sed 's/\///')
    app_name=$(docker inspect "$container" | jq -r '.[0].Config.Labels["com.docker.compose.project"]')
    health_status=$(docker inspect --format='{{.State.Health.Status}}' "$container")

    if [ "$app_name" != "$APPLICATION" ]; then
      echo "Skip health check of unrelated container."
    fi

    # If health status is empty, container doesn't have a health check defined
    if [ -z "$health_status" ]; then
      health_status="no health check"
      continue
    fi

    echo "$name: $health_status"

    # Check if the container is not healthy
    if [ "$health_status" != "healthy" ]; then
      all_healthy=false
    fi
  done

  # Return whether all containers are healthy
  $all_healthy && return 0 || return 1
}

# Main loop to retry until all containers are healthy or retries run out
for (( i=1; i<=$max_retries; i++ )); do
  echo "Attempt $i of $max_retries: Checking container health..."

  if check_containers_health; then
    echo "All containers are healthy!"
    exit 0
  else
    echo "Some containers are not healthy yet. Retrying in $retry_interval seconds..."
    sleep $retry_interval
  fi
done

# If we reach this point, some containers did not become healthy within the retry limit
echo "Some containers did not become healthy after $max_retries retries."
exit 1
