#!/bin/sh
set -eu
base=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
cd "$base/server"
mkdir -p "$base/dist"
version_flag=""
if [ -n "${VERSION:-}" ]; then
 version_flag="-X main.version=$VERSION"
elif [ -d "$base/.git" ] && command -v git >/dev/null; then
 git_ver=$(git describe --tags --always --dirty 2>/dev/null || true)
 if [ -n "$git_ver" ]; then version_flag="-X main.version=$git_ver"; fi
fi
for arch in amd64 arm64 arm; do
 name=$arch
 if [ "$arch" = arm ]; then name=armv7; fi
 CGO_ENABLED=0 GOOS=linux GOARCH="$arch" GOARM=7 go build -trimpath -ldflags="-s -w $version_flag" -o "$base/dist/litehelm-linux-$name" .
done
