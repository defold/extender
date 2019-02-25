#! /usr/bin/env bash

EXTENDER_DIR=./extender
if [ ! -e $EXTENDER_DIR ]; then
	mkdir $EXTENDER_DIR
	echo "Created" $EXTENDER_DIR
fi

EXTENDER_CACHE_DIR=${EXTENDER_DIR}/cache/data
if [ ! -e $EXTENDER_CACHE_DIR ]; then
	mkdir -p $EXTENDER_CACHE_DIR
	echo "Created" $EXTENDER_CACHE_DIR
fi

# Download packages
SDK_DIR=${EXTENDER_DIR}/platformsdk
if [ ! -e $SDK_DIR ]; then
	mkdir -p $SDK_DIR
	echo "Created" $SDK_DIR
fi

S3_URL=https://s3-eu-west-1.amazonaws.com/defold-packages

function download() {
	local package_name=$1

	if [ ! -e ${SDK_DIR}/${package_name} ]; then
		mkdir _tmpdir

		echo "Downloading" ${package_name}.tar.gz
		wget -q -O - ${S3_URL}/${package_name}.tar.gz | tar xz -C _tmpdir

		# the folder inside the package is something like "iPhoneOS.sdk"
		local folder=`(cd _tmpdir && ls)`
		echo "Found folder" $folder
		mv _tmpdir/${folder} ${SDK_DIR}/${package_name}
		rmdir _tmpdir

		echo "Installed" ${SDK_DIR}/${package_name}
	else
		echo "Package" ${SDK_DIR}/${package_name} "already installed"
	fi
}

# These are packaged with the version omitted, but it's needed in order to avoid bugs (e.g. the layout change issue)
download iPhoneOS11.2.sdk
download iPhoneOS12.1.sdk
download iPhoneSimulator12.1.sdk
download MacOSX10.13.sdk
download XcodeDefault10.1.xctoolchain

