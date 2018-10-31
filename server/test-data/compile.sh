

eval ANDROID_NDK=$ANDROID_NDK
ANDROID_GCC=${ANDROID_NDK}/toolchains/arm-linux-androideabi-4.8/prebuilt/darwin-x86_64/bin/arm-linux-androideabi-g++
ANDROID_AR=${ANDROID_NDK}/toolchains/arm-linux-androideabi-4.8/prebuilt/darwin-x86_64/bin/arm-linux-androideabi-ar
ANDROID_NDK_API_VERSION='14'
ANDROID_GCC_VERSION='4.8'
ANDROID_SYS_ROOT=${ANDROID_NDK}/platforms/android-${ANDROID_NDK_API_VERSION}/arch-arm
ANDROID_INCLUDE_STL=${ANDROID_NDK}/sources/cxx-stl/gnu-libstdc++/${ANDROID_GCC_VERSION}/include
ANDROID_INCLUDE_ARCH=${ANDROID_NDK}/sources/cxx-stl/gnu-libstdc++/${ANDROID_GCC_VERSION}/libs/armeabi-v7a/include

IOS_GCC=${DYNAMO_HOME}/ext/SDKs/XcodeDefault.xctoolchain/usr/bin/clang++
IOS_AR=${DYNAMO_HOME}/ext/SDKs/XcodeDefault.xctoolchain/usr/bin/ar
IOS_MIN_VERSION=6.0
IOS_SYS_ROOT=${DYNAMO_HOME}/ext/SDKs/iPhoneOS11.2.sdk

OSX_GCC=${DYNAMO_HOME}/ext/SDKs/XcodeDefault.xctoolchain/usr/bin/clang++
OSX_AR=${DYNAMO_HOME}/ext/SDKs/XcodeDefault.xctoolchain/usr/bin/ar
OSX_MIN_VERSION=10.7
OSX_SYS_ROOT=${DYNAMO_HOME}/ext/SDKs/MacOSX10.13.sdk

EMCC=$DYNAMO_HOME/ext/bin/emsdk_portable/emscripten/1.38.12/em++
EMAR=$DYNAMO_HOME/ext/bin/emsdk_portable/emscripten/1.38.12/emar

WIN32_CL=cl.exe
WIN32_LIB=lib.exe

if [ "$(uname)" == "Darwin" ]; then
	LINUX_GCC=$(brew --prefix llvm)/bin/clang++
	LINUX_AR=$(brew --prefix llvm)/bin/llvm-ar
	WIN32_GCC=$(brew --prefix llvm)/bin/clang++
	WIN32_AR=$(brew --prefix llvm)/bin/llvm-ar
else
	LINUX_GCC=/usr/bin/g++
	LINUX_AR=/usr/bin/ar
fi

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
		echo "Compiling ${name} for ${archname}"

		RemoveTarget $target
		mkdir -p $(dirname $target)

		$ANDROID_GCC -c -g -gdwarf-2 -fpic -ffunction-sections -fstack-protector -Wno-psabi -march=armv7-a -mfloat-abi=softfp -mfpu=vfp -fno-strict-aliasing -finline-limit=64 -fno-exceptions -funwind-tables -I${ANDROID_INCLUDE_STL} -I${ANDROID_INCLUDE_ARCH} --sysroot=${ANDROID_SYS_ROOT} $src -c -o /tmp/$name-$archname.o
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
		echo "Compiling ${name} for ${archname}"

		RemoveTarget $target
		mkdir -p $(dirname $target)

		$IOS_GCC -arch $arch -fno-strict-aliasing -fno-exceptions -miphoneos-version-min=${IOS_MIN_VERSION} -isysroot ${IOS_SYS_ROOT} $src -c -o /tmp/$name-$archname.o
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
		echo "Compiling ${name} for ${archname}"

		RemoveTarget $target
		mkdir -p $(dirname $target)

		$OSX_GCC -arch $arch -fomit-frame-pointer -fno-strict-aliasing -fno-exceptions -mmacosx-version-min=${OSX_MIN_VERSION} -isysroot ${OSX_SYS_ROOT} $src -c -o /tmp/$name-$archname.o
		$OSX_AR rcs $target /tmp/$name-$archname.o

		echo Wrote $target
	done
}

function CompileHTML5 {
	local name=$1
	local src=$2
	local targetdir=$3

	archs=("js" "wasm")
	for arch in "${archs[@]}"
	do
		local archname=$arch-web
		local target=$targetdir/$archname/lib$name.a
		echo "Compiling ${name} for ${archname}"

		RemoveTarget $target
		mkdir -p $(dirname $target)

		$EMCC $src -c -o /tmp/$name-$archname.o
		$EMAR rcs $target /tmp/$name-$archname.o

		echo Wrote $target
	done
}

function CompileWindowsOnDarwin {
	local name=$1
	local src=$2
	local targetdir=$3

	archs=( "x86" "x86_64")
	for arch in "${archs[@]}"
	do
		local archname=$arch-win32
		local target=$targetdir/$archname/$name.lib
		echo "Compiling ${name} for ${archname}"

		RemoveTarget $target
		mkdir -p $(dirname $target)

		local INCLUDES="-I${DYNAMO_HOME}/ext/SDKs/Win32/MicrosoftVisualStudio14.0/VC/include -I${DYNAMO_HOME}/ext/SDKs/Win32/MicrosoftVisualStudio14.0/VC/atlmfc/include -I${DYNAMO_HOME}/ext/SDKs/Win32/WindowsKits/10/Include/10.0.10240.0/ucrt -I${DYNAMO_HOME}/ext/SDKs/Win32/WindowsKits/8.1/Include/winrt -I${DYNAMO_HOME}/ext/SDKs/Win32/WindowsKits/8.1/Include/um -I${DYNAMO_HOME}/ext/SDKs/Win32/WindowsKits/8.1/Include/shared"
		local DEFINES="-DDM_PLATFORM_WINDOWS -D_CRT_SECURE_NO_WARNINGS -D__STDC_LIMIT_MACROS -DWINVER=0x0600 -DWIN32"
		local FLAGS="-O2 -Wall -Werror=format -fvisibility=hidden -g" # -codeview
		local ARCH_FLAGS=""
		local LIB_PATHS=""

		if [ "$arch" == "x86_64" ]; then
			ARCH_FLAGS="-target x86_64-pc-win32-msvc -m64"
			#LIB_PATHS="-L${DYNAMO_HOME}/ext/SDKs/Win32/lib/x86_64-win32 -L${DYNAMO_HOME}/ext/SDKs/Win32/ext/lib/x86_64-win32 -L${DYNAMO_HOME}/ext/SDKs/Win32/MicrosoftVisualStudio14.0/VC/lib/amd64 -L${DYNAMO_HOME}/ext/SDKs/Win32/MicrosoftVisualStudio14.0/VC/atlmfc/lib/amd64 -L${DYNAMO_HOME}/ext/SDKs/Win32/WindowsKits/10/Lib/10.0.10240.0/ucrt/x64 -L${DYNAMO_HOME}/ext/SDKs/Win32/WindowsKits/8.1/Lib/winv6.3/um/x64"
		else
			ARCH_FLAGS="-target i386-pc-win32-msvc -m32"
			#LIB_PATHS="-L${DYNAMO_HOME}/ext/SDKs/Win32/lib/win32 -L${DYNAMO_HOME}/ext/SDKs/Win32/ext/lib/win32 -L${DYNAMO_HOME}/ext/SDKs/Win32/MicrosoftVisualStudio14.0/VC/lib/ -L${DYNAMO_HOME}/ext/SDKs/Win32/MicrosoftVisualStudio14.0/VC/atlmfc/lib -L${DYNAMO_HOME}/ext/SDKs/Win32/WindowsKits/10/Lib/10.0.10240.0/ucrt/x86 -L${DYNAMO_HOME}/ext/SDKs/Win32/WindowsKits/8.1/Lib/winv6.3/um/x86"
		fi

		echo $WIN32_GCC $ARCH_FLAGS $FLAGS $LIBPATHS $INCLUDES $LIB_PATHS $src -c -o /tmp/$name-$archname.o
		$WIN32_GCC $ARCH_FLAGS $FLAGS $LIBPATHS $INCLUDES $LIB_PATHS $src -c -o /tmp/$name-$archname.o
		$WIN32_AR rcs $target /tmp/$name-$archname.o

		echo Wrote $target
	done
}

function CompileWindowsWin32 {
	local name=$1
	local src=$2
	local targetdir=$3

	archs=( "x86" "x86_64")
	for arch in "${archs[@]}"
	do
		local archname=$arch-win32
		local target=$targetdir/$archname/$name.lib
		echo "Compiling ${name} for ${archname}"

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
		echo "Compiling ${name} for ${archname}"

		RemoveTarget $target
		mkdir -p $(dirname $target)

		$LINUX_GCC $flags -fomit-frame-pointer -fno-strict-aliasing -fno-exceptions $src -c -o /tmp/$name-$archname.o
		$LINUX_AR rcs $target /tmp/$name-$archname.o

		echo Wrote $target
	done
}

function Compile {
	set -e
	local name=$1
	local src=$2
	local targetdir=$3

	if [ "$(uname)" == "Darwin" ]; then
		CompileOSX $name $src $targetdir
		CompileiOS $name $src $targetdir
		CompileAndroid $name $src $targetdir
		CompileHTML5 $name $src $targetdir
		CompileWindowsOnDarwin $name $src $targetdir
	fi
	if [ "$(uname)" == "MINGW32_NT-6.2" ]; then
		CompileWindows $name $src $targetdir
	fi
	if [ "$(uname)" == "Linux" ]; then
		CompileLinux $name $src $targetdir
	fi
	set +e
}
