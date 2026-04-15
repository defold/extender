#! /bin/sh
set -e

SCRIPT_DIR="$(cd "$(dirname "${0}")"; pwd)"

REGISTRY_REGION=europe-west1-docker.pkg.dev
GCP_PROJECT=extender-426409
REGISTRY_PREFIX=$REGISTRY_REGION/$GCP_PROJECT

DOCKER_REGISTRY=$REGISTRY_PREFIX/extender-public-registry
DOCKER_PS4_PRIVATE_REGISTRY=$REGISTRY_PREFIX/extender-ps4-private-registry
DOCKER_PS5_PRIVATE_REGISTRY=$REGISTRY_PREFIX/extender-ps5-private-registry
DOCKER_NINTENDO_PRIVATE_REGISTRY=$REGISTRY_PREFIX/extender-nintendo-private-registry
DOCKER_XBOX_PRIVATE_REGISTRY=$REGISTRY_PREFIX/extender-xbox-private-registry

MULTI_ARCH="linux/amd64"
[[ -z "$NO_ARM64" ]] && MULTI_ARCH+=",linux/arm64"

# build env image (shared across Dockerfiles)
echo "Build env image with archs: $MULTI_ARCH"
docker buildx build --load --platform $MULTI_ARCH -t $DOCKER_REGISTRY/extender-build-env:1.0.0 -t $DOCKER_REGISTRY/extender-build-env:latest -f $SCRIPT_DIR/docker/Dockerfile.build-env $SCRIPT_DIR/docker

# base images
echo "Base image with archs: $MULTI_ARCH"
docker buildx build --load --platform $MULTI_ARCH -t $DOCKER_REGISTRY/extender-base-env:1.6.0 -t $DOCKER_REGISTRY/extender-base-env:latest -f $SCRIPT_DIR/docker/Dockerfile.base-env $SCRIPT_DIR/docker

REQUESTED="$@"
[ -z "$REQUESTED" ] && REQUESTED="android windows web ps4 ps5 nintendo linux"
for request in $REQUESTED; do
    INSTALL=""
    case $request in
        web)
            INSTALL="emsdk-406"
            ;;
        ps4)
            INSTALL="wine ps4-12500"
            ;;
        ps5)
            INSTALL="wine ps5-12000"
            ;;
        nintendo)
            INSTALL="wine nssdk-2143"
            ;;
        android)
            INSTALL="android android-ndk25 android-ndk25_sdk36"
            ;;
        android-ndk-*)
            INSTALL="android $request"
            ;;
        windows)
            # skip building winsdk-2022 because wine image contains newer Clang.
            INSTALL="wine winsdk-2022_144435207"
            ;;
        xbox)
            INSTALL="wine winsdk-2022_144435207 xbox-251002"
            ;;
        linux)
            INSTALL="linux"
            ;;
        *)
            INSTALL="$request"
            ;;
    esac
    for install in $INSTALL; do
        echo "Install $install"
        case $install in
        ps4-*)
            DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --load --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_PS4_PRIVATE_REGISTRY/extender-${install}-env:latest -f $SCRIPT_DIR/docker/Dockerfile.$(echo $install | sed 's,-,.,')-env $SCRIPT_DIR/docker
            ;;
        ps5-*)
            DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --load --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_PS5_PRIVATE_REGISTRY/extender-${install}-env:latest -f $SCRIPT_DIR/docker/Dockerfile.$(echo $install | sed 's,-,.,')-env $SCRIPT_DIR/docker
            ;;
        nssdk-*)
            DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --load --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_NINTENDO_PRIVATE_REGISTRY/extender-${install}-env:latest -f $SCRIPT_DIR/docker/Dockerfile.$(echo $install | sed 's,-,.,')-env $SCRIPT_DIR/docker
            ;;
        xbox-*)
            DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --load --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_XBOX_PRIVATE_REGISTRY/extender-${install}-env:latest -f $SCRIPT_DIR/docker/Dockerfile.$(echo $install | sed 's,-,.,')-env $SCRIPT_DIR/docker
            ;;
        wine)
            DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --load --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_REGISTRY/extender-wine-env:1.7.0 -t $DOCKER_REGISTRY/extender-wine-env:latest -f $SCRIPT_DIR/docker/Dockerfile.wine-env $SCRIPT_DIR/docker
            ;;
        android)
            DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --load --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_REGISTRY/extender-android-env:1.7.0 -t $DOCKER_REGISTRY/extender-android-env:latest -f $SCRIPT_DIR/docker/Dockerfile.android-env $SCRIPT_DIR/docker
            ;;
        winsdk-2022_144435207)
            DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --load --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_REGISTRY/extender-winsdk-2022_144435207-env:1.1.0 -t $DOCKER_REGISTRY/extender-winsdk-2022_144435207-env:latest -f $SCRIPT_DIR/docker/Dockerfile.winsdk.2022_144435207-env $SCRIPT_DIR/docker
            ;;
        android-ndk*|winsdk-*|emsdk-*)
            DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --load --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_REGISTRY/extender-${install}-env:latest -f $SCRIPT_DIR/docker/Dockerfile.$(echo $install | sed 's,-,.,')-env $SCRIPT_DIR/docker
            ;;
        linux)
            DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --load --secret id=DM_PACKAGES_URL --platform $MULTI_ARCH -t $DOCKER_REGISTRY/extender-linux-env:latest -f $SCRIPT_DIR/docker/Dockerfile.linux-env $SCRIPT_DIR/docker
            ;;
        *)
            echo "Unknown"
            exit 1
            ;;
        esac
    done
done
