#!/bin/sh
set -eu
base=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
cd "$base/server"
mkdir -p "$base/dist"
for arch in amd64 arm64 arm; do
 name=$arch
 if [ "$arch" = arm ]; then name=armv7; fi
 CGO_ENABLED=0 GOOS=linux GOARCH="$arch" GOARM=7 go build -trimpath -ldflags='-s -w' -o "$base/dist/litehelm-linux-$name" .
done
