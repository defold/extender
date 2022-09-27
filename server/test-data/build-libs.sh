
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

	for file in $dir/*.cpp
	do
		local name=$(basename $file)
		name="${name%.*}"
		echo $name $file
		Compile $name $file $extension
	done
}

function Copy {
	mkdir -p $(dirname test-data/$2)
	cp -v test-data/$1 test-data/$2
}


CompileLibsToExtension enginelibs engineext/lib

# copy these into the "a" sdk
mkdir -p sdk/a/defoldsdk/lib
cp -v -r engineext/lib sdk/a/defoldsdk/
rm -rf ./engineext

# The sdk's has different naming
mv sdk/a/defoldsdk/lib/x86-osx sdk/a/defoldsdk/lib/darwin
mv sdk/a/defoldsdk/lib/x86_64-osx sdk/a/defoldsdk/lib/x86_64-macos

mv sdk/a/defoldsdk/lib/x86-linux sdk/a/defoldsdk/lib/linux

# Need these folders as well (empty is fine)
mkdir -p sdk/a/defoldsdk/ext/lib/darwin
mkdir -p sdk/a/defoldsdk/ext/lib/x86_64-macos

CompileLibsToExtension alib ext/lib
CompileLibsToExtension alib ext2/lib
CompileLibsToExtension blib ext2/lib
CompileLibsToExtension stdlib ext_std/lib

(cd testproject_appmanifest && ./build.sh)

