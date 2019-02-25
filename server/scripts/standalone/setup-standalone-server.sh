#!/bin/bash

if [ $# -ne 1 ]; then
    printf "Usage: $(basename "$0") <version>\n\n";
    exit 1;
fi

VERSION=$1

EXTENDER_DIR=/usr/local/extender
EXTENDER_SERVICE=/usr/local/bin/extender
EXTENDER_INSTALL_DIR=/usr/local/extender/${VERSION}

if [ ! -e ${EXTENDER_DIR} ]; then
	echo "Error! Extender home directory ${EXTENDER_DIR} not setup, exiting.\n\n"
	exit 2
fi

# Builds
EXTENDER_BUILD_DIR=${EXTENDER_DIR}/builds
if [ ! -e ${EXTENDER_BUILD_DIR} ]; then
	mkdir -p ${EXTENDER_BUILD_DIR}
	echo "Created build directory at ${EXTENDER_BUILD_DIR}."
fi

# Platform SDKs
PLATFORMSDK_DIR=${EXTENDER_DIR}/platformsdk
if [ ! -e ${PLATFORMSDK_DIR} ]; then
	mkdir -p ${PLATFORMSDK_DIR}
	echo "Created SDK directory at ${PLATFORMSDK_DIR}."
fi

# Logs
LOGS_DIR=/usr/local/var/log/extender
if [ ! -e ${LOGS_DIR} ]; then
	mkdir -p ${LOGS_DIR}
	echo "Created logs directory at ${LOGS_DIR}."
fi

S3_URL=https://s3-eu-west-1.amazonaws.com/defold-packages
WGET_CMD=/usr/local/bin/wget

function download_package() {
	local package_name=$1

	if [ ! -e ${PLATFORMSDK_DIR}/${package_name} ]; then
		mkdir _tmpdir

		echo "Downloading" ${package_name}.tar.gz
		${WGET_CMD} -q -O - ${S3_URL}/${package_name}.tar.gz | tar xz -C _tmpdir

		# The folder inside the package is something like "iPhoneOS.sdk"
		local folder=`(cd _tmpdir && ls)`
		echo "Found folder" ${folder}
		mv _tmpdir/${folder} ${PLATFORMSDK_DIR}/${package_name}
		rmdir _tmpdir

		echo "Installed" ${PLATFORMSDK_DIR}/${package_name}
	else
		echo "Package" ${PLATFORMSDK_DIR}/${package_name} "already installed"
	fi
}

# Keep Apple's naming convention to avoid bugs
PACKAGES=(
    iPhoneOS11.2.sdk
    iPhoneOS12.1.sdk
    iPhoneSimulator12.1.sdk
    MacOSX10.13.sdk
    XcodeDefault10.1.xctoolchain
)

function download_packages() {
    for package_name in ${PACKAGES[@]}; do
        download_package ${package_name}
    done
}

download_packages

${EXTENDER_SERVICE} stop
ln -sfn ${VERSION} /usr/local/extender/current
ln -sfn /usr/local/extender/current/service.sh /usr/local/bin/extender
chmod a+x ${EXTENDER_INSTALL_DIR}/service.sh
${EXTENDER_SERVICE} start
