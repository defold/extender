#!/usr/bin/env bash

# This script is for regenerating LocalAar.aar, a minimal Android archive used to test
# support for .aar files shipped inside an extension (lib/android/*.aar).
# Usage: cd to this directory and run this script, and it should regenerate the file for you.
#
# The archive holds one of each part that the build consumes:
#   AndroidManifest.xml  - merged into the app manifest, its package becomes an aapt2 extra package
#   classes.jar          - com.defold.localaar.LocalAar
#   libs/InnerJar.jar    - com.defold.localaar.InnerJar
#   res/values/          - compiled and linked by aapt2, returned to the client in packages/
#   assets/              - returned to the client in assets/

set -e

BUILD=./build
TARGET=../ext/lib/android/LocalAar.aar

rm -rf $BUILD
mkdir -p $BUILD/classes $BUILD/libs $BUILD/aar

# classes.jar
javac -source 1.8 -target 1.8 -d $BUILD/classes com/defold/localaar/LocalAar.java
jar -cf $BUILD/aar/classes.jar -C $BUILD/classes .

# libs/InnerJar.jar
javac -source 1.8 -target 1.8 -d $BUILD/libs com/defold/localaar/InnerJar.java
mkdir -p $BUILD/aar/libs
jar -cf $BUILD/aar/libs/InnerJar.jar -C $BUILD/libs .

cp AndroidManifest.xml $BUILD/aar/
cp -r res $BUILD/aar/
cp -r assets $BUILD/aar/

# An .aar is a plain zip archive
rm -f $TARGET
(cd $BUILD/aar && zip -r -X ../../$TARGET .)

rm -rf $BUILD

echo "Wrote $TARGET"
