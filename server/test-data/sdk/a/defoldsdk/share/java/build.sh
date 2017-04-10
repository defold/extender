# DEBUG
# Dump embedded resources:
# /Users/mathiaswesterdahl/android//android-sdk/build-tools/23.0.2/aapt d resources AndroidNativeExt/AndroidNativeExt.apk 

BUILD=./build
AAPT=~/android/android-sdk/build-tools/23.0.2/aapt

javac  -source 1.6 -target 1.6  -cp . com/defoldtest/engine/Engine.java
jar cvf Engine.jar com
