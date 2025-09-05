#!/bin/bash

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

echo "Load common env ..."
source $SCRIPT_DIR/../../envs/.env

if [[ ! -e ${SCRIPT_DIR}/../../envs/user.env ]]; then
    echo "${SCRIPT_DIR}/../../envs/user.env doesn't exist. Runs ./server/envs/generate_user_env.sh to generate it."
    $SCRIPT_DIR/../../envs/generate_user_env.sh
fi

echo "Load user env ..."
source $SCRIPT_DIR/../../envs/user.env

if [[ -z ${DM_PACKAGES_URL} ]]; then
    echo "[setup] Missing DM_PACKAGES_URL environment variable"
    exit 1
fi

# Platform SDKs
if [[ "" == ${PLATFORMSDK_DIR} ]]; then
    echo "Missing PLATFORMSDK_DIR. Please run ./server/envs/generate_user_env.sh to generate it"
    exit 1
fi
if [[ ! -e ${PLATFORMSDK_DIR} ]]; then
    mkdir -p ${PLATFORMSDK_DIR}
    echo "[setup] Created SDK directory at ${PLATFORMSDK_DIR}."
fi

# Logs
if [[ ! -e ${LOG_DIRECTORY} ]]; then
    mkdir -p ${LOG_DIRECTORY}
    echo "[setup] Created logs directory at ${LOG_DIRECTORY}."
fi

CURL_CMD=$(which curl)
TMP_DOWNLOAD_DIR=/tmp/_extender_download

function check_url() {
    local url=$1
    echo "[check url]" ${url}
    STATUS_CODE=$(curl --head --write-out "%{http_code}" --silent --output /dev/null ${url})
    if (( STATUS_CODE == 200 ))
    then
        echo "${STATUS_CODE}"
    else
        echo -e "\033[0;31mError: Url returned status code ${STATUS_CODE}: ${url} \033[m"
        exit 1
    fi
}

function download_package() {
    local package_name=$1
    local out_package_name=$package_name
    local url=${DM_PACKAGES_URL}/${package_name}.tar.gz

    if [[ "$package_name" == *darwin ]]; then
        out_package_name=$(sed "s/.darwin//g" <<< ${package_name})
    fi

    # if it has grown old, we want to know as soon as possible
    check_url ${url}

    if [[ ! -e ${PLATFORMSDK_DIR}/${out_package_name} ]]; then
        mkdir -p ${TMP_DOWNLOAD_DIR}

        echo "[setup] Downloading" ${url} "to" ${TMP_DOWNLOAD_DIR}
        ${CURL_CMD} ${url} | tar xz -C ${TMP_DOWNLOAD_DIR}

        # The folder inside the package is something like "iPhoneOS.sdk"
        local folder=`(cd ${TMP_DOWNLOAD_DIR} && ls)`
        echo "[setup] Found folder" ${folder}

        if [[ -n ${folder} ]]; then
            if [[ "$package_name" == *xctoolchain* ]]; then
                for f in $folder; do
                    if [[ -L $f ]]; then
                        cp ${TMP_DOWNLOAD_DIR}/${f} ${PLATFORMSDK_DIR}
                    else
                        cp -R ${TMP_DOWNLOAD_DIR}/${f} ${PLATFORMSDK_DIR}
                    fi
                    echo "[setup] Copy $f to ${PLATFORMSDK_DIR}"
                done
                echo "[setup] Installed ${package_name}"
            else
                mv ${TMP_DOWNLOAD_DIR}/${folder} ${PLATFORMSDK_DIR}/${out_package_name}
                echo "[setup] Installed" ${PLATFORMSDK_DIR}/${package_name}
            fi
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
    local full_url=${url}/${package_name}

    # if it has grown old, we want to know as soon as possible
    check_url ${full_url}

    if [[ ! -e ${folder} ]]; then
        mkdir -p ${TMP_DOWNLOAD_DIR}/zig-tmp


        echo "[setup] Downloading" ${url}/${package_name} "to" ${TMP_DOWNLOAD_DIR}/zig-tmp
        ${CURL_CMD} ${url}/${package_name} | tar xJ --strip-components=1 -C ${TMP_DOWNLOAD_DIR}/zig-tmp

        echo "[setup] Rename folder to" ${folder}

        mv ${TMP_DOWNLOAD_DIR}/zig-tmp ${folder}
        rm -rf ${TMP_DOWNLOAD_DIR}

        echo "[setup] Installed" ${folder}
    else
        echo "[setup] Package" ${folder} "already installed"
    fi
}

function install_dotnet() {
    # https://dotnet.microsoft.com/en-us/download/dotnet/9.0
    # https://github.com/dotnet/core/blob/main/release-notes/9.0/install.md

    local version="9.0.1xx"
    if [[ ! -e ${DOTNET_ROOT} ]]; then

        mkdir -p ${DOTNET_ROOT}

        local os=$(uname)
        if [ "Darwin" == "${os}" ] || [ "Linux" == "${os}" ]; then

            echo "Downloading dotnet-install.sh ..."

            ${CURL_CMD} -L https://dot.net/v1/dotnet-install.sh --output ./dotnet-install.sh
            chmod +x ./dotnet-install.sh

            echo "Installing dotnet ..."
            ./dotnet-install.sh --channel ${version} --install-dir ${DOTNET_ROOT}

            rm ./dotnet-install.sh

        else
            echo "Windows not supported standalone yet"

            # https://github.com/dotnet/core/blob/main/release-notes/9.0/install.md
        fi

        echo "[setup] Installed dotnet"
    else
        echo "[setup] Package" ${DOTNET_ROOT} "already installed"
    fi

    local DOTNET=${DOTNET_ROOT}/dotnet

    DOTNET_VERSION=$(${DOTNET} --info | grep -e "Host:" -A 1 | grep -e "Version:" | awk '{print $2}')
    echo ${DOTNET_VERSION} > ${DOTNET_VERSION_FILE}

    echo "[setup] Using dotnet:" ${DOTNET} " version:" $(${DOTNET} --version) "  sdk:" ${DOTNET_VERSION}

    if [[ ! -e ${NUGET_PACKAGES} ]]; then
        mkdir -p ${NUGET_PACKAGES}
        echo "[setup] Created Nuget directory at ${NUGET_PACKAGES}."
    fi
    echo "Set Nuget package folder to ${NUGET_PACKAGES}"
    echo "${DOTNET} nuget config set globalPackagesFolder ${NUGET_PACKAGES}"
    ${DOTNET} nuget config set globalPackagesFolder ${NUGET_PACKAGES}

    echo "[setup] Using NUGET_PACKAGES=${NUGET_PACKAGES}"

}

if [[ $(uname) == "Darwin" ]]; then
    echo "Setup Macos environment"

    echo "Load macos environment..."
    source $SCRIPT_DIR/../../envs/macos.env

    # Keep Apple's naming convention to avoid bugs
    PACKAGES=(
        iPhoneOS${IOS_18_VERSION}.sdk
        iPhoneSimulator${IOS_18_VERSION}.sdk
        MacOSX${MACOS_15_VERSION}.sdk
        XcodeDefault${XCODE_16_VERSION}.xctoolchain.darwin
    )
    function download_packages() {
        for package_name in ${PACKAGES[@]}; do
            download_package ${package_name}
        done
    }

    echo "[setup] Downloading packages"
    download_packages
fi

ZIG_ARCH=x86_64
if [[ $(uname -m) == "arm64" ]]; then
    ZIG_ARCH=aarch64
fi
ZIG_PACKAGE_NAME=zig-macos-${ZIG_ARCH}-${ZIG_VERSION}.tar.xz
ZIG_URL=https://ziglang.org/download/${ZIG_VERSION}

echo "[setup] Downloading Zig"
download_zig ${ZIG_URL} ${ZIG_PACKAGE_NAME} ${ZIG_PATH_0_11}

echo "[setup] Installing dotnet"
install_dotnet

if [[ $(uname) == "Darwin" ]]; then
    echo "[setup] Install hmap utility"
    brew install milend/taps/hmap
fi