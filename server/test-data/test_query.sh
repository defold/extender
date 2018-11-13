#!/usr/bin/env bash

JSONFILE=$1
if [ "$JSONFILE" == "" ]; then
	echo "You didn't specify a json file!"
	exit 1
fi

curl --progress-bar -H "Content-Type: application/json" -d @$JSONFILE http://localhost:9000/query -v
#curl --progress-bar -H "Content-Type: application/json" -d @server/test-data/query1/ne-cache-info.json http://localhost:9000/query -v
