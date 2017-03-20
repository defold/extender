/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++ -arch armv7 -fomit-frame-pointer -fno-strict-aliasing -fno-exceptions test-data/blib/blib.cpp -c -o /tmp/blib-armv7-ios.o
/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/ar rcs test-data/ext/lib/armv7-ios/libblib.a /tmp/blib-armv7-ios.o

/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++ -arch arm64 -fomit-frame-pointer -fno-strict-aliasing -fno-exceptions test-data/blib/blib.cpp -c -o /tmp/blib-arm64-ios.o
/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/ar rcs test-data/ext/lib/arm64-ios/libblib.a /tmp/blib-arm64-ios.o
