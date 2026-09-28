# R8 Android resource regression fixture

`resources.ap_` is an AAPT2 proto archive, generated from `res/` and the manifest.
The Java fixture exercises live code references, removed-code references, and views
referenced only by used or unused layouts. `res/raw/keep.xml` protects a resource
that has no static code reference. The activity is a test root, not an installable app.

The checked-in archive and generated `R.java` let the regression test run without
an Android SDK. Regenerate them with build-tools 36.1.0:

```sh
python3 generate.py --aapt2 "$ANDROID_HOME/build-tools/36.1.0/aapt2" \
    --android-jar "$ANDROID_HOME/platforms/android-36/android.jar"
```

`aapt-generated.keep` is used only by the compatibility test for SDKs that optimize
code without resource shrinking. Feeding those rules to the integrated shrinker
would retain the otherwise unused XML view.

## Full APK/AAB and runtime verification

From the Extender repository root, point the test at a local Android builder using
an SDK with `r8ResourceShrinking: true` and the updated R8 command:

```sh
python3 server/scripts/test-r8-resources.py \
    --bob ../defold/com.dynamo.cr/com.dynamo.cr.bob/dist/bob.jar \
    --build-server http://127.0.0.1:9010 --defoldsdk <sdk-sha> \
    --work-dir /tmp/defold-r8-resources \
    --aapt2 "$ANDROID_HOME/build-tools/36.1.0/aapt2" \
    --adb "$ANDROID_HOME/platform-tools/adb" --device <adb-serial>
```

The test creates separate D8 and R8 projects, builds both APK and AAB, verifies
retained and removed resources in all three archives, and writes `report.json`
with resource sizes. With `--device`, it installs both APKs and verifies live code,
XML-only view inflation, and dynamically named resources protected by `tools:keep`.
The extension includes an explicit `.keep` file so that Extender does not apply
its compatibility fallback that keeps every extension class. The manifest and live
XML references therefore determine which fixture classes survive.
Use `--skip-build` to recheck existing artifacts. Bob logs are kept per project.
