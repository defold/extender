#!/usr/bin/env bash

# This script is for regenerating Dummy.jar, and VeryLarge1 and VeryLarge2.jar
# Usage: cd to this directory and run this script, and it should regenerate those files for you.

if [ "$ANDROID_HOME" == "" ]; then
    echo "No ANDROID_HOME environment variable set!"
    exit 1
fi

BUILD=./build

PACKAGE_NAME=com/defold/dummy

ANDROID_SDK_VERSION=33
ANDROID_JAR=${ANDROID_HOME}/platforms/android-${ANDROID_SDK_VERSION}/android.jar

mkdir -p $BUILD

# Dummy.jar
javac  -source 1.8 -target 1.8  -cp .  com/defold/dummy/Dummy.java
jar -cvf ../ext/lib/armv7-android/Dummy.jar com

# VeryLarge.jar

array=( VeryLarge1 VeryLarge2 )
for NAME in "${array[@]}"
do
    echo "Generating $NAME.java..."
    mkdir -p tmp/com/defold/multidex
    pushd tmp

    VERYLARGE_JAVA=com/defold/multidex/$NAME.java

    echo "package com.defold.multidex;" > $VERYLARGE_JAVA
    echo "public class ${NAME} {" >> $VERYLARGE_JAVA

    COUNTER=0
    while [  $COUNTER -lt 40000 ]; do
        #echo The counter is $COUNTER
        echo "static public int Function${COUNTER}() { return ${COUNTER}; }" >> $VERYLARGE_JAVA
        let COUNTER=COUNTER+1
    done

    echo "}" >> $VERYLARGE_JAVA

    echo "done."

    javac  -source 1.8 -target 1.8  -cp . $VERYLARGE_JAVA
    rm $VERYLARGE_JAVA
    jar -cvf ../../ext/lib/armv7-android/$NAME.jar com

    popd
    rm -rf ./tmp
done

