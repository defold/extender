# Extender

Extender is a build server that builds native extensions of the Defold engine. The build server can either by run using Docker or as a standalone server running on macOS.

* Server description and setup/run instructions - [link](/server/README.md)
* Debugging FAQ - [link](/README_DEBUGGING.md)

---

## Running as a stand-alone server on macOS
The stand-alone server is currently used on a machine runing macOS. The server is used to build darwin targets (macOS+iOS) using the Apple tools (XCode+Apple Clang). It is also possible to use this setup when developing on macOS.

### Prerequisites
Ensure that on host is installed:
* brew
* curl

Before running Extender you need to have prepackaged toolchains and sdks. Full instruction how it can be done you can find [here](https://github.com/defold/defold/tree/dev/scripts/package).

Ensure that you have the following tools packaged:
* iPhoneOS18.2.sdk
* iPhoneSimulator18.2.sdk
* MacOSX15.2.sdk
* XcodeDefault16.2.xctoolchain.darwin

NOTE: Complete list of needed packages see [link](./server/scripts/standalone/setup-standalone-env.sh)

After obtain packages with sdks and toolchains you make it available via HTTP. The easiest way to do these is to run Python HTTP server on local machine. For example,
```sh
    cd <path_where_packages_located>
    python -m http.server
```
It starts local web server that available at `http://localhost:8000`.

Setup all needed packages via
```sh
    DM_PACKAGES_URL=<url_where_package_located> ./server/scripts/standalone/setup-standalone-env.sh
```
If you run local HTTP server from previous step replace `<url_where_package_located>` with `http://localhost:8000`.
It's download packages, unpack it to correct folder and generate .env file with correct pathes.

### Local Extender's application
There are two ways to obtain Extender's jars:
1. Download ready-to-use jars from public repo. See instructions [here](https://defold.com/manuals/extender-local-setup/#how-to-run-local-extender-with-preconfigured-artifacts) (step 5).
2. Build jars locally. See instruction [here](./server/README.md#how-to-build-extenders-applications).

As result you should have 2 jars in `./server/apps/` folder: extender.jar and manifestmergetool.jar.

### Run
To run stand-alone Extender instance use folowing script:
```sh
    ./server/scripts/standalone/service-standalone.sh start
```
Script takes commad as 1st argument. Command can be one of the following:
* **start** - start new Extender instance
* **stop** - stop already running Extender instance
* **restart** - stop and start Extender instance from the scratch

If you want to use different Spring profile you can pass it via 2nd argument. For example,
```sh
    ./server/scripts/standalone/service-standalone.sh start standalone-dev
```

Note: all Spring profiles should be located in `./server/configs/` folder.

As a result Extender should start and start listening 9010 port (that port set by default). E.g. Extender can be reached via `http://localhost:9010`.
Logs of extender can be found in `./server/app/logs/` folder.

If you want to use other port (not 9010) you can change property `server.port` in `./server/configs/application-standalone-dev.yml`.

### Show the output

The output can be found in `./server/app/logs/stdout.log`

To show the log as it updates:

```sh
    tail -F server/app/logs/stdout.log
```



### Develop/debug standalone Extender using VSCode
Note that [Prerequisites](#prerequisites) should be completed and manifestmergetool.jar already downloaded or built.

1. Download VSCode: https://code.visualstudio.com/download
2. Open folder with Extender sources in VSCode.
3. Install following extensions:
   1. Spring Boot Extension Pack https://marketplace.visualstudio.com/items?itemName=vmware.vscode-boot-dev-pack
   2. Spring Boot Dashboard https://marketplace.visualstudio.com/items?itemName=vscjava.vscode-spring-boot-dashboard
   3. Language Support for Java(TM) by Red Hat https://marketplace.visualstudio.com/items?itemName=redhat.java
   4. Gradle for Java https://marketplace.visualstudio.com/items?itemName=vscjava.vscode-gradle
   5. Debugger for Java https://marketplace.visualstudio.com/items?itemName=vscjava.vscode-java-debug. Note: version should be v0.58.2024090204 (pre-release) or greater.
4. Select `Spring Boot-ExtenderApplication<server>` in dropdown and start debug session.

### Develop/debug standalone Extender using IntelliJ IDEA
Note that [Prerequisites](#prerequisites) should be completed and manifestmergetool.jar already downloaded or built.

1. Download IntelliJ IDEA: https://www.jetbrains.com/idea/download
2. Run IntelliJ IDEA and open folder with Extender's sources from the welcome screen.
3. Select `Run standalone Extender` in `Run configurations` dropdown and start debug session.
