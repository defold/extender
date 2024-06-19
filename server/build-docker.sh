#! /bin/sh
set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

DOCKER_REGISTRY=europe-north1-docker.pkg.dev/extender-poc/extender-public-registry
DOCKER_PRIVATE_REGISTRY=europe-north1-docker.pkg.dev/extender-poc/extender-private-registry

# base images
echo "Base image"
DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_REGISTRY/extender-base-env:latest -f $SCRIPT_DIR/docker/Dockerfile.base-env $SCRIPT_DIR/docker
echo "Wine base image"
DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_REGISTRY/extender-wine-env:latest -f $SCRIPT_DIR/docker/Dockerfile.wine-env $SCRIPT_DIR/docker
echo "Linux image"
DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_REGISTRY/extender-linux-env:latest -f $SCRIPT_DIR/docker/Dockerfile.linux-env $SCRIPT_DIR/docker
echo "Android base image"
DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_REGISTRY/extender-android-env:latest -f $SCRIPT_DIR/docker/Dockerfile.android-env $SCRIPT_DIR/docker
echo "Winsdk 2019"
DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_REGISTRY/extender-winsdk-2019-env:latest -f $SCRIPT_DIR/docker/Dockerfile.winsdk.2019-env $SCRIPT_DIR/docker
echo "Winsdk 2022"
DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_REGISTRY/extender-winsdk-2022-env:latest -f $SCRIPT_DIR/docker/Dockerfile.winsdk.2022-env $SCRIPT_DIR/docker
echo "Emsdk 2.0.11"
DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_REGISTRY/extender-emsdk-2011-env:latest -f $SCRIPT_DIR/docker/Dockerfile.emsdk.2011-env $SCRIPT_DIR/docker
echo "Emsdk 3.1.55"
DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_REGISTRY/extender-emsdk-3155-env:latest -f $SCRIPT_DIR/docker/Dockerfile.emsdk.3155-env $SCRIPT_DIR/docker
echo "Android ndk 25"
DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_REGISTRY/extender-android-ndk25-env:latest -f $SCRIPT_DIR/docker/Dockerfile.android.ndk25-env $SCRIPT_DIR/docker

echo "Ps4 images"
DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_PRIVATE_REGISTRY/extender-ps4-10500-env:latest -f $SCRIPT_DIR/docker/Dockerfile.ps4.10500-env $SCRIPT_DIR/docker
DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_PRIVATE_REGISTRY/extender-ps4-11000-env:latest -f $SCRIPT_DIR/docker/Dockerfile.ps4.11000-env $SCRIPT_DIR/docker
echo "Ps5 images"
DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_PRIVATE_REGISTRY/extender-ps5-8000-env:latest -f $SCRIPT_DIR/docker/Dockerfile.ps5.8000-env $SCRIPT_DIR/docker
echo "Nssdk images"
DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_PRIVATE_REGISTRY/extender-nssdk-1532-env:latest -f $SCRIPT_DIR/docker/Dockerfile.nssdk.1532-env $SCRIPT_DIR/docker
DM_PACKAGES_URL=$DM_PACKAGES_URL docker buildx build --secret id=DM_PACKAGES_URL --platform linux/amd64 -t $DOCKER_PRIVATE_REGISTRY/extender-nssdk-1753-env:latest -f $SCRIPT_DIR/docker/Dockerfile.nssdk.1753-env $SCRIPT_DIR/docker