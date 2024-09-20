# Server part
Server part consists of set of Docker containers and standalone instances. The most common setup is:
* **frontend instance** that runs in empty environment. Handle all incoming requests and redirects it to remote instances according to platfrom.sdks.json mappings that instance download for engine version. Also handle all access checks.
* **remote instances**. One instance per platform and platform sdk version. Instance has necessary environment and produce engine builds.
* **standalone instances**. It also can be any builder for any platform. Recommended for osx/ios builder instances because there are no ready-to-use environment to run on MacOS host. So you need to setup all environment manually and deploy extender's jars to host and run it.

Extender consists of two parts:
* **app.jar** - the main jar contains all functionality to work with network requests, making engine builds.
* **manifestmerger.jar** - standalone jar used by app.jar to do platform manifest merging (like merging of *AndroidManifest.xml*, *Info.plist*, *PrivacyInfo.xcprivacy* manifests).

# Docker images
Extender instance should run in preconfigured environment (where necessary SDKs, toolchains, environment variables are confgured). For all platforms (except MacOS/iOS) exist Docker images where everything is done.

## How to use ready-to-use Docker images
1. Make sure that you have access to Defold Artifact registries.
2. Authorize to Google Cloud and create Application default credentials
   ```sh
   gcloud auth application-default login
   ```
3. Configure Docker to use Artifact registries
   ```sh
   gcloud auth configure-docker europe-west1-docker.pkg.dev
   ```
4. Check that everything set up correctly by pulling base image. Run
   ```sh
   docker pull europe-west1-docker.pkg.dev/extender-426409/extender-public-registry/extender-base-env:latest
   ```

## How to build docker images locally
In repository provided Dockerfiles for currently support by Defold platforms. All Dockerfiles can be found in `docker` folder.
Dockerfile named according to platform and sdk version that used inside. For example, `Dockerfile.emsdk-2011` means that inside Emscripten sdk 2.0.11 will be installed.
Dockerfiles contains only environment settings (e.g. no app.jar/manifestmerger.jar inside).

All available docker images can be build via (can be called from the root of repository)
```sh
DM_PACKAGE_URL=<url_to_packages> ./server/build-docker.sh
```
It's mandatory to provide `DM_PACKAGE_URL` variable. Otherwise most of containers cannot be built. `DM_PACKAGE_URL` variable should contains url to place where prebuilt platform sdks are located.

If you don't have all platform sdks (for example, you don't have consoles sdks) you can customize `build-docker.sh` script and remove all unused parts.
By default all built images tagged with `latest` version.

## How to add new Docker image with new environment
1. Place new Dockerfile in `server/docker` folder. Docker file should have name in format `Dockerfile.<platform>[.<version>]-env` (version can be optional if Dockerfile contains some common stuff for other images).
2. Depends on plaform choose right base image (for `FROM` instruction):
   1. `europe-west1-docker.pkg.dev/extender-426409/extender-public-registry/extender-android-env` for any **Android-based** images
   2. `europe-west1-docker.pkg.dev/extender-426409/extender-public-registry/extender-wine-env` for any **Windows-based** images
   3. `europe-west1-docker.pkg.dev/extender-426409/extender-public-registry/extender-base-env` for the rest of cases
3. Set exact version of base image in `FROM` instruction.
4. Add appropriate command to `server/build-docker.sh` script.
5. Run `server/build-docker.sh` and check that everything is built correctly.

# How to build Extender's applications
```sh
./gradlew server:bootJar
./gradlew manifestmergetool:mainJar
```
That two commands produce 2 jars that will be placed in `server/app` folder. It's ready to use jars without any additional external dependencies.

## How to run locally
Build Extender's jars (called from root directory):
```sh
./gradlew server:bootJar
./gradlew manifestmergetool:mainJar
```
Main command
```sh
docker compose -p extender -f docker/docker-compose.yml --profile <profile> up
```
where *profile* can be:
* **all** - runs remote instances for every platform
* **android** - runs frontend instance + remote instances to build Android version
* **web** - runs frontend instance + remote instances to build Web version
* **linux** - runs frontend instance + remote instances to build Linux version
* **windows** - runs frontend instance + remote instances to build Windows version
* **consoles** - runs frontend instance + remote instances to build Nintendo Switch/PS4/PS5 versions
* **nintendo** - runs frontend instance + remote instances to build Nintendo Switch version
* **playstation** - runs frontend instance + remote instances to build PS4/PS5 versions
* **metrics** - runs VictoriaMetrics + Grafana as metrics backend and tool for visualization

Several profiles can be passed to command line. For example:
```sh
docker compose -p extender -f docker/docker-compose.yml --profile android --profile web --profile windows up
```
Example above runs frontend, Android, Web, Windows instances.

To stop services - Press Ctrl+C if docker compose runs in non-detached mode, or 
```sh
docker compose -p extender down
```
if docker compose was run in detached mode (e.g. '-d' flag was passed to `docker compose up` command).

## How to run tests
Test can be run from the root directory with
```sh
./gradlew server:test
```
During the testing local servers will be run. That's why it necessary to have prebuild docker images.
There are two set of services that run:
* **test** - run for integration testing (see *IntegrationTest.java*)
* **auth-test** - run for authentication testing (see *AuthenticationTest.java*)

## How to debug running instance
To enable remote JVM debug need to add following additional options to entrypoint section in `./server/docker/common-services.yml` for `common_builder` service
```
-Xdebug -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
```
Additionally in `./server/docker/docker-compose.yml` modify `expose` and `ports` of interesting service in following way:
```
    expose:
      - "5005"
    ports:
      - "5005:5005"
```
Rerun docker compose. After that you can attach to running JVM process via `localhost:5005`. For more instructions how to setup remote java debugging read documentation for your IDE. For example:
* IntelliJ - https://medium.com/@klymok.nazariy/how-to-remotely-debug-spring-boot-application-in-docker-compose-in-intellij-e9e4422dafce
* VSCode - https://code.visualstudio.com/docs/java/java-debugging

## How to release new application's versions
To release new extender application version simply need to create an new git tag
```sh
git tag -a extender-v1.0.0 -m "Initial extender release."
```
and push it to remote repository
```sh
git push origin extender-v1.0.0
```
It triggres appropriate Github workflow that build jar, test, and upload to private Maven repository.

Tags should be in the following format:
* **extender-vX.X.X** - for Extender jar. For example, extender-v2.0.0
* **manifestmergetool-vX.X.X** - for manifest merge tool. For example, manifestmergetool-v1.5.0

For more details see [application workflow](../.github/workflows/application-build.yml).

## How to change and release new version of Docker image
As an example show stepp on Android NDK25 Docker image.
1. Make changes in `server/docker/Dockerfile.android.ndk25-env` file and save it.
2. Test locally that everything build correctly. Run
   ```sh
   ./server/build-docker.sh
   ```
   or single docker build command 
   ```sh
   DM_PACKAGES_URL=<URL_TO_PACKAGES> docker buildx build --secret id=DM_PACKAGES_URL --platform linux/amd64 -t europe-west1-docker.pkg.dev/extender-426409/extender-public-registry/extender-android-env:latest -f ./server/docker/Dockerfile.android-env ./server/docker
   ```
3. Create new git tag according to name convention:
   `<platform>[.<sdk_version>]-<version>`
   For example, `android.ndk25-1.0.0`. Base part of th tag name should be the same as the middle part of Dockerfile name, e.g. if Dockerfile has name `Dockerfile.XXXX-env` than tag should have name `XXXX-<version>`.
   ```sh
   git tag -a android.ndk25-1.0.0 -m "Initial Android NDK25 Docker image."
   ```
4. Push tag to repository
   ```sh
   git push origin android.ndk25-1.0.0
   ```
Tag push triggres Github workflow that build and push to private Docker registry new Docker image. For more details, see [docker workflow](../.github/workflows/docker-env-build.yml).

In case if new version of base image will be release do not forget to update tag version in `.server/build-socker.sh`.

# Deployment notes

## How to configure remote hosts
Frontend instance should run with configs that contains urls to remote hosts. For example, see `server/configs/application-local-dev-app.yml`.
Keys in `extender.remote-builder.platform` should be formed in following way: `<platform_name>-<sdk_version>`. That mappings must have the same names as used in https://github.com/defold/defold/blob/generate-platform-sdks-mappings/share/platform.sdks.json. How it works.
1. Frontend instance get a request.
2. By engine sha1 version instance downloads `platform.sdks.json`.
3. Instance looks into `platform.sdks.json` and found information according to requested platform. For example, user try to build engine for platform `js-web`. In that case frontend instance found `["emsdk", "3155"]`.
4. Frontend instance search through `extender.remote-builder.platforms` two keys: `<platform>-<sdk_version>` and `<platform>-latest`. If no mappings was found - frontend instance starts local build (which highly likely will fail because no appropriate environment was configured). For our example frontend instance search for `emsdk-3155` and `emsdk-latest`. 
5. For find url frontend instance start remote build.
