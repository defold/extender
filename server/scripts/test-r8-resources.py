#!/usr/bin/env python3
"""Build D8/R8 Defold apps, verify final APK/AAB resources, and optionally run an Android probe."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import time
import zipfile


FIXTURE = Path(__file__).resolve().parents[1] / 'test-data' / 'r8-resources'
KEPT = ['res/layout/used_layout.xml', 'res/raw/code_kept.bin', 'res/raw/dynamic_kept.bin']
REMOVED = ['res/layout/unused_layout.xml', 'res/raw/dead_code.bin', 'res/raw/unused.bin']


def run(command, **kwargs):
    return subprocess.run([str(arg) for arg in command], check=True, text=True, **kwargs)


def write(root, name, text):
    file = root / name
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(text)


def create_project(root, bob, mode):
    root.mkdir(parents=True, exist_ok=True)
    rules = '/app.keep' if mode == 'r8' else ''
    write(root, 'game.project', f'''[project]
title = R8Resources

[bootstrap]
main_collection = /main.collectionc

[android]
package = com.defold.r8resources.{mode}
r8_keep_rules = {rules}
''')
    write(root, 'main.collection', 'name: "main"\n')
    write(root, 'input/game.input_binding', '')
    with zipfile.ZipFile(bob) as archive:
        write(root, 'app.keep', archive.read('builtins/manifests/android/dmengine.keep').decode())
    write(root, 'fixture/ext.manifest', '''name: R8Resources
platforms:
    android:
        context:
            aaptExtraPackages: [com.defold.r8resources]
''')
    write(root, 'fixture/manifests/android/fixture.keep', '# Manifest and live XML references define the Java roots for this fixture.\n')
    write(root, 'fixture/src/extension.cpp', '''#include <dmsdk/sdk.h>

static dmExtension::Result Initialize(dmExtension::Params*) {
    return dmExtension::RESULT_OK;
}

DM_DECLARE_EXTENSION(R8Resources, "R8Resources", 0, 0, Initialize, 0, 0, 0)
''')
    write(root, 'fixture/manifests/android/AndroidManifest.xml', '''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.defold.r8resources">
    <application>
        <activity android:name="com.defold.r8resources.ProbeActivity" android:exported="true" android:label="@string/manifest_kept" />
    </application>
</manifest>
''')
    shutil.copytree(FIXTURE / 'res', root / 'fixture/res/android/res', dirs_exist_ok=True)
    for name in ['XmlView', 'UnusedView', 'DeadCode']:
        shutil.copyfile(FIXTURE / ('java/com/defold/r8resources/' + name + '.java'), root / ('fixture/src/' + name + '.java'))
    write(root, 'fixture/src/ProbeActivity.java', '''package com.defold.r8resources;

public class ProbeActivity extends android.app.Activity {
    @Override
    public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        try {
            android.content.res.Resources resources = getResources();
            android.view.View view = getLayoutInflater().inflate(R.layout.used_layout, null);
            // The custom view class is referenced only by the layout XML.
            if (!"Live XML reference".equals(view.getTag())) {
                throw new IllegalStateException("XML-only view or string missing");
            }
            if (!"Live code reference".equals(getString(R.string.code_kept))) {
                throw new IllegalStateException("Code-referenced string missing");
            }
            String dynamicName = getIntent().getStringExtra("resource");
            int dynamicId = resources.getIdentifier(dynamicName, "raw", getPackageName());
            if (dynamicId == 0) {
                throw new IllegalStateException("tools:keep resource missing: " + dynamicName);
            }
            try (java.io.InputStream code = resources.openRawResource(R.raw.code_kept);
                 java.io.InputStream dynamic = resources.openRawResource(dynamicId)) {
                if (code.available() != 256 || dynamic.available() != 512) {
                    throw new IllegalStateException("Resource payload changed");
                }
            }
            String result = "RESOURCE_SHRINKING_OK:" + getIntent().getStringExtra("token");
            android.util.Log.i("DefoldR8ResourceTest", result);
            android.widget.TextView status = new android.widget.TextView(this);
            status.setText(result);
            setContentView(status);
        } catch (Exception error) {
            throw new RuntimeException("Android resource regression", error);
        }
    }
}
''')


def verify_archive(path, mode, prefix, table):
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        for name in KEPT:
            assert prefix + name in names, (path, 'missing', name)
        for name in REMOVED:
            assert (prefix + name in names) == (mode == 'd8'), (path, 'unexpected resource', name)
        if table.endswith('.pb'):
            data = archive.read(prefix + table)
            for name in ['manifest_kept', 'xml_kept', 'code_kept']:
                assert name.encode() in data, (path, 'missing string', name)
            for name in ['unused_string', 'dead_code_string']:
                assert (name.encode() in data) == (mode == 'd8'), (path, 'unexpected string', name)
        resource_bytes = sum(entry.file_size for entry in archive.infolist()
                             if entry.filename.startswith(prefix + 'res/') or entry.filename == prefix + table)
        return {'file': str(path), 'archive_bytes': path.stat().st_size, 'resource_bytes': resource_bytes}


def verify_runtime(adb, device, apk, mode):
    command = [adb, '-s', device]
    run(command + ['install', '-r', str(apk)], capture_output=True)
    token = mode + '-' + str(time.time_ns())
    component = 'com.defold.r8resources.' + mode + '/com.defold.r8resources.ProbeActivity'
    run(command + ['shell', 'am', 'start', '-S', '-W', '--activity-clear-task', '-n', component, '--es', 'resource', 'dynamic_kept', '--es', 'token', token], capture_output=True)
    expected = 'RESOURCE_SHRINKING_OK:' + token
    for _ in range(30):
        logs = run(command + ['logcat', '-d', '-s', 'DefoldR8ResourceTest:I', '*:S'], capture_output=True).stdout
        if expected in logs:
            return expected
        time.sleep(1)
    crashes = run(command + ['logcat', '-d', '-b', 'crash', '-t', '100'], capture_output=True).stdout
    raise RuntimeError('Android probe did not report success: ' + expected + '\n' + crashes)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bob', type=Path, required=True)
    parser.add_argument('--build-server', required=True)
    parser.add_argument('--defoldsdk', required=True)
    parser.add_argument('--work-dir', type=Path, required=True)
    parser.add_argument('--aapt2', required=True)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--device')
    parser.add_argument('--skip-build', action='store_true', help='Recheck artifacts already built in work-dir')
    args = parser.parse_args()
    args.bob = args.bob.resolve()
    args.work_dir = args.work_dir.resolve()
    report = {}
    for mode in ['d8', 'r8']:
        project = args.work_dir / mode
        if not args.skip_build:
            create_project(project, args.bob, mode)
            print('Building ' + mode + ' with ' + args.build_server, flush=True)
            with (project / 'bob.log').open('w') as log:
                run(['java', '-jar', args.bob, '--root', project, '--platform', 'arm64-android',
                     '--architectures', 'arm64-android', '--variant', 'release',
                     '--build-server', args.build_server, '--defoldsdk', args.defoldsdk,
                     '--bundle-output', project / 'bundle', '--bundle-format', 'apk,aab',
                     'resolve', 'build', '--archive', 'bundle'], stdout=log, stderr=subprocess.STDOUT)
        apk = project / 'bundle/R8Resources/R8Resources.apk'
        aab = apk.with_suffix('.aab')
        linked = project / 'build/arm64-android/compiledresources.apk'
        result = {
            'compiledresources': verify_archive(linked, mode, '', 'resources.pb'),
            'apk': verify_archive(apk, mode, '', 'resources.arsc'),
            'aab': verify_archive(aab, mode, 'base/', 'resources.pb'),
        }
        dump = run([args.aapt2, 'dump', 'resources', apk], capture_output=True).stdout
        (project / 'apk-resources.txt').write_text(dump)
        for name in ['manifest_kept', 'xml_kept', 'code_kept']:
            assert 'string/' + name in dump, (apk, 'missing', name)
        for name in ['unused_string', 'dead_code_string']:
            assert ('string/' + name in dump) == (mode == 'd8'), (apk, 'unexpected', name)
        if args.device:
            result['runtime'] = verify_runtime(args.adb, args.device, apk, mode)
        report[mode] = result
        print(json.dumps({mode: result}, indent=2), flush=True)
    for artifact in ['compiledresources', 'apk', 'aab']:
        assert report['r8'][artifact]['resource_bytes'] < report['d8'][artifact]['resource_bytes'], artifact
    (args.work_dir / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print('Verified resource shrinking in compiledresources.apk, final APK, and final AAB.', flush=True)


if __name__ == '__main__':
    main()
