#!/usr/bin/env bash

SHA1=$1

if [ -e $SHA1 ]; then
	echo "Usage: ./debug_defoldsdk.sh <sha1>"
	exit 1
fi



wget http://d.defold.com/archive/$SHA1/engine/defoldsdk.zip

mkdir -p debugsdk/$SHA1
unzip defoldsdk.zip -d debugsdk/$SHA1

export DYNAMO_HOME=$(cd debugsdk/$SHA1/defoldsdk && pwd)

echo export DYNAMO_HOME=$DYNAMO_HOME
