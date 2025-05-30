# Server part
Server part consists of a set of Docker containers and standalone instances. The most common setup is:

* **frontend instance** runs in an empty environment and handles all incoming requests and redirects them to remote instances according to `platfrom.sdks.json` mappings that the instance downloads for an engine version. Also handle all access checks.
* **remote instances** exist per platform and platform sdk version. The remote instance run preconfigured Docker images with the necessary environment and produce engine builds for the specific platform and sdk version.
* **standalone instances** are typically used for osx/ios builder instances running on a macOS host. A standalone instance does not use a Docker image and instead have the necessary environment and jar files installed directly on the host.

The extender consists of two parts:
* **app.jar** - the main jar contains all functionality to work with network requests and making engine builds.
* **manifestmerger.jar** - a standalone jar used by app.jar to do platform manifest merging (like merging of *AndroidManifest.xml*, *Info.plist*, *PrivacyInfo.xcprivacy* manifests).

# Docker images
Extender instance should run in preconfigured environment where necessary SDKs, toolchains, environment variables are confgured. For all platforms (except MacOS/iOS) there exists Docker images where everything is set up.

## How to use ready-to-use Docker images
1. Authorize to Google Cloud
   ```sh
   gcloud auth login
   ```
2. Configure Docker to use Artifact registries
   ```sh
   gcloud auth configure-docker europe-west1-docker.pkg.dev
   ```
3. Check that everything set up correctly by pulling base image. Run
   ```sh
   docker pull europe-west1-docker.pkg.dev/extender-426409/extender-public-registry/extender-base-env:latest
   ```

## How to build docker images locally
**IMPORTANT NOTE**: To build multi-platform images you need to switch your Docker installation to use containerd store. Here is official instructions how to do that:
* for Docker desktop - https://docs.docker.com/go/build-multi-platform/
* for Docker engine - https://docs.docker.com/engine/storage/containerd/

Dockerfiles for the currently supported platforms can be found in `server/docker`. Dockerfiles are named according to the platform and sdk version that is used inside. For example, `Dockerfile.emsdk-2011` means that inside Emscripten sdk 2.0.11 will be installed.
The Dockerfiles contain only environment settings (e.g. no app.jar/manifestmerger.jar inside).

All available docker images can be built using the following command, run from the root of this repository:
```sh
DM_PACKAGES_URL=<url_to_packages> ./server/build-docker.sh
```
It's mandatory to provide a `DM_PACKAGES_URL` variable. Otherwise most of containers cannot be built. `DM_PACKAGES_URL` variable should contains url to the location where prebuilt platform sdks are located.

If you don't have all platform sdks (for example, you don't have consoles sdks) you can customize `build-docker.sh` script and remove all unused parts.
By default all built images tagged with `latest` version.

By default for base and Linux docker images used 2 architectures: **linux/amd64** and **linux/arm64**. If you don't want to build arm64 image you can pass `NO_ARM64=1` as part of command to skip it. Example
```sh
NO_ARM64=1 DM_PACKAGES_URL=<url_to_packages> ./server/build-docker.sh
```

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
If you don't have locally built images - pull it from registry:
```sh
docker compose -p extender -f server/docker/docker-compose.yml --profile <profile> pull
```
where *profile* can be:
* **all** - runs remote instances for every platform
* **android** - runs frontend instance + remote instances to build Android version
* **web** - runs frontend instance + remote instances to build Web version
* **linux** - runs frontend instance + remote instances to build Linux version
* **windows** - runs frontend instance + remote instances to build Windows version
* **consoles** - runs frontend instance + remote instances to build Nintendo Switch/PS4/PS5 versions (you should have access to private registry)
* **nintendo** - runs frontend instance + remote instances to build Nintendo Switch version (you should have access to private registry)
* **playstation** - runs frontend instance + remote instances to build PS4/PS5 versions (you should have access to private registry)
* **metrics** - runs VictoriaMetrics + Grafana as metrics backend and tool for visualization
Main command
```sh
docker compose -p extender -f server/docker/docker-compose.yml --profile <profile> up --pull never
```

Several profiles can be passed to command line. For example:
```sh
docker compose -p extender -f server/docker/docker-compose.yml --profile android --profile web --profile windows up --pull never
```
Example above runs frontend, Android, Web, Windows instances.

To stop services - Press Ctrl+C if docker compose runs in non-detached mode, or 
```sh
docker compose -p extender down
```
if docker compose was run in detached mode (e.g. '-d' flag was passed to `docker compose up` command).

In case of usage `metrics` profile you also need to add `influx` profile to service command (see `./server/docker/common-service.yml` and `./server/docker/docker-compose.yml`). For example, for common remote builder definition should looks like (`./server/docker/common-service.yml`):
```yml
  remote_builder:
    extends: common_builder
    command: ["--spring.config.location=classpath:./,file:/etc/defold/extender/", "--spring.profiles.active=local-dev,metrics,influx${STRUCTURED_LOGGING+,logging}"]

```

## Structured logging
If you want to see logs in structured form you need to execute docker compose with `STRUCTURED_LOGGING=1` environment variable. For example
```sh
STRUCTURED_LOGGING=1 docker compose -f ./server/docker/docker-compose.yml --profile web up
```
For more information about structured logs: [link](https://newrelic.com/blog/how-to-relic/structured-logging)

## How to run tests
Test can be run from the root directory with
```sh
./gradlew server:test
```
During the testing local servers will be run. That's why it necessary to have prebuild docker images. There are two set of services that run:
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
To release new extender application version simply need to create an new git tag:
```sh
git tag -a extender-v1.0.0 -m "Initial extender release."
```
and push it to remote repository:
```sh
git push origin extender-v1.0.0
```
It triggers appropriate GitHub workflow that build jar, test, and upload to private Maven repository.

Tags should be in the following format:
* **extender-vX.X.X** - for Extender jar. For example, extender-v2.0.0
* **manifestmergetool-vX.X.X** - for manifest merge tool. For example, manifestmergetool-v1.5.0

For more details see [application workflow](../.github/workflows/application-build.yml).

## How to change and release new version of Docker image
As an example here is how to change Android NDK25 Docker image.
1. Make changes in `server/docker/Dockerfile.android.ndk25-env` file and save it.
2. Test locally that everything build correctly. Run
   ```sh
   ./server/build-docker.sh
   ```
   or single docker build command 
   ```sh
   DM_PACKAGES_URL=<URL_TO_PACKAGES> docker buildx build --secret id=DM_PACKAGES_URL --platform linux/amd64 -t europe-west1-docker.pkg.dev/extender-426409/extender-public-registry/extender-android-env:latest -f ./server/docker/Dockerfile.android-env ./server/docker
   ```
3. Create new git tag according to the following naming convention:
   `<platform>[.<sdk_version>]-<version>`
   For example, `android.ndk25-1.0.0`. Base part of the tag name should be the same as the middle part of Dockerfile name, e.g. if Dockerfile has name `Dockerfile.XXXX-env` than tag should have name `XXXX-<version>`.
   ```sh
   git tag -a android.ndk25-1.0.0 -m "Initial Android NDK25 Docker image."
   ```
4. Push tag to repository
   ```sh
   git push origin android.ndk25-1.0.0
   ```
Tag push triggers GitHub workflow that build and push the new Docker image to the Docker registry. For more details, see [docker workflow](../.github/workflows/docker-env-build.yml).

In case if new version of base image will be released do not forget to update tag version in `.server/build-socker.sh`.

# Deployment notes

## How to configure remote hosts
Frontend instance should run with configs that contains urls to remote hosts. For example, see `server/configs/application-local-dev-app.yml`.
Keys in `extender.remote-builder.platform` should be formed in the following way: `<platform_name>-<sdk_version>` and the mappings must have the same names as used in https://github.com/defold/defold/blob/generate-platform-sdks-mappings/share/platform.sdks.json. Here's how it works:

1. Frontend instance get a request. The request contains a sha1 matching an engine version and which platform to build.
2. Frontend instance downloads `platform.sdks.json` for the specified engine sha1.
3. Frontend instance looks into `platform.sdks.json` for information according to requested platform. For example, user try to build engine for platform `js-web`. In that case frontend instance found `["emsdk", "3155"]`.
4. Frontend instance search through `extender.remote-builder.platforms` using the keys: `<platform>-<sdk_version>` and `<platform>-latest`. If no mappings was found - frontend instance starts local build (which highly likely will fail because no appropriate environment was configured). For our example frontend instance search for `emsdk-3155` and `emsdk-latest`. 
5. Frontend instance sends a build request to the found server url.
