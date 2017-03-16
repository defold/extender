$ANDROID_NDK/toolchains/arm-linux-androideabi-4.8/prebuilt/darwin-x86_64/bin/arm-linux-androideabi-g++ -c -g -gdwarf-2 -fpic -ffunction-sections -fstack-protector -Wno-psabi -march=armv7-a -mfloat-abi=softfp -mfpu=vfp -fomit-frame-pointer -fno-strict-aliasing -finline-limit=64 -fno-exceptions -funwind-tables test-data/alib/alib.cpp -c -o /tmp/alib.o
$ANDROID_NDK/toolchains/arm-linux-androideabi-4.8/prebuilt/darwin-x86_64/bin/arm-linux-androideabi-ar rcs test-data/ext/lib/armv7-android/libalib.a /tmp/alib.o



