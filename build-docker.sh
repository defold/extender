#! /bin/sh
set -e
./gradlew :server:build -xtest
./gradlew :manifestmergetool:mainJar

# # copy jars
cp server/build/libs/extender-0.1.0.jar server/docker
cp server/manifestmergetool/build/libs/manifestmergetool-0.1.0.jar server/docker

# # copy users
cp -r server/users server/docker

# base images
echo "Base image"
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-base:latest -f $(pwd)/server/docker/Dockerfile.base $(pwd)/server/docker
echo "Wine base image"
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-wine:latest -f $(pwd)/server/docker/Dockerfile.wine $(pwd)/server/docker
echo "Linux image"
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-linux-env:latest -f $(pwd)/server/docker/Dockerfile.linux-env $(pwd)/server/docker
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-linux-app:latest -f $(pwd)/server/docker/Dockerfile.linux-app $(pwd)/server/docker
echo "Android base image"
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-android:latest -f $(pwd)/server/docker/Dockerfile.android $(pwd)/server/docker
echo "Winsdk 2019"
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-winsdk-2019-env:latest -f $(pwd)/server/docker/Dockerfile.winsdk.2019-env $(pwd)/server/docker
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-winsdk-2019-app:latest -f $(pwd)/server/docker/Dockerfile.winsdk.2019-app $(pwd)/server/docker
echo "Winsdk 2022"
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-winsdk-2022-env:latest -f $(pwd)/server/docker/Dockerfile.winsdk.2022-env $(pwd)/server/docker
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-winsdk-2022-app:latest -f $(pwd)/server/docker/Dockerfile.winsdk.2022-app $(pwd)/server/docker
echo "Emsdk 2.0.11"
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-emsdk-2011-env:latest -f $(pwd)/server/docker/Dockerfile.emsdk.2011-env $(pwd)/server/docker
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-emsdk-2011-app:latest -f $(pwd)/server/docker/Dockerfile.emsdk.2011-app $(pwd)/server/docker
echo "Emsdk 3.1.55"
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-emsdk-3155-env:latest -f $(pwd)/server/docker/Dockerfile.emsdk.3155-env $(pwd)/server/docker
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-emsdk-3155-app:latest -f $(pwd)/server/docker/Dockerfile.emsdk.3155-app $(pwd)/server/docker
echo "Android ndk 25"
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-android-ndk25-env:latest -f $(pwd)/server/docker/Dockerfile.android.ndk25-env $(pwd)/server/docker
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-android-ndk25-app:latest -f $(pwd)/server/docker/Dockerfile.android.ndk25-app $(pwd)/server/docker

echo "Ps4 images"
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-ps4-10500-env:latest -f $(pwd)/server/docker/Dockerfile.ps4.10500-env $(pwd)/server/docker
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-ps4-10500-app:latest -f $(pwd)/server/docker/Dockerfile.ps4.10500-app $(pwd)/server/docker
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-ps4-11000-env:latest -f $(pwd)/server/docker/Dockerfile.ps4.11000-env $(pwd)/server/docker
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-ps4-11000-app:latest -f $(pwd)/server/docker/Dockerfile.ps4.11000-app $(pwd)/server/docker
echo "Ps5 images"
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-ps5-8000-env:latest -f $(pwd)/server/docker/Dockerfile.ps5.8000-env $(pwd)/server/docker
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-ps5-8000-app:latest -f $(pwd)/server/docker/Dockerfile.ps5.8000-app $(pwd)/server/docker
echo "Nssdk images"
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-nssdk-1532-env:latest -f $(pwd)/server/docker/Dockerfile.nssdk.1532-env $(pwd)/server/docker
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-nssdk-1532-app:latest -f $(pwd)/server/docker/Dockerfile.nssdk.1532-app $(pwd)/server/docker
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-nssdk-1753-env:latest -f $(pwd)/server/docker/Dockerfile.nssdk.1753-env $(pwd)/server/docker
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-nssdk-1753-app:latest -f $(pwd)/server/docker/Dockerfile.nssdk.1753-app $(pwd)/server/docker

echo "Base app image"
docker build --build-arg DM_PACKAGES_URL=$DM_PACKAGE_URL --platform linux/amd64 -t defold/extender-base-app:latest -f $(pwd)/server/docker/Dockerfile.base-app $(pwd)/server/docker