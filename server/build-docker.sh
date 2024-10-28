#! /bin/sh
set -e

SCRIPT_DIR="$(cd "$(dirname "${0}")"; pwd)"

DOCKER_REGISTRY=europe-west1-docker.pkg.dev/extender-426409/extender-public-registry
DOCKER_PS4_PRIVATE_REGISTRY=europe-west1-docker.pkg.dev/extender-426409/extender-ps4-private-registry
DOCKER_PS5_PRIVATE_REGISTRY=europe-west1-docker.pkg.dev/extender-426409/extender-ps5-private-registry
DOCKER_NINTENDO_PRIVATE_REGISTRY=europe-west1-docker.pkg.dev/extender-426409/extender-nintendo-private-registry

# base images
echo "Base image"
DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_REGISTRY/extender-base-env:1.2.1 -t $DOCKER_REGISTRY/extender-base-env:latest -f $SCRIPT_DIR/docker/Dockerfile.base-env $SCRIPT_DIR/docker
echo "Linux image"
DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_REGISTRY/extender-linux-env:latest -f $SCRIPT_DIR/docker/Dockerfile.linux-env $SCRIPT_DIR/docker

REQUESTED="$@"
[ -z "$REQUESTED" ] && REQUESTED="android windows web ps4 ps5 nintendo"
for request in $REQUESTED; do
    INSTALL=""
    case $request in
        web)
            INSTALL="wine emsdk-2011 emsdk-3155 emsdk-3165"
            ;;
        ps4)
            INSTALL="wine ps4-10500 ps4-11000 ps4-12000"
            ;;
        ps5)
            INSTALL="wine ps5-8000 ps5-9000 ps5-10000"
            ;;
        nintendo)
            INSTALL="wine nssdk-1532 nssdk-1753 nssdk-1832"
            ;;
        android)
            INSTALL="android android-ndk25"
            ;;
        android-ndk-*)
            INSTALL="android $request"
            ;;
        windows)
            INSTALL="wine winsdk-2019 winsdk-2022"
            ;;
        *)
            INSTALL="$request"
            ;;
    esac
    for install in $INSTALL; do
        echo "Install $install"
        case $install in
        ps4-*)
            DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_PS4_PRIVATE_REGISTRY/extender-${install}-env:latest -f $SCRIPT_DIR/docker/Dockerfile.$(echo $install | sed 's,-,.,')-env $SCRIPT_DIR/docker
            ;;
        ps5-*)
            DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_PS5_PRIVATE_REGISTRY/extender-${install}-env:latest -f $SCRIPT_DIR/docker/Dockerfile.$(echo $install | sed 's,-,.,')-env $SCRIPT_DIR/docker
            ;;
        nssdk-*)
            DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_NINTENDO_PRIVATE_REGISTRY/extender-${install}-env:latest -f $SCRIPT_DIR/docker/Dockerfile.$(echo $install | sed 's,-,.,')-env $SCRIPT_DIR/docker
            ;;
        wine)
            DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_REGISTRY/extender-wine-env:1.2.1 -t $DOCKER_REGISTRY/extender-wine-env:latest -f $SCRIPT_DIR/docker/Dockerfile.wine-env $SCRIPT_DIR/docker
            ;;
        android)
            DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_REGISTRY/extender-android-env:1.33 -f $SCRIPT_DIR/docker/Dockerfile.android-env $SCRIPT_DIR/docker
            ;;
        android-ndk*|winsdk-*|emsdk-*)
            DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_REGISTRY/extender-${install}-env:latest -f $SCRIPT_DIR/docker/Dockerfile.$(echo $install | sed 's,-,.,')-env $SCRIPT_DIR/docker
            ;;
        *)
            echo "Unknown"
            exit 1
            ;;
        esac
    done
done
