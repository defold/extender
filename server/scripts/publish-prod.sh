#!/bin/bash -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

source ${DIR}/shared/publi.sh

publish extender prod

SERVER=https://build.defold.com

echo "**********************************"
echo "Checking the server version:"
wget -q -O - $SERVER
echo ""
echo "**********************************"
