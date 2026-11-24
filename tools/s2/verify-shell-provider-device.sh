#!/usr/bin/env bash
set -euo pipefail

if (($# < 3 || $# > 5)); then
    echo 'usage: verify-shell-provider-device.sh SERIAL CANDIDATE_DIR OUTPUT_DIR [base|provision|APP|LINUX|SHIZUKU|ROOT|ROOT_UNAVAILABLE|cancel-LINUX|cancel-SHIZUKU|cancel-ROOT] [UID]' >&2
    exit 2
fi
serial=$1
candidate=$(realpath "$2")
out=$(realpath -m "$3")
phase=${4:-base}
uid=${5:-}
adb=${ADB:-${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb}
app=com.deepstudent.app.s2device
runner=$app.test/androidx.test.runner.AndroidJUnitRunner
mkdir -p "$out"
[[ $("$adb" -s "$serial" get-state) == device ]]
"$adb" -s "$serial" shell getprop ro.product.cpu.abilist | grep -q '^arm64-v8a'

verify_apk() {
    local package=$1 name=$2 installed expected measured
    expected=$(sha256sum "$candidate/$name" | cut -d' ' -f1)
    installed=$("$adb" -s "$serial" shell pm path "$package" | tr -d '\r' | sed -n 's/^package://p')
    [[ -n $installed && $installed != *$'\n'* && $installed == */base.apk ]]
    "$adb" -s "$serial" pull "$installed" "$out/$name.readback" >/dev/null
    measured=$(sha256sum "$out/$name.readback" | cut -d' ' -f1)
    rm -f "$out/$name.readback"
    [[ $measured == "$expected" ]] || { echo "candidate mismatch: $package" >&2; return 1; }
    printf '%s %s\n' "$measured" "$package"
}

# This script checks pre-installed isolated artifacts only. No permissions, services, packages or user files are changed by the host.
{
    verify_apk "$app" app-debug.apk
    verify_apk "$app.test" app-debug-androidTest.apk
    if [[ $phase == base ]]; then verify_apk com.deepstudent.services.resources.data.test resources-test.apk; fi
} | tee "$out/$phase-installed.txt"

case "$phase" in
    base)
        cases=com.deepstudent.app.execution.ShellApprovalDeviceTest,com.deepstudent.app.saf.WorkspaceProviderDeviceTest
        picker=${DSTU_MANUAL_PICKER:-false}
        [[ $picker == false || $picker == true ]]
        args=(-e class "$cases" -e s228ManualPicker "$picker")
        tests=5
        ;;
    APP|LINUX|SHIZUKU)
        [[ $uid =~ ^[0-9]+$ ]] || { echo 'the expected OS or guest UID is required' >&2; exit 2; }
        args=(-e class com.deepstudent.app.execution.ShellTargetDeviceReplayTest -e s227Target "$phase" -e s227ExpectedUid "$uid")
        tests=1
        ;;
    provision)
        args=(-e class com.deepstudent.app.execution.ShellDevicePreparationTest#installFrozenLinuxEnvironment)
        tests=1
        ;;
    ROOT)
        [[ $uid == 0 ]] || { echo 'ROOT expects UID 0' >&2; exit 2; }
        args=(-e class com.deepstudent.app.execution.ShellAuthorizationDeviceTest#requestIndependentRootForIsolatedApp)
        tests=1
        ;;
    ROOT_UNAVAILABLE)
        args=(-e class com.deepstudent.app.execution.ShellAuthorizationDeviceTest#independentRootFailsClosedOnUnrootedDevice)
        tests=1
        ;;
    cancel-LINUX|cancel-SHIZUKU|cancel-ROOT)
        [[ $uid =~ ^[0-9]+$ ]] || { echo 'the expected OS or guest UID is required' >&2; exit 2; }
        args=(-e class com.deepstudent.app.execution.ShellProcessCancellationDeviceTest -e s227Target "${phase#cancel-}" -e s227ExpectedUid "$uid")
        tests=1
        ;;
    *) echo "invalid phase: $phase" >&2; exit 2 ;;
esac
"$adb" -s "$serial" shell am instrument -w -r "${args[@]}" "$runner" | tee "$out/$phase-instrumentation.txt"
grep -Fq "OK ($tests test" "$out/$phase-instrumentation.txt"
! grep -Eq 'FAILURES!!!|INSTRUMENTATION_STATUS_CODE: -[1234]' "$out/$phase-instrumentation.txt"
