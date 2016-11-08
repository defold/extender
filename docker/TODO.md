* [ ] Investigate libarclite_iphoneos.a

  http://blogs.adobe.com/rajorshi/2012/09/06/using-arc-in-native-extensions/

  *"Setting the deployment target to 4.x also tells the linker to link in a static library which will satisfy the symbol dependencies introduced by the compiler generated code. This library is called libarclite_iphoneos.a. If you do not set the deployment target to 4.x, this library will not be linked in and the loader will try to resolve these dependencies at runtime."*

  Added by default, with -force_load, when linking on OSX but not on Linux
  
* [ ] Investigate libclang_rt.ios.a

  Automatically included when linking on OSX but not on Linux




sdfok
dsokf

        isjdfisdf
        idsjf






* Verify linker flags

        When running on OSX


                    clang++ x.o -framework ... -arch armv7 -stdlib=libstdc++ -fobjc-link-runtime -isysroot ... -dead_strip -miphoneos-version-min=6.0 -v

                    /.../ld" -demangle -dynamic -arch armv7 -dead_strip -iphoneos_version_min 6.0.0 -syslibroot ... -o ...  x.o -framework ... -force_load /.../usr/lib/arc/libarclite_iphoneos.a -framework Foundation -lobjc -lstdc++ -lSystem /.../usr/bin/../lib/clang/8.0.0/lib/darwin/libclang_rt.ios.a
