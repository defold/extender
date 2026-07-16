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

# Local developer speedups, both off in CI (GITHUB_ACTION is set by Actions).
#   EXTENDER_DEV_CACHE=1  keep the downloaded Defold SDKs in a named volume between runs
#   EXTENDER_KEEP_STACK=1 leave the containers running afterwards (see stop-test-server.sh)
if [ "$GITHUB_ACTION" != "" ]; then
	EXTENDER_DEV_CACHE=0
fi
if [ "$EXTENDER_DEV_CACHE" == "" ]; then
	EXTENDER_DEV_CACHE=1
fi
if [ "$EXTENDER_KEEP_STACK" == "" ]; then
	EXTENDER_KEEP_STACK=0
fi

echo "Using RUN_ENV: ${RUN_ENV}"
echo "Using compose profile: ${COMPOSE_PROFILE}"
echo "Start application: ${APPLICATION}"
echo "Using PORT: ${PORT}"

URL=http://localhost:${PORT}

COMPOSE_FILES=(-f "${DIR}/../docker/docker-compose.yml")
if [ "$EXTENDER_DEV_CACHE" == "1" ]; then
	# The volume is external so that the 'extender-test' and 'extender-test-auth'
	# projects share one SDK cache instead of getting a project-prefixed copy each.
	docker volume create extender-sdk-cache > /dev/null
	COMPOSE_FILES+=(-f "${DIR}/../docker/docker-compose.dev-cache.yml")
fi

# COMPOSE_PROFILE may name several profiles, e.g. "test-linux test-web"
PROFILE_ARGS=()
for profile in $COMPOSE_PROFILE; do
	PROFILE_ARGS+=(--profile "$profile")
done

COMPOSE=(docker compose -p "$APPLICATION" "${COMPOSE_FILES[@]}" "${PROFILE_ARGS[@]}")

function check_server() {
	if curl -s --head  --request GET ${URL} | grep "200 OK" > /dev/null; then
		echo "ERROR: ${URL} is already occupied!"
		exit 1
	fi
}

# A container keeps running whatever extender.jar it loaded at startup, so a stack we
# are reusing must be recreated once the jar is rebuilt - otherwise the tests would
# silently exercise stale code.
function stack_is_current() {
	local jar="${DIR}/../app/extender.jar"
	[ -f "$jar" ] || return 0

	local ids
	ids=$("${COMPOSE[@]}" ps -q 2>/dev/null)
	[ -n "$ids" ] || return 1

	local jar_mtime
	jar_mtime=$(stat -f %m "$jar" 2>/dev/null || stat -c %Y "$jar")

	local started started_epoch
	for id in $ids; do
		# StartedAt is UTC (e.g. 2026-07-14T22:40:26.4Z); parse it as UTC on both BSD and GNU date.
		started=$(docker inspect -f '{{.State.StartedAt}}' "$id")
		started_epoch=$(date -u -j -f "%Y-%m-%dT%H:%M:%S" "${started:0:19}" +%s 2>/dev/null \
			|| date -u -d "$started" +%s)
		if [ "$jar_mtime" -gt "$started_epoch" ]; then
			return 1
		fi
	done
	return 0
}

# fail early - but a stack we intend to reuse is expected to answer on ${URL}
if [ "$EXTENDER_KEEP_STACK" != "1" ]; then
	check_server
fi

# For CI to be able to work with the test files
if [ "$GITHUB_ACTION" != "" ]; then
	chmod -R a+xrw ${DIR}/../test-data || true
fi

UP_ARGS=(up -d --wait --wait-timeout 300)
if [ "$EXTENDER_KEEP_STACK" == "1" ] && ! stack_is_current; then
	echo "extender.jar is newer than the running containers - recreating them"
	UP_ARGS+=(--force-recreate)
fi

"${COMPOSE[@]}" "${UP_ARGS[@]}"

for service in $("${COMPOSE[@]}" ps --services); do
	# base-env creates /var/extender and /var/extender/cache but not /var/extender/sdk,
	# so with EXTENDER_DEV_CACHE the volume mounts root-owned and the extender user
	# (uid 2222) cannot write to it. Harmless when there is no volume.
	"${COMPOSE[@]}" exec -T -u root "$service" mkdir -p /var/extender/sdk
	"${COMPOSE[@]}" exec -T -u root "$service" chown extender:extender /var/extender/sdk

	# "a" is the version-controlled test sdk. It survives in the cache volume, and both
	# evictCache() and destroy() in DefoldSdkService deliberately exempt it.
	if ! "${COMPOSE[@]}" exec -T "$service" test -d /var/extender/sdk/a; then
		echo "Copy test sdk to $service"
		"${COMPOSE[@]}" cp "${DIR}/../test-data/sdk/a" "$service:/var/extender/sdk"
		"${COMPOSE[@]}" exec -T -u root "$service" chown -R extender:extender /var/extender/sdk/a
	fi
done
