
ANDROID_GCC=$ANDROID_NDK/toolchains/arm-linux-androideabi-4.8/prebuilt/darwin-x86_64/bin/arm-linux-androideabi-g++
ANDROID_AR=$ANDROID_NDK/toolchains/arm-linux-androideabi-4.8/prebuilt/darwin-x86_64/bin/arm-linux-androideabi-ar

IOS_GCC=/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++
IOS_AR=/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/ar

OSX_GCC=/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++
OSX_AR=/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/ar


function RemoveTarget {
	local name=$1
	if [ -f $name ]; then
		rm $name
		echo Removed $name
	fi
}

function CompileAndroid {
	local name=$1
	local extension=$2
	
	archs=("armv7")
	for arch in "${archs[@]}"
	do
		local archname=$arch-android
		local target=test-data/$extension/lib/$archname/lib$name.a

		RemoveTarget $target
		
		$ANDROID_GCC -c -g -gdwarf-2 -fpic -ffunction-sections -fstack-protector -Wno-psabi -march=armv7-a -mfloat-abi=softfp -mfpu=vfp -fomit-frame-pointer -fno-strict-aliasing -finline-limit=64 -fno-exceptions -funwind-tables test-data/$name/$name.cpp -c -o /tmp/$name-armv7-android.o
		$ANDROID_AR rcs $target /tmp/$name-armv7-android.o
		echo Wrote $target
	done
}


function CompileiOS {
	local name=$1
	local extension=$2

	archs=("armv7" "arm64")
	for arch in "${archs[@]}"
	do
		local archname=$arch-ios
		local target=test-data/$extension/lib/$archname/lib$name.a
		
		RemoveTarget $target

		$IOS_GCC -arch $arch -fomit-frame-pointer -fno-strict-aliasing -fno-exceptions test-data/$name/$name.cpp -c -o /tmp/$name-$archname.o
		$IOS_AR rcs $target /tmp/$name-$archname.o

		echo Wrote $target
	done
}

function CompileOSX {
	local name=$1
	local extension=$2

	archs=( "x86" "x86_64")
	for arch in "${archs[@]}"
	do
		local archname=$arch-osx
		if [ "$arch" == "x86" ]; then
			arch="i386"
		fi

		local target=test-data/$extension/lib/$archname/lib$name.a
		
		RemoveTarget $target

		$OSX_GCC -arch $arch -fomit-frame-pointer -fno-strict-aliasing -fno-exceptions test-data/$name/$name.cpp -c -o /tmp/$name-$archname.o
		$OSX_AR rcs $target /tmp/$name-$archname.o

		echo Wrote $target
	done
}


function CompileLibsToExtension {
	local name=$1
	local extension=$2

	echo $extension - $name
	CompileAndroid $name $extension
	CompileiOS $name $extension
	CompileOSX $name $extension
}




CompileLibsToExtension alib ext

CompileLibsToExtension alib ext2
CompileLibsToExtension blib ext2



