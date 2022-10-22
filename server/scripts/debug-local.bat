@echo off

docker ps -f name=extender -q > container.txt
set /P CONTAINER=<container.txt
del container.txt

echo Found container %CONTAINER%
docker exec -uextender -it %CONTAINER% /bin/bash
