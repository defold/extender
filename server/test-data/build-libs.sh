
# How to build on Windows
# * Make a shared directory between OSX/Win32 for test-data in Parallels Desktop
# * On the windows machine, open an msys terminal
# * Change directory to the new drive (E.g. X:  "cd x:") which was mounted for you by Parallels Desktop
# * Run "sh build-libs.sh"
# All files should be named and copied automatically by this script


source ./compile.sh

# Find all .cpp files in a folder and make a lib of each of them
function CompileLibsToExtension {
	local dir=$1
	local extension=$2
	local lib_type=$3

	for file in $dir/*.cpp
	do
		local name=$(basename $file)
		name="${name%.*}"
		echo $name $file $lib_type
		if [ "$lib_type" == "dynamic" ]; then
			CompileDynamic $name $file $extension
		else
			Compile $name $file $extension
		fi
	done
}

function Copy {
	mkdir -p $(dirname test-data/$2)
	cp -v test-data/$1 test-data/$2
}


CompileLibsToExtension enginelibs engineext/lib static

# copy these into the "a" sdk
mkdir -p sdk/a/defoldsdk/lib
cp -v -r engineext/lib sdk/a/defoldsdk/
rm -rf ./engineext

# The sdk's has different naming
mv sdk/a/defoldsdk/lib/x86_64-osx sdk/a/defoldsdk/lib/x86_64-macos

# Need these folders as well (empty is fine)
mkdir -p sdk/a/defoldsdk/ext/lib/darwin
mkdir -p sdk/a/defoldsdk/ext/lib/x86_64-macos

CompileLibsToExtension alib ext/lib static
CompileLibsToExtension alib ext2/lib static
CompileLibsToExtension blib ext2/lib static
CompileLibsToExtension stdlib ext_std/lib static

CompileLibsToExtension dynamic_specific1 ext_dyn_libs/lib dynamic
CompileLibsToExtension dynamic_specific2 ext_dyn_libs2/lib dynamic

(cd testproject_appmanifest && ./build.sh)

