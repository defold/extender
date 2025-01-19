#!/usr/bin/env bash

if [ "$(uname)" == "Darwin" ]; then
	LINUX_GCC=$(brew --prefix llvm)/bin/clang++
	LINUX_AR=$(brew --prefix llvm)/bin/llvm-ar
	WIN32_GCC=$(brew --prefix llvm)/bin/clang++
	WIN32_AR=$(brew --prefix llvm)/bin/llvm-ar
	HOST='darwin'
else
	LINUX_GCC=/usr/bin/g++
	LINUX_AR=/usr/bin/ar
	HOST='linux'
fi

ANDROID_NDK=${DYNAMO_HOME}/ext/SDKs/android-ndk-r25b
ANDROID_GCC=${ANDROID_NDK}/toolchains/llvm/prebuilt/darwin-x86_64/bin/
ANDROID_AR=${ANDROID_GCC}/llvm-ar
ANDROID_NDK_API_VERSION='19'
ANDROID_64_NDK_API_VERSION='21'
ANDROID_SYS_ROOT=${ANDROID_NDK}/toolchains/llvm/prebuilt/${HOST}-x86_64/sysroot
ANDROID_INCLUDE_ARCH=${ANDROID_NDK}/sources/android/cpufeatures

IOS_GCC=${DYNAMO_HOME}/ext/SDKs/XcodeDefault16.2.xctoolchain/usr/bin/clang++
IOS_AR=${DYNAMO_HOME}/ext/SDKs/XcodeDefault16.2.xctoolchain/usr/bin/ar
IOS_MIN_VERSION=11.0
IOS_SYS_ROOT=${DYNAMO_HOME}/ext/SDKs/iPhoneOS18.2.sdk

OSX_GCC=${DYNAMO_HOME}/ext/SDKs/XcodeDefault16.2.xctoolchain/usr/bin/clang++
OSX_AR=${DYNAMO_HOME}/ext/SDKs/XcodeDefault16.2.xctoolchain/usr/bin/ar
OSX_MIN_VERSION=10.13
OSX_SYS_ROOT=${DYNAMO_HOME}/ext/SDKs/MacOSX15.2.sdk

EMCC=$DYNAMO_HOME/ext/SDKs/emsdk-3.1.65/upstream/emscripten/em++
EMAR=$DYNAMO_HOME/ext/SDKs/emsdk-3.1.65/upstream/emscripten/emar

WIN32_CL=cl.exe
WIN32_LIB=lib.exe

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
	local lib_type=$4

	archs=("armv7" "arm64")
	for arch in "${archs[@]}"
	do
		local archname=$arch-android
		local target=""
		if [ "$lib_type" == "static" ]; then
			target=$targetdir/$archname/lib$name.a
		elif [ "$lib_type" == "dynamic" ]; then
			target=$targetdir/$archname/lib$name.so
		fi

		echo "Compiling ${name} for ${archname} type ${lib_type}"

		RemoveTarget $target
		mkdir -p $(dirname $target)

		CFLAGS="-g -gdwarf-2 -D__STDC_LIMIT_MACROS -Wall -fpic -ffunction-sections -fstack-protector -fomit-frame-pointer -fno-strict-aliasing -fno-exceptions -funwind-tables"
		if [ "armv7" == "$arch" ]; then
			CFLAGS="${CFLAGS} -D__ARM_ARCH_5__ -D__ARM_ARCH_5T__ -D__ARM_ARCH_5E__ -D__ARM_ARCH_5TE__ -march=armv7-a -mfloat-abi=softfp -mfpu=vfp"
			LDFLAGS="-Wl,--fix-cortex-a8 -Wl,--no-undefined -Wl,-z,noexecstack -landroid -fpic -z text"
			GCC=${ANDROID_GCC}armv7a-linux-androideabi${ANDROID_NDK_API_VERSION}-clang++
		else
			CFLAGS="${CFLAGS} -D__aarch64__ -march=armv8-a"
			LDFLAGS="-Wl,--no-undefined -Wl,-z,noexecstack -landroid -fpic -z text"
			GCC=${ANDROID_GCC}aarch64-linux-android${ANDROID_64_NDK_API_VERSION}-clang++
		fi


		$GCC -gdwarf-2 $CFLAGS -I${ANDROID_INCLUDE_ARCH} -isysroot=${ANDROID_SYS_ROOT} $src -c -o /tmp/$name-$archname.o
		if [ "$lib_type" == "static" ]; then
			$ANDROID_AR rcs $target /tmp/$name-$archname.o
		elif [ "$lib_type" == "dynamic" ]; then
			$GCC -shared -isysroot=${ANDROID_SYS_ROOT} -o $target /tmp/$name-$archname.o
		fi

		echo Wrote $target
	done
}


function CompileiOS {
	local name=$1
	local src=$2
	local targetdir=$3
	local lib_type=$4

	archs=("arm64")
	for arch in "${archs[@]}"
	do
		local archname=$arch-ios
		local target=""
		if [ "$lib_type" == "static" ]; then
			target=$targetdir/$archname/lib$name.a
		elif [ "$lib_type" == "dynamic" ]; then
			target=$targetdir/$archname/lib$name.so
		fi

		echo "Compiling ${name} for ${archname}"

		RemoveTarget $target
		mkdir -p $(dirname $target)

		$IOS_GCC -arch $arch -stdlib=libc++ -fno-strict-aliasing -fno-exceptions -miphoneos-version-min=${IOS_MIN_VERSION} -isysroot ${IOS_SYS_ROOT} $src -c -o /tmp/$name-$archname.o
		if [ "$lib_type" == "static" ]; then	
			$IOS_AR rcs $target /tmp/$name-$archname.o
		elif [ "$lib_type" == "dynamic" ]; then
			$IOS_GCC -arch $arch -shared -isysroot ${IOS_SYS_ROOT} -o $target /tmp/$name-$archname.o
		fi

		echo Wrote $target
	done
}

function CompileOSX {
	local name=$1
	local src=$2
	local targetdir=$3
	local lib_type=$4

	archs=("x86_64" "arm64")
	for arch in "${archs[@]}"
	do
		local archname=$arch-osx
		local target=""
		if [ "$lib_type" == "static" ]; then
			target=$targetdir/$archname/lib$name.a
		elif [ "$lib_type" == "dynamic" ]; then
			target=$targetdir/$archname/lib$name.dylib
		fi
		
		echo "Compiling ${name} for ${archname} type ${lib_type}"

		RemoveTarget $target
		mkdir -p $(dirname $target)

		$OSX_GCC -arch $arch -stdlib=libc++ -fomit-frame-pointer -fno-strict-aliasing -fno-exceptions -mmacosx-version-min=${OSX_MIN_VERSION} -isysroot ${OSX_SYS_ROOT} $src -c -o /tmp/$name-$archname.o
		if [ "$lib_type" == "static" ]; then
			$OSX_AR rcs $target /tmp/$name-$archname.o
		elif [ "$lib_type" == "dynamic" ]; then	
			$OSX_GCC -arch $arch -shared -isysroot ${OSX_SYS_ROOT} -o $target /tmp/$name-$archname.o
		fi

		echo Wrote $target
	done
}

function CompileHTML5 {
	local name=$1
	local src=$2
	local targetdir=$3
	local lib_type=$4

	archs=("js" "wasm")
	for arch in "${archs[@]}"
	do
		local archname=$arch-web
		echo "Compiling ${name} for ${archname} type ${lib_type}"
		local target=""
		if [ "$lib_type" == "static" ]; then
			target=$targetdir/$archname/lib$name.a
		elif [ "$lib_type" == "dynamic" ]; then
			target=$targetdir/$archname/lib$name.so
		fi

		RemoveTarget $target
		mkdir -p $(dirname $target)

		$EMCC $src -c -o /tmp/$name-$archname.o
		if [ "$lib_type" == "static" ]; then
			$EMAR rcs $target /tmp/$name-$archname.o
		elif [ "$lib_type" == "dynamic" ]; then
			$EMCC -sSIDE_MODULE=1 -o $target /tmp/$name-$archname.o
		fi

		echo Wrote $target
	done
}

function CompileWindowsOnDarwin {
	local name=$1
	local src=$2
	local targetdir=$3
	local lib_type=$4

	archs=( "x86" "x86_64")
	for arch in "${archs[@]}"
	do
		local archname=$arch-win32
		echo "Compiling ${name} for ${archname} type ${lib_type}"
		local target=""
		if [ "$lib_type" == "static" ]; then
			target=$targetdir/$archname/$name.lib
		elif [ "$lib_type" == "dynamic" ]; then
			target=$targetdir/$archname/$name.dll
		fi

		RemoveTarget $target
		mkdir -p $(dirname $target)

		local INCLUDES="-I${DYNAMO_HOME}/ext/SDKs/Win32/MicrosoftVisualStudio14.0/VC/include -I${DYNAMO_HOME}/ext/SDKs/Win32/MicrosoftVisualStudio14.0/VC/atlmfc/include -I${DYNAMO_HOME}/ext/SDKs/Win32/WindowsKits/10/Include/10.0.20348.0/ucrt -I${DYNAMO_HOME}/ext/SDKs/Win32/MicrosoftVisualStudio14.0/VC/Tools/MSVC/14.37.32822/include"
		local DEFINES="-DDM_PLATFORM_WINDOWS -D_CRT_SECURE_NO_WARNINGS -D__STDC_LIMIT_MACROS -DWINVER=0x0600 -DWIN32"
		local FLAGS="-O2 -Wall -Werror=format -fvisibility=hidden -g" # -codeview
		local ARCH_FLAGS=""
		local LIB_PATHS=""

		if [ "$arch" == "x86_64" ]; then
			ARCH_FLAGS="-target x86_64-pc-win32-msvc -m64"
			#LIB_PATHS="-L${DYNAMO_HOME}/ext/SDKs/Win32/lib/x86_64-win32 -L${DYNAMO_HOME}/ext/SDKs/Win32/ext/lib/x86_64-win32 -L${DYNAMO_HOME}/ext/SDKs/Win32/MicrosoftVisualStudio14.0/VC/lib/amd64 -L${DYNAMO_HOME}/ext/SDKs/Win32/MicrosoftVisualStudio14.0/VC/atlmfc/lib/amd64 -L${DYNAMO_HOME}/ext/SDKs/Win32/WindowsKits/10/Lib/10.0.20348.0/ucrt/x64"
		else
			ARCH_FLAGS="-target i386-pc-win32-msvc -m32"
			#LIB_PATHS="-L${DYNAMO_HOME}/ext/SDKs/Win32/lib/win32 -L${DYNAMO_HOME}/ext/SDKs/Win32/ext/lib/win32 -L${DYNAMO_HOME}/ext/SDKs/Win32/MicrosoftVisualStudio14.0/VC/lib/ -L${DYNAMO_HOME}/ext/SDKs/Win32/MicrosoftVisualStudio14.0/VC/atlmfc/lib -L${DYNAMO_HOME}/ext/SDKs/Win32/WindowsKits/10/Lib/10.0.20348.0/ucrt/x86"
		fi

		echo $WIN32_GCC $ARCH_FLAGS $FLAGS $LIBPATHS $INCLUDES $LIB_PATHS $src -c -o /tmp/$name-$archname.o
		$WIN32_GCC $ARCH_FLAGS $FLAGS $LIBPATHS $INCLUDES $LIB_PATHS $src -c -o /tmp/$name-$archname.o
		if [ "$lib_type" == "static" ]; then
			$WIN32_AR rcs $target /tmp/$name-$archname.o
		elif [ "$lib_type" == "dynamic" ]; then
			echo "Create dummy .dll file with text content. Enough for testing."
			echo "$target" > $target
		fi

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
	local lib_type=$4

	archs=("x86_64")
	for arch in "${archs[@]}"
	do
		local archname=$arch-linux
		local target=""
		if [ "$lib_type" == "static" ]; then
			target=$targetdir/$archname/lib$name.a
		elif [ "$lib_type" == "dynamic" ]; then
			target=$targetdir/$archname/lib$name.so
		fi
		echo "Compiling ${name} for ${archname} type ${lib_type}"

		RemoveTarget $target
		mkdir -p $(dirname $target)

		$LINUX_GCC -fPIC -fomit-frame-pointer -fno-strict-aliasing -fno-exceptions $src -c -o /tmp/$name-$archname.o
		if [ "$lib_type" == "static" ]; then
			$LINUX_AR rcs $target /tmp/$name-$archname.o
		elif [ "$lib_type" == "dynamic" ]; then
			$LINUX_GCC -shared -o $target /tmp/$name-$archname.o
		fi

		echo Wrote $target
	done
}

function Compile {
	set -e
	local name=$1
	local src=$2
	local targetdir=$3

	if [ "$(uname)" == "Darwin" ]; then
		CompileOSX $name $src $targetdir static
		CompileiOS $name $src $targetdir static
		CompileAndroid $name $src $targetdir static
		CompileHTML5 $name $src $targetdir static
		CompileWindowsOnDarwin $name $src $targetdir static
	fi
	if [ "$(uname)" == "MINGW32_NT-6.2" ]; then
		CompileWindows $name $src $targetdir
	fi
	if [ "$(uname)" == "Linux" ]; then
		CompileLinux $name $src $targetdir static
	fi
	set +e
}

function CompileDynamic {
	set -e
	local name=$1
	local src=$2
	local targetdir=$3

	if [ "$(uname)" == "Darwin" ]; then
		CompileOSX $name $src $targetdir dynamic
		CompileiOS $name $src $targetdir dynamic
		CompileAndroid $name $src $targetdir dynamic
		CompileHTML5 $name $src $targetdir dynamic
		CompileWindowsOnDarwin $name $src $targetdir dynamic
	fi
	# if [ "$(uname)" == "MINGW32_NT-6.2" ]; then
	# 	CompileWindows $name $src $targetdir
	# fi
	if [ "$(uname)" == "Linux" ]; then
		CompileLinux $name $src $targetdir dynamic
	fi
	set +e
}

