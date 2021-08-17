#!/bin/bash

if [[ "$#" -ne 2 ]] && [[ "$#" -ne 3 ]]; then
    printf "Usage: $(basename "$0") <version> <target directory> [service script]\n\n";
    exit 1;
fi

if  [ -e $DM_PACKAGES_URL ]; then
	echo "|setup] Missing DM_PACKAGES_URL environment variable"
	exit 1
fi

VERSION=$1
EXTENDER_DIR=$2
EXTENDER_SERVICE=$3

EXTENDER_INSTALL_DIR=${EXTENDER_DIR}/${VERSION}

if [[ ! -e ${EXTENDER_DIR} ]]; then
	echo "[setup] Error! Extender home directory ${EXTENDER_DIR} not setup, exiting.\n\n"
	exit 2
fi

# Platform SDKs
PLATFORMSDK_DIR=${EXTENDER_DIR}/platformsdk
if [[ ! -e ${PLATFORMSDK_DIR} ]]; then
	mkdir -p ${PLATFORMSDK_DIR}
	echo "[setup] Created SDK directory at ${PLATFORMSDK_DIR}."
fi

# Logs
LOGS_DIR=${EXTENDER_DIR}/logs
if [[ ! -e ${LOGS_DIR} ]]; then
	mkdir -p ${LOGS_DIR}
	echo "[setup] Created logs directory at ${LOGS_DIR}."
fi

WGET_CMD=/usr/local/bin/wget
TMP_DOWNLOAD_DIR=/tmp/_extender_download

function download_package() {
	local package_name=$1
	local out_package_name=$package_name

	if [ "XcodeDefault12.5.xctoolchain.darwin" == "$package_name" ]; then
		out_package_name="XcodeDefault12.5.xctoolchain"
	fi

	if [[ ! -e ${PLATFORMSDK_DIR}/${out_package_name} ]]; then
		mkdir -p ${TMP_DOWNLOAD_DIR}

		echo "[setup] Downloading" ${package_name}.tar.gz
		${WGET_CMD} -q -O - ${DM_PACKAGES_URL}/${package_name}.tar.gz | tar xz -C ${TMP_DOWNLOAD_DIR}

		# The folder inside the package is something like "iPhoneOS.sdk"
		local folder=`(cd ${TMP_DOWNLOAD_DIR} && ls)`
		echo "[setup] Found folder" ${folder}


		mv ${TMP_DOWNLOAD_DIR}/${folder} ${PLATFORMSDK_DIR}/${out_package_name}
		rm -rf ${TMP_DOWNLOAD_DIR}

		echo "[setup] Installed" ${PLATFORMSDK_DIR}/${package_name}
	else
		echo "[setup] Package" ${PLATFORMSDK_DIR}/${package_name} "already installed"
	fi
}

# Keep Apple's naming convention to avoid bugs
PACKAGES=(
    iPhoneOS14.5.sdk
    iPhoneSimulator14.5.sdk
    MacOSX11.3.sdk
    XcodeDefault12.5.xctoolchain.darwin
)

function download_packages() {
    for package_name in ${PACKAGES[@]}; do
        download_package ${package_name}
    done
}

download_packages

chmod a+x ${EXTENDER_INSTALL_DIR}/service.sh

if [[ -e ${EXTENDER_SERVICE} ]]; then
    ${EXTENDER_SERVICE} stop
fi

ln -sfn ${VERSION} ${EXTENDER_DIR}/current

if [[ -e ${EXTENDER_SERVICE} ]]; then
    ln -sfn ${EXTENDER_DIR}/current/service.sh ${EXTENDER_SERVICE}
    ${EXTENDER_SERVICE} start
fi
