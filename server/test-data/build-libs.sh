
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

# # copy these into the "a" sdk
cp -v -r engineext/lib/ sdk/a/defoldsdk/lib
rm -rf ./engineext

CompileLibsToExtension alib ext/lib
CompileLibsToExtension alib ext2/lib
CompileLibsToExtension blib ext2/lib

(cd testproject_appmanifest && ./build.sh)

