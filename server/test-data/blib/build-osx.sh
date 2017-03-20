/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++ -arch i386 -fomit-frame-pointer -fno-strict-aliasing -fno-exceptions test-data/blib/blib.cpp -c -o /tmp/blib-x86-osx.o
/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/ar rcs test-data/ext/lib/x86-osx/libblib.a /tmp/blib-x86-osx.o

/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++ -arch x86_64 -fomit-frame-pointer -fno-strict-aliasing -fno-exceptions test-data/blib/blib.cpp -c -o /tmp/blib-x86_64-osx.o
/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/ar rcs test-data/ext/lib/x86_64-osx/libblib.a /tmp/blib-x86_64-osx.o
