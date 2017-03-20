/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++ -arch i386 -fomit-frame-pointer -fno-strict-aliasing -fno-exceptions test-data/alib/alib.cpp -c -o /tmp/alib-x86-osx.o
/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/ar rcs test-data/ext/lib/x86-osx/libalib.a /tmp/alib-x86-osx.o

/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++ -arch x86_64 -fomit-frame-pointer -fno-strict-aliasing -fno-exceptions test-data/alib/alib.cpp -c -o /tmp/alib-x86_64-osx.o
/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/ar rcs test-data/ext/lib/x86_64-osx/libalib.a /tmp/alib-x86_64-osx.o
