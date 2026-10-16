#!/usr/bin/env bash
# Packs the launcher sources into a release asset, so a Mac host can build the launcher that
# matches a server jar without checking out the repository. Needs GNU tar (gtar on macOS).
#   package.sh <output.tar.gz>
set -euo pipefail

OUT=$1
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TAR=$(command -v gtar || command -v tar)

# Fixed order, owner and mtime keep the archive byte-identical for unchanged sources.
"$TAR" -C "$SCRIPT_DIR" \
    --sort=name --owner=0 --group=0 --numeric-owner --mtime='1970-01-01 00:00:00Z' \
    -cf - extender-sandbox-darwin.c selftest-darwin.sh README.md \
    | gzip -n -9 > "$OUT"
