#!/usr/bin/env bash
# Only non-destructive platform tests. Death/SAF/biometric/manual ARM64 app cases are not silently claimed here.
set -euo pipefail
mkdir -p build/ci-device
capture() {
  adb logcat -d -v threadtime > build/ci-device/logcat.txt 2>&1 || true
  adb shell getprop > build/ci-device/device-properties.txt 2>&1 || true
}
trap capture EXIT
adb wait-for-device
./gradlew :platform:storage:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.deepstudent.platform.storage.DataSpaceAndroidTest --stacktrace
./gradlew :platform:network:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.deepstudent.platform.network.NetworkAndroidTest --stacktrace
./gradlew :platform:security:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.deepstudent.platform.security.AndroidKeystoreDeviceTest --stacktrace
node tools/ci/check-device-results.mjs
