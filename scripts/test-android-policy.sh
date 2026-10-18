#!/bin/sh
set -eu
ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
TEST_CLASSES=$(mktemp -d "${TMPDIR:-/tmp}/litehelm-policy.XXXXXX")
trap 'rm -rf "$TEST_CLASSES"' EXIT HUP INT TERM
javac -encoding UTF-8 -d "$TEST_CLASSES" \
  "$ROOT/android/app/src/main/java/com/litehelm/mobile/RequestPolicy.java" \
  "$ROOT/android/app/src/main/java/com/litehelm/mobile/LogFilter.java" \
  "$ROOT/android/app/src/main/java/com/litehelm/mobile/JobOutput.java" \
  "$ROOT/android/tests/HostTests.java"
java -cp "$TEST_CLASSES" com.litehelm.mobile.HostTests
