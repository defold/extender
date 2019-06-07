#!/bin/bash -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

source ${DIR}/shared/publi.sh

publish extender stage

SERVER=https://build-stage.defold.com

echo "**********************************"
echo "Checking the server version:"
wget -q -O - $SERVER
echo ""
echo "**********************************"
