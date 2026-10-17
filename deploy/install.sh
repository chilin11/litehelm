#!/bin/sh
# Offline source-tree convenience wrapper.
set -eu
base=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
exec sh "$base/install.sh" --local-dir "$base/dist" "$@"
