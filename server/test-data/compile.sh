

ANDROID_GCC=$ANDROID_NDK/toolchains/arm-linux-androideabi-4.8/prebuilt/darwin-x86_64/bin/arm-linux-androideabi-g++
ANDROID_AR=$ANDROID_NDK/toolchains/arm-linux-androideabi-4.8/prebuilt/darwin-x86_64/bin/arm-linux-androideabi-ar

IOS_GCC=/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++
IOS_AR=/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/ar

OSX_GCC=/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++
OSX_AR=/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/ar

EMCC=$DYNAMO_HOME/ext/bin/emsdk_portable/emscripten/1.35.0/em++
EMAR=$DYNAMO_HOME/ext/bin/emsdk_portable/emscripten/1.35.0/emar

WIN32_CL=cl.exe
WIN32_LIB=lib.exe

LINUX_GCC=/usr/bin/g++
LINUX_AR=/usr/bin/ar


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

function CompileWindows {
	local name=$1
	local src=$2
	local targetdir=$3

	archs=( "x86" "x86_64")
	for arch in "${archs[@]}"
	do
		local archname=$arch-win32
		local target=$targetdir/$archname/$name.lib
		
		RemoveTarget $target
		mkdir -p $(dirname $target)

		local OLDPATH=$PATH

		if [ "$arch" == "x86_64" ]; then
			export PATH="/c/Program Files (x86)/MSBuild/14.0/bin:/c/Program Files (x86)/Microsoft Visual Studio 14.0/Common7/IDE/:/c/Program Files (x86)/Microsoft Visual Studio 14.0/VC/BIN/x86_amd64:/c/Program Files (x86)/Microsoft Visual Studio 14.0/VC/BIN:/c/Program Files (x86)/Microsoft Visual Studio 14.0/Common7/Tools:/c/WINDOWS/Microsoft.NET/Framework/v4.0.30319:/c/Program Files (x86)/Microsoft Visual Studio 14.0/VC/VCPackages:/c/Program Files (x86)/HTML Help Workshop:/c/Program Files (x86)/Microsoft Visual Studio 14.0/Team Tools/Performance Tools:/c/Program Files (x86)/Windows Kits/8.1/bin/x86:/c/Program Files (x86)/Microsoft SDKs/Windows/v10.0A/bin/NETFX 4.6.1 Tools/:$PATH"
		else
			export PATH="/c/Program Files (x86)/MSBuild/14.0/bin:/c/Program Files (x86)/Microsoft Visual Studio 14.0/Common7/IDE/:/c/Program Files (x86)/Microsoft Visual Studio 14.0/VC/BIN:/c/Program Files (x86)/Microsoft Visual Studio 14.0/VC/BIN:/c/Program Files (x86)/Microsoft Visual Studio 14.0/Common7/Tools:/c/WINDOWS/Microsoft.NET/Framework/v4.0.30319:/c/Program Files (x86)/Microsoft Visual Studio 14.0/VC/VCPackages:/c/Program Files (x86)/HTML Help Workshop:/c/Program Files (x86)/Microsoft Visual Studio 14.0/Team Tools/Performance Tools:/c/Program Files (x86)/Windows Kits/8.1/bin/x86:/c/Program Files (x86)/Microsoft SDKs/Windows/v10.0A/bin/NETFX 4.6.1 Tools/:$PATH"
		fi

		$WIN32_CL -nologo -TP -O2 -Oy- -Z7 -MT -D__STDC_LIMIT_MACROS -DWINVER=0x0600 -D_WIN32_WINNT=0x0600 -DWIN32 -D_CRT_SECURE_NO_WARNINGS -wd4200 -W3 -EHsc $src -c -Fo$name-$archname.obj
		# For some reason, only the /OUT cannot be set using -OUT :/
		$WIN32_LIB -nologo $name-$archname.obj
		rm $name-$archname.obj
		mv -v $name-$archname.lib $target

		export PATH=$OLDPATH

		echo Wrote $target
	done
}

function CompileLinux {
	local name=$1
	local src=$2
	local targetdir=$3

	archs=( "x86" "x86_64")
	for arch in "${archs[@]}"
	do
		local archname=$arch-linux
		local flags=""
		if [ "$arch" == "x86" ]; then
			flags="-m32"
		fi

		local target=$targetdir/$archname/lib$name.a
		
		RemoveTarget $target
		mkdir -p $(dirname $target)

		$LINUX_GCC $flags -fomit-frame-pointer -fno-strict-aliasing -fno-exceptions $src -c -o /tmp/$name-$archname.o
		$LINUX_AR rcs $target /tmp/$name-$archname.o

		echo Wrote $target
	done
}

function Compile {
	local name=$1
	local src=$2
	local targetdir=$3

	if [ "$(uname)" == "Darwin" ]; then
		CompileOSX $name $src $targetdir
		CompileiOS $name $src $targetdir
		CompileAndroid $name $src $targetdir
		CompileHTML5 $name $src $targetdir
	fi
	if [ "$(uname)" == "MINGW32_NT-6.2" ]; then
		CompileWindows $name $src $targetdir
	fi
	if [ "$(uname)" == "Linux" ]; then
		CompileLinux $name $src $targetdir
	fi
}