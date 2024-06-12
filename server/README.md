# Server part
Server part consists of set of Docker containers and standalone instances. The most common setup is:
* **frontend instance** that runs in empty environment. Handle all incoming requests and redirects it to remote instances according to platfrom.sdks.json mappings that instance download for engine version. Also handle all access checks.
* **remote instances**. One instance per platfrom per platform sdk version. Instance has necessary environment and produce engine builds.
* **standalone instances**. It also can be any builder for any platform. Recommended for osx/ios builder instances because there are no ready-to-use environment to run on MacOS host. So you need to setup all environment manually and deploy extender's jars to host and run it.

Extender consists of two parts:
* **app.jar** - the main jar contains all functionality to work with network requests, making engine builds.
* **manifestmerger.jar** - standalone jar used by app.jar to do platform manifest merging (like merging of *AndroidManifest.xml*, *Info.plist*, *PrivacyInfo.xcprivacy* manifests).

## How to build
In repository provided Dockerfiles for currently support by Defold platforms. All Dockerfiles can be found in `docker` folder.
Dockerfile named according to platform and sdk version that used inside. For example, `Dockerfile.emsdk-2011` means that inside Emscripten sdk 2.0.11 will be installed.
Also Dockerfiles divided into two group: with postfix `-env` and `-app`. Postfix `-env` means that docker image will contain only configured environment without any application (e.g. no app.jar/manifestmerger.jar inside). Postfix `-app` means that docker image contains all application code and initial configuration for entrypoint; based on `-env` docker image for the same platform+version.

All available docker images can be build via (can be called from the root of repository)
```sh
DM_PACKAGE_URL=<url_to_packages> ./server/build-docker.sh
```
It's mandatory to provide `DM_PACKAGE_URL` variable. Otherwise most of containers cannot be built. `DM_PACKAGE_URL` variable should contain url to palce where prebuilt platform sdks are located.

If you don't have all platform sdks (for example, you don't have consoles sdks) you can customize `build-docker.sh` script and remove all unused parts.
By default all built images tagged with `latest` version.

## How to run locally
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
Test can be run from `server` directory with
```sh
./../gradlew test
```
During the testing local servers will be run. That's why it necessary to have prebuild docker images.
There are two set of services that run:
* test - run for integration testing (see IntegrationTest.java)
* auth-test - run for authentication testing (see AuthenticationTest.java)

## Deployment notes

### How to configure remote hosts
Frontend instance should run with configs that contains urls to remote hosts. For example, see `server/configs/application-local-dev-app.yml`.
Keys in `extender.remote-builder.platform` should be formed in following way: `<platform_name>-<sdk_version>`. That mappings must have the same names as used in https://github.com/defold/defold/blob/generate-platform-sdks-mappings/share/platform.sdks.json. How it works.
1. Frontend instance get a request.
2. By engine sha1 version instance downloads `platform.sdks.json`.
3. Instance looks into `platform.sdks.json` and found information according to requested platform. For example, user try to build engine for platform `js-web`. In that case frontend instance found `["emsdk", "3155"]`.
4. Frontend instance search through `extender.remote-builder.platforms` two keys: `<platform>-<sdk_version>` and `<platform>-latest`. If no mappings was found - frontend instance starts local build (which highly likely will fail because no appropriate environment was configured). For our example frontend instance search for `emsdk-3155` and `emsdk-latest`. 
5. For find url frontend instance start remote build.