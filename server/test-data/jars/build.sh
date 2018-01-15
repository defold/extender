#!/usr/bin/env bash

# DEBUG
# Dump embedded resources:
# /Users/mathiaswesterdahl/android//android-sdk/build-tools/23.0.2/aapt d resources AndroidNativeExt/AndroidNativeExt.apk 

if [ "$ANDROID_HOME" == "" ]; then
    echo "No ANDROID_HOME environment variable set!"
    exit 1
fi

BUILD=./build
#AAPT=$ANDROID_HOME/build-tools/23.0.2/aapt

PACKAGE_NAME=com/defold/dummy

ANDROID_JAR=$DYNAMO_HOME/ext/share/java/android.jar

# local to this project
#RESOURCE_DIR1=../androidnative/res/android/res
#RESOURCE_DIR2=../androidnative/res/armv7-android/res


mkdir -p $BUILD

#ret = bld.exec_command('%s package --no-crunch -f --debug-mode --auto-add-overlay -M %s -I %s %s -F %s -m -J %s %s' % (aapt, manifest, android_jar, res_args, ap_, r_java_gen_dir, extra_packages_cmd))

#$AAPT package --no-crunch -f --debug-mode --auto-add-overlay -M AndroidManifest.xml -I $ANDROID_JAR -S $RESOURCE_DIR1 -S $RESOURCE_DIR2 -J $BUILD


# Dummy.jar
javac  -source 1.6 -target 1.6  -cp .  com/defold/dummy/Dummy.java
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

    javac  -source 1.6 -target 1.6  -cp . $VERYLARGE_JAVA
    rm $VERYLARGE_JAVA
    jar -cvf ../../ext/lib/armv7-android/$NAME.jar com

    popd
    rm -rf ./tmp
done

