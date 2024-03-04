#!/bin/bash

if [[ "$#" -lt 4 ]]; then
    printf "Usage: $(basename "$0") <version> <target directory> <service script> [packages url] [extender profile]\n\n";
    exit 1;
fi

VERSION=$1
EXTENDER_DIR=$2
EXTENDER_SERVICE=$3
export DM_PACKAGES_URL=$4
EXTENDER_PROFILE=$5

if [[ -z ${DM_PACKAGES_URL} ]]; then
	echo "[setup] Missing DM_PACKAGES_URL environment variable"
	exit 1
fi

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

CURL_CMD=/usr/bin/curl
TMP_DOWNLOAD_DIR=/tmp/_extender_download

function download_package() {
	local package_name=$1
	local out_package_name=$package_name

	if [[ "$package_name" == *darwin ]]; then
		out_package_name=$(sed "s/.darwin//g" <<< ${package_name})
	fi

	if [[ ! -e ${PLATFORMSDK_DIR}/${out_package_name} ]]; then
		mkdir -p ${TMP_DOWNLOAD_DIR}

		echo "[setup] Downloading" ${DM_PACKAGES_URL}/${package_name}.tar.gz "to" ${TMP_DOWNLOAD_DIR}
		${CURL_CMD} ${DM_PACKAGES_URL}/${package_name}.tar.gz | tar xz -C ${TMP_DOWNLOAD_DIR}

		# The folder inside the package is something like "iPhoneOS.sdk"
		local folder=`(cd ${TMP_DOWNLOAD_DIR} && ls)`
		echo "[setup] Found folder" ${folder}

		if [[ -n ${folder} ]]; then
			mv ${TMP_DOWNLOAD_DIR}/${folder} ${PLATFORMSDK_DIR}/${out_package_name}
			echo "[setup] Installed" ${PLATFORMSDK_DIR}/${package_name}
		else
			echo "[setup] Failed to install" ${package_name}
		fi

		rm -rf ${TMP_DOWNLOAD_DIR}
	else
		echo "[setup] Package" ${PLATFORMSDK_DIR}/${package_name} "already installed"
	fi
}

function download_zig() {
	local url=$1
	local package_name=$2
	local folder=$3

	if [[ ! -e ${folder} ]]; then
		mkdir -p ${TMP_DOWNLOAD_DIR}/zig-tmp

		echo "[setup] Downloading" ${url}/${package_name} "to" ${TMP_DOWNLOAD_DIR}/zig-tmp
		${CURL_CMD} ${url}/${package_name} | tar xJ --strip-components=1 -C ${TMP_DOWNLOAD_DIR}/zig-tmp

		echo "[setup] Rename folder" ${folder}

		mv ${TMP_DOWNLOAD_DIR}/zig-tmp ${folder}
		rm -rf ${TMP_DOWNLOAD_DIR}

		echo "[setup] Installed" ${folder}
	else
		echo "[setup] Package" ${folder} "already installed"
	fi
}


# Keep Apple's naming convention to avoid bugs
PACKAGES=(
    iPhoneOS16.2.sdk
    iPhoneOS17.2.sdk
    iPhoneSimulator16.2.sdk
    iPhoneSimulator17.2.sdk
    MacOSX13.1.sdk
    MacOSX14.2.sdk
    XcodeDefault14.2.xctoolchain.darwin
    XcodeDefault15.1.xctoolchain.darwin
)

ZIG_VERSION=0.11.0
ZIG_PATH_0_11=${PLATFORMSDK_DIR}/zig-0-11
ZIG_PACKAGE_NAME=zig-macos-x86_64-${ZIG_VERSION}-dev.3937+78eb3c561.tar.xz
ZIG_URL=https://ziglang.org/builds

function download_packages() {
    for package_name in ${PACKAGES[@]}; do
        download_package ${package_name}
    done
}

echo "[setup] Downloading packages"
download_packages

echo "[setup] Downloading Zig"
download_zig ${ZIG_URL} ${ZIG_PACKAGE_NAME} ${ZIG_PATH_0_11}

chmod a+x ${EXTENDER_INSTALL_DIR}/service.sh

if [[ -e ${EXTENDER_SERVICE} ]]; then
    echo "[setup] Stopping extender service"
    ${EXTENDER_SERVICE} stop ${EXTENDER_DIR}
else
    echo "[setup] Extender service not running"
fi

echo "[setup] Symlinking ${VERSION} to ${EXTENDER_DIR}/current"
ln -sfn ${VERSION} ${EXTENDER_DIR}/current

echo "[setup] Symlinking ${EXTENDER_DIR}/current/service.sh ${EXTENDER_SERVICE}"
ln -sfn ${EXTENDER_DIR}/current/service.sh ${EXTENDER_SERVICE}

if [[ -e ${EXTENDER_SERVICE} ]]; then
    echo "[setup] Starting extender service with profile ${EXTENDER_PROFILE}"

    ${EXTENDER_SERVICE} start ${EXTENDER_DIR} ${EXTENDER_PROFILE}
else
    echo "[setup] ERROR No extender service found"
    exit 1
fi
