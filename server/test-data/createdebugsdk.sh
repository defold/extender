
# not really a sha, but the name of the folder
SHA1=debugsdk

# defold> ./scripts/build.py build_platform_sdk --platform js-web
# Wrote /var/folders/5s/ll5cfsq52p5516swlrjrmv540000gp/T/tmpEBfCxg
PLATFORM=js-web

function create_zip {
	local platform=$1
	local target=$2

	pushd $DYNAMO_HOME/../..

	#./scripts/build.py build_platform_sdk --platform $platform > _log.txt

	#result=`cat _log.txt | grep -e Wrote`
	result="Wrote /var/folders/5s/ll5cfsq52p5516swlrjrmv540000gp/T/tmp0FHQN_"

	rm _log.txt

	popd

	result=`echo $result | sed s/Wrote\ //`

	echo RESULT: $result

	unzip $result -d $target
}

create_zip js-web sdk/$SHA1