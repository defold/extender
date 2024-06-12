#! /bin/sh
set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

$SCRIPT_DIR/../gradlew build -xtest
$SCRIPT_DIR/../gradlew :manifestmergetool:mainJar

# # copy jars
cp $SCRIPT_DIR/build/libs/extender-0.1.0.jar $SCRIPT_DIR/docker
cp $SCRIPT_DIR/manifestmergetool/build/libs/manifestmergetool-0.1.0.jar $SCRIPT_DIR/docker

# # copy users
cp -r $SCRIPT_DIR/users $SCRIPT_DIR/docker

# base images
echo "Base image"
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-base:latest -f $SCRIPT_DIR/docker/Dockerfile.base $SCRIPT_DIR/docker
echo "Wine base image"
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-wine:latest -f $SCRIPT_DIR/docker/Dockerfile.wine $SCRIPT_DIR/docker
echo "Linux image"
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-linux-env:latest -f $SCRIPT_DIR/docker/Dockerfile.linux-env $SCRIPT_DIR/docker
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-linux-app:latest -f $SCRIPT_DIR/docker/Dockerfile.linux-app $SCRIPT_DIR/docker
echo "Android base image"
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-android:latest -f $SCRIPT_DIR/docker/Dockerfile.android $SCRIPT_DIR/docker
echo "Winsdk 2019"
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-winsdk-2019-env:latest -f $SCRIPT_DIR/docker/Dockerfile.winsdk.2019-env $SCRIPT_DIR/docker
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-winsdk-2019-app:latest -f $SCRIPT_DIR/docker/Dockerfile.winsdk.2019-app $SCRIPT_DIR/docker
echo "Winsdk 2022"
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-winsdk-2022-env:latest -f $SCRIPT_DIR/docker/Dockerfile.winsdk.2022-env $SCRIPT_DIR/docker
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-winsdk-2022-app:latest -f $SCRIPT_DIR/docker/Dockerfile.winsdk.2022-app $SCRIPT_DIR/docker
echo "Emsdk 2.0.11"
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-emsdk-2011-env:latest -f $SCRIPT_DIR/docker/Dockerfile.emsdk.2011-env $SCRIPT_DIR/docker
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-emsdk-2011-app:latest -f $SCRIPT_DIR/docker/Dockerfile.emsdk.2011-app $SCRIPT_DIR/docker
echo "Emsdk 3.1.55"
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-emsdk-3155-env:latest -f $SCRIPT_DIR/docker/Dockerfile.emsdk.3155-env $SCRIPT_DIR/docker
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-emsdk-3155-app:latest -f $SCRIPT_DIR/docker/Dockerfile.emsdk.3155-app $SCRIPT_DIR/docker
echo "Android ndk 25"
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-android-ndk25-env:latest -f $SCRIPT_DIR/docker/Dockerfile.android.ndk25-env $SCRIPT_DIR/docker
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-android-ndk25-app:latest -f $SCRIPT_DIR/docker/Dockerfile.android.ndk25-app $SCRIPT_DIR/docker

echo "Ps4 images"
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-ps4-10500-env:latest -f $SCRIPT_DIR/docker/Dockerfile.ps4.10500-env $SCRIPT_DIR/docker
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-ps4-10500-app:latest -f $SCRIPT_DIR/docker/Dockerfile.ps4.10500-app $SCRIPT_DIR/docker
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-ps4-11000-env:latest -f $SCRIPT_DIR/docker/Dockerfile.ps4.11000-env $SCRIPT_DIR/docker
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-ps4-11000-app:latest -f $SCRIPT_DIR/docker/Dockerfile.ps4.11000-app $SCRIPT_DIR/docker
echo "Ps5 images"
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-ps5-8000-env:latest -f $SCRIPT_DIR/docker/Dockerfile.ps5.8000-env $SCRIPT_DIR/docker
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-ps5-8000-app:latest -f $SCRIPT_DIR/docker/Dockerfile.ps5.8000-app $SCRIPT_DIR/docker
echo "Nssdk images"
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-nssdk-1532-env:latest -f $SCRIPT_DIR/docker/Dockerfile.nssdk.1532-env $SCRIPT_DIR/docker
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-nssdk-1532-app:latest -f $SCRIPT_DIR/docker/Dockerfile.nssdk.1532-app $SCRIPT_DIR/docker
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-nssdk-1753-env:latest -f $SCRIPT_DIR/docker/Dockerfile.nssdk.1753-env $SCRIPT_DIR/docker
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-nssdk-1753-app:latest -f $SCRIPT_DIR/docker/Dockerfile.nssdk.1753-app $SCRIPT_DIR/docker

echo "Base app image"
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-base-app:latest -f $SCRIPT_DIR/docker/Dockerfile.base-app $SCRIPT_DIR/docker