#! /bin/sh
zip -r test_sdk.zip defoldsdk/
printf '%s' "$(sha256 -q ./test_sdk.zip)" > test_sdk.sha256
printf '%s' "$(sha256 -q ./defoldsdk/sdk_file_1.yml)" > test_sdk_invalid.sha256
