#!/usr/bin/env python3
"""Regenerate the portable R8 resource fixture with Android SDK build-tools 36.1.0."""
import argparse
from pathlib import Path
import random
import subprocess
import tempfile
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--aapt2', required=True)
parser.add_argument('--android-jar', required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parent

def run(*command):
    subprocess.run([str(arg) for arg in command], cwd=root, check=True)

# Incompressible payloads make resource-size assertions independent of ZIP overhead.
rng = random.Random(13102)
for name, size in [('code_kept', 256), ('dynamic_kept', 512), ('dead_code', 4096), ('unused', 8192)]:
    (root / 'res' / 'raw' / (name + '.bin')).write_bytes(rng.randbytes(size))

with tempfile.TemporaryDirectory(prefix='r8-resources-') as temporary:
    temporary = Path(temporary)
    for resource in sorted((root / 'res').glob('*/*')):
        run(args.aapt2, 'compile', resource.relative_to(root), '-o', temporary)
    archive = temporary / 'linked.apk'
    run(args.aapt2, 'link', '--proto-format', '--non-final-ids', '--auto-add-overlay',
        '--manifest', 'AndroidManifest.xml', '-I', args.android_jar,
        '--java', 'java', '-o', archive, '--proguard', 'aapt-generated.keep',
        '-R', *sorted(temporary.glob('*.flat')))
    rules = root / 'aapt-generated.keep'
    rules.write_text(rules.read_text().rstrip() + '\n')
    # Normalize timestamps and order so the checked-in fixture is reproducible.
    with zipfile.ZipFile(archive) as source, zipfile.ZipFile(root / 'resources.ap_', 'w') as target:
        for name in sorted(source.namelist()):
            entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            target.writestr(entry, source.read(name))
