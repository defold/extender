@echo off

docker ps -f name=extender -q > container.txt
set /P CONTAINER=<container.txt
del container.txt

set LOCAL_ENV=

if not "%DM_DEBUG_DISABLE_PROGUARD%" == ""  set LOCAL_ENV=-e DM_DEBUG_DISABLE_PROGUARD=%DM_DEBUG_DISABLE_PROGUARD% %LOCAL_ENV%
if not "%DM_DEBUG_COMMANDS%" == ""          set LOCAL_ENV=-e DM_DEBUG_COMMANDS=%DM_DEBUG_COMMANDS% %LOCAL_ENV%
if not "%DM_DEBUG_JOB_FOLDER%" == ""        set LOCAL_ENV=-e DM_DEBUG_JOB_FOLDER=%DM_DEBUG_JOB_FOLDER% %LOCAL_ENV%
if not "%DM_DEBUG_KEEP_JOB_FOLDER%" == ""   set LOCAL_ENV=-e DM_DEBUG_KEEP_JOB_FOLDER=%DM_DEBUG_KEEP_JOB_FOLDER% %LOCAL_ENV%
if not "%DM_DEBUG_JOB_UPLOAD%" == ""        set LOCAL_ENV=-e DM_DEBUG_JOB_UPLOAD=%DM_DEBUG_JOB_UPLOAD% %LOCAL_ENV%

echo "Using local env: '%LOCAL_ENV%'"

if "%DYNAMO_HOME%" == "" (
    echo "No DYNAMO_HOME"
    docker run --init --rm --name extender -p 9000:9000 -e SPRING_PROFILES_ACTIVE=dev %LOCAL_ENV% extender/extender
) else (
    echo "Using DYNAMO_HOME=%DYNAMO_HOME%"
    docker run --init --rm --name extender -p 9000:9000 -e SPRING_PROFILES_ACTIVE=dev %LOCAL_ENV% -v %DYNAMO_HOME%:/dynamo_home -e DYNAMO_HOME=/dynamo_home extender/extender
)
