/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++ -arch armv7 -fomit-frame-pointer -fno-strict-aliasing -fno-exceptions test-data/alib/alib.cpp -c -o /tmp/alib-armv7-ios.o
/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/ar rcs test-data/ext/lib/armv7-ios/libalib.a /tmp/alib-armv7-ios.o

/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++ -arch arm64 -fomit-frame-pointer -fno-strict-aliasing -fno-exceptions test-data/alib/alib.cpp -c -o /tmp/alib-arm64-ios.o
/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/ar rcs test-data/ext/lib/arm64-ios/libalib.a /tmp/alib-arm64-ios.o
