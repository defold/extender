

ANDROID_GCC=$ANDROID_NDK/toolchains/arm-linux-androideabi-4.8/prebuilt/darwin-x86_64/bin/arm-linux-androideabi-g++
ANDROID_AR=$ANDROID_NDK/toolchains/arm-linux-androideabi-4.8/prebuilt/darwin-x86_64/bin/arm-linux-androideabi-ar

IOS_GCC=/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++
IOS_AR=/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/ar

OSX_GCC=/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++
OSX_AR=/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/ar

EMCC=$DYNAMO_HOME/ext/bin/emsdk_portable/emscripten/1.35.0/em++
EMAR=$DYNAMO_HOME/ext/bin/emsdk_portable/emscripten/1.35.0/emar


function RemoveTarget {
	local name=$1
	if [ -f $name ]; then
		rm $name
		echo Removed $name
	fi
}

function CompileAndroid {
	local name=$1
	local src=$2
	local targetdir=$3
	
	archs=("armv7")
	for arch in "${archs[@]}"
	do
		local archname=$arch-android
		local target=$targetdir/$archname/lib$name.a

		RemoveTarget $target
		mkdir -p $(dirname $target)
		
		$ANDROID_GCC -c -g -gdwarf-2 -fpic -ffunction-sections -fstack-protector -Wno-psabi -march=armv7-a -mfloat-abi=softfp -mfpu=vfp -fomit-frame-pointer -fno-strict-aliasing -finline-limit=64 -fno-exceptions -funwind-tables $src -c -o /tmp/$name-$archname.o
		$ANDROID_AR rcs $target /tmp/$name-$archname.o
		echo Wrote $target
	done
}


function CompileiOS {
	local name=$1
	local src=$2
	local targetdir=$3

	archs=("armv7" "arm64")
	for arch in "${archs[@]}"
	do
		local archname=$arch-ios
		local target=$targetdir/$archname/lib$name.a
		
		RemoveTarget $target
		mkdir -p $(dirname $target)

		$IOS_GCC -arch $arch -fomit-frame-pointer -fno-strict-aliasing -fno-exceptions $src -c -o /tmp/$name-$archname.o
		$IOS_AR rcs $target /tmp/$name-$archname.o

		echo Wrote $target
	done
}

function CompileOSX {
	local name=$1
	local src=$2
	local targetdir=$3

	archs=( "x86" "x86_64")
	for arch in "${archs[@]}"
	do
		local archname=$arch-osx
		if [ "$arch" == "x86" ]; then
			arch="i386"
		fi

		local target=$targetdir/$archname/lib$name.a
		
		RemoveTarget $target
		mkdir -p $(dirname $target)

		$OSX_GCC -arch $arch -fomit-frame-pointer -fno-strict-aliasing -fno-exceptions $src -c -o /tmp/$name-$archname.o
		$OSX_AR rcs $target /tmp/$name-$archname.o

		echo Wrote $target
	done
}

function CompileHTML5 {
	local name=$1
	local src=$2
	local targetdir=$3

	archs=("js")
	for arch in "${archs[@]}"
	do
		local archname=$arch-web
		local target=$targetdir/$archname/lib$name.a
		
		RemoveTarget $target
		mkdir -p $(dirname $target)

		$EMCC $src -c -o /tmp/$name-$archname.o
		$EMAR rcs $target /tmp/$name-$archname.o

		echo Wrote $target
	done
}

function Compile {
	local name=$1
	local src=$2
	local targetdir=$3

	CompileOSX $name $src $targetdir
	CompileiOS $name $src $targetdir
	CompileAndroid $name $src $targetdir
	CompileHTML5 $name $src $targetdir
}
