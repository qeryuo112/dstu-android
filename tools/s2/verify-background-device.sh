#!/usr/bin/env bash
# Install only sealed, disposable packages on the one selected physical device.
set -euo pipefail
umask 077
serial=${1:?USB serial required}
model=${2:?physical model required}
candidate=$(realpath "${3:?sealed candidate directory required}")
out=${4:?new private output directory required}
phase=${5:-all}
root=$(realpath "$(dirname "${BASH_SOURCE[0]}")/../..")
sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
adb=$sdk/platform-tools/adb
app=com.deepstudent.app.s2device
test_app=$app.test
platform_test=com.deepstudent.platform.background.test
formal=com.deepstudent.app
owned=()
local_hash=()
[[ "$serial" =~ ^[A-Za-z0-9_.:-]+$ ]]
[[ "$model" == iPA2475 || "$model" == V2232A ]]
[[ "$phase" == s223 || "$phase" == s224 || "$phase" == s225 || "$phase" == all ]]
[[ $(realpath -m "$out") != "$root"/* && $(realpath -m "$out") != "$root" ]]
! git -C "$(dirname "$out")" rev-parse --is-inside-work-tree >/dev/null 2>&1
mkdir -m 700 "$out"
exec > >(tee "$out/device.log") 2>&1

d() { "$adb" -s "$serial" "$@"; }
shell() { d shell -n "$@" | tr -d '\r'; }
stamp() { TZ=Asia/Shanghai date -Iseconds; }
absent() {
    local packages
    packages=$(shell pm list packages -u --user 0 "$1") || return 2
    ! grep -Fxq "package:$1" <<< "$packages"
}
installed_hash() {
    local path
    path=$(shell pm path --user 0 "$1") || return 1
    [[ "$path" =~ ^package:/data/app/[^[:space:]]+/base\.apk$ ]] || return 1
    shell sha256sum "${path#package:}" | cut -d ' ' -f 1
}
formal_state() {
    local packages entry
    packages=$(shell pm list packages -U --user 0 "$formal") || return 2
    entry=$(grep -E '^package:com\.deepstudent\.app uid:[0-9]+$' <<< "$packages" || true)
    if [[ -z "$entry" ]]; then printf 'absent\n'; return; fi
    printf '%s\n' "$entry"
    shell pm path --user 0 "$formal"
}
cleanup() {
    local rc=$? i pkg after
    trap - EXIT
    set +e
    if [[ ${battery_changed:-0} == 1 ]] && ! restore_battery; then rc=1; fi
    for ((i=${#owned[@]}-1; i>=0; i--)); do
        pkg=${owned[i]}
        if ! absent "$pkg"; then
            if [[ $(installed_hash "$pkg") == "${local_hash[i]}" ]]; then
                if [[ $(shell pm uninstall --user 0 "$pkg") == Success ]] && absent "$pkg"; then
                    printf 'Removed verified isolated package %s\n' "$pkg"
                else
                    printf 'Cleanup failed: %s\n' "$pkg" >&2; rc=1
                fi
            else
                printf 'Refusing to remove unverified package %s\n' "$pkg" >&2; rc=1
            fi
        fi
    done
    if ! after=$(formal_state); then
        printf 'Could not read formal installation state\n' >&2; rc=1
    elif [[ "$after" != "$formal_before" ]]; then
        printf 'Formal installation state changed\n' >&2; rc=1
    fi
    printf 'Formal installation checked; finished %s result=%d\n' "$(stamp)" "$rc"
    exit "$rc"
}

install_one() {
    local name=$1 pkg=$2 expected badging
    absent "$pkg"
    badging=$("$sdk/build-tools/36.0.0/aapt" dump badging "$candidate/$name")
    grep -Fq "package: name='$pkg'" <<< "$badging"
    expected=$(sha256sum "$candidate/$name" | cut -d ' ' -f 1)
    owned+=("$pkg"); local_hash+=("$expected")
    timeout 150 "$adb" -s "$serial" install --user 0 --no-streaming -t "$candidate/$name"
    [[ $(installed_hash "$pkg") == "$expected" ]]
    printf 'Read back APK: %s sha256=%s\n' "$pkg" "$expected"
}

instrument() {
    local pkg=$1 kind=$2 selection=$3
    shift 3
    printf '\n%s %s %s\n' "$(stamp)" "$kind" "$selection"
    timeout 110 "$adb" -s "$serial" shell am instrument --user 0 -w -r \
        -e class "$selection" "$@" "$pkg/androidx.test.runner.AndroidJUnitRunner" | tr -d '\r' | tee "$out/$kind.log"
}

positive() {
    local pkg=$1 kind=$2 selection=$3 count=$4
    shift 4
    instrument "$pkg" "$kind" "$selection" "$@"
    node --input-type=module - "$root" "$out/$kind.log" "$selection" "$count" <<'JS'
import {readFileSync} from 'node:fs';
import {pathToFileURL} from 'node:url';
const [, , root, file, selection, count] = process.argv;
const {parseInstrumentResults} = await import(pathToFileURL(`${root}/tools/s2/s2-16-18-results.mjs`));
for (const test of parseInstrumentResults(readFileSync(file, 'utf8'), selection, Number(count))) console.log(`PASS ${test}`);
JS
}

wait_dead() {
    local pid=$1
    [[ "$pid" =~ ^[1-9][0-9]*$ ]]
    for _ in {1..25}; do
        if [[ $(shell "if test -d /proc/$pid; then printf present; else printf gone; fi") == gone ]]; then return; fi
        sleep .2
    done
    printf 'Expected old PID to be gone\n' >&2; return 1
}

expected_exit() {
    local pkg=$1 kind=$2 selection=$3 marker=$4 rc=0 lines
    instrument "$pkg" "$kind" "$selection" || rc=$?
    [[ "$rc" != 124 && "$rc" != 137 ]]
    ! grep -Eq '^OK |FAILURES!!!|^INSTRUMENTATION_STATUS: stack=' "$out/$kind.log"
    grep -Eq 'Process crashed|Process exited' "$out/$kind.log"
    lines=$(grep -E "^${marker} pid=[1-9][0-9]*$|^INSTRUMENTATION_STATUS: stream=${marker} pid=[1-9][0-9]*$" "$out/$kind.log")
    wait_dead "${lines##*pid=}"
    printf 'Verified expected process exit: %s\n' "$marker"
}

foreground_fixture() {
    local policy
    shell input keyevent KEYCODE_WAKEUP
    policy=$(shell dumpsys window policy)
    if grep -Eq '^[[:space:]]*secure=false' <<< "$policy"; then shell wm dismiss-keyguard; fi
    shell am start -W --user 0 -n "$app/com.deepstudent.app.saf.SafGrantHostActivity" | grep -Fx 'Status: ok'
}

run_s223() {
    positive "$platform_test" s223-notification com.deepstudent.platform.background.NotificationDeviceTest 4
    foreground_fixture
    positive "$test_app" s223-ui com.deepstudent.app.runtime.NoteImportPanelDeviceTest 1
    foreground_fixture
    positive "$test_app" s223-multiple com.deepstudent.app.runtime.ForegroundRuntimeDeviceTest 1
    foreground_fixture
    positive "$test_app" s223-warm com.deepstudent.app.runtime.ForegroundRebuildDeviceTest#sameProcessRebuildRestoresCurrentDuty 1
    foreground_fixture
    expected_exit "$test_app" s223-kill com.deepstudent.app.runtime.ForegroundRebuildDeviceTest#seedLiveDutyForHostProcessDeath S223_LIVE_DUTY
    foreground_fixture
    positive "$test_app" s223-cold com.deepstudent.app.runtime.ForegroundRebuildDeviceTest#coldRebuildClosesLostReferenceAndDoesNotRerunImport 1
}

battery_state() {
    shell cmd deviceidle whitelist | grep -Fx "user,$app,$battery_uid" >/dev/null
}

restore_battery() {
    if [[ "$battery_before" == absent ]]; then
        shell cmd deviceidle whitelist "-$app" >/dev/null && ! battery_state
    else
        battery_state
    fi
    local rc=$?
    if [[ "$rc" == 0 ]]; then battery_changed=0; printf 'Restored isolated package battery state\n'; fi
    return "$rc"
}

run_s224() {
    local test=com.deepstudent.app.runtime.BackgroundPowerDeviceTest
    battery_uid=$(shell pm list packages -U --user 0 "$app" | awk -v pkg="package:$app" '$1 == pkg { sub(/^uid:/, "", $2); print $2 }')
    [[ "$battery_uid" =~ ^[0-9]+$ ]]
    foreground_fixture
    positive "$test_app" s224-terminal "$test#realTerminalDutyTracksTheSavedSwitchAndDoesNotHoldAnIdleLock" 1
    shell am force-stop --user 0 "$app"
    foreground_fixture
    positive "$test_app" s224-reopen "$test#restartKeepsOnlyPreferenceAndReadsCurrentSystemBatteryState" 1 -e s224BatteryIgnoring false
    grep -Fq 'S224_REAL_BATTERY ignoring=false' "$out/s224-reopen.log"
    battery_before=absent
    ! battery_state
    # Change the verified, just-installed disposable package only, never the global idle policy.
    battery_changed=1
    shell cmd deviceidle whitelist "+$app" >/dev/null
    battery_state
    foreground_fixture
    positive "$test_app" s224-battery-exemption "$test#batteryPageReflectsSystemState" 1 -e s224BatteryIgnoring true
    grep -Fq 'S224_REAL_BATTERY ignoring=true' "$out/s224-battery-exemption.log"
    restore_battery
    foreground_fixture
    positive "$test_app" s224-wake "$test#realWakeLockTimesOutAndControllerReleasesOnStop" 1
}

run_s225() {
    local test=com.deepstudent.app.runtime.ScheduledWorkDeviceTest marker pid job stopped
    positive "$platform_test" s225-platform com.deepstudent.platform.background.scheduling.SchedulingDeviceTest 8
    foreground_fixture
    positive "$test_app" s225-timeout "$test#uiCompetesWithRealWorkerThenSystemTimeoutYieldsTheSameCheckpoint" 1
    foreground_fixture
    positive "$test_app" s225-reminders com.deepstudent.app.runtime.ReminderReplanDeviceTest 2
    foreground_fixture
    positive "$test_app" s225-force-seed "$test#seedWorkForExternalForceStop" 1
    marker=$(grep -E '^S225_FORCE_STOP_SEED pid=[1-9][0-9]* job=[0-9]+$' "$out/s225-force-seed.log")
    pid=${marker#*pid=}; pid=${pid%% *}; job=${marker##*job=}
    shell am force-stop --user 0 "$app"
    wait_dead "$pid"
    stopped=$(shell dumpsys package "$app" | grep -E 'User 0:.*stopped=true' | grep -v 'User 999:' || true)
    [[ -n "$stopped" ]]
    if shell cmd jobscheduler get-job-state -u 0 -n androidx.work.systemjobscheduler "$app" "$job" 2>/dev/null | grep -E '(^|[[:space:]])active($|[[:space:]])'; then
        printf 'Force-stopped package still has an active job\n' >&2; return 1
    fi
    sleep 2
    [[ -z $(shell pidof "$app" || true) ]]
    printf 'Validated force-stop without process or active job before explicit reopen\n'
    foreground_fixture
    positive "$test_app" s225-force-reopen "$test#visibleReopenRequeuesTheOldTaskWithoutDuplicatingBusiness" 1
}

mapfile -t attached < <("$adb" devices -l | grep -vE '^(List of devices attached|[[:space:]]*$)')
[[ ${#attached[@]} == 1 && ${attached[0]} =~ ^${serial}[[:space:]]+device[[:space:]]+usb: ]]
[[ $(shell getprop ro.product.model) == "$model" && $(shell getprop ro.product.cpu.abi) == arm64-v8a ]]
[[ $(shell getprop ro.build.version.sdk) == 36 && $(shell getprop ro.kernel.qemu) != 1 ]]
[[ $(shell am get-current-user) == 0 ]]
formal_before=$(formal_state)
for pkg in "$app" "$test_app" "$platform_test"; do absent "$pkg"; done
(cd "$candidate" && sha256sum -c SHA256SUMS && sha256sum SHA256SUMS)
trap cleanup EXIT
printf 'Started %s device=%s api=36 abi=arm64-v8a phase=%s\n' "$(stamp)" "$model" "$phase"
install_one isolated-app.apk "$app"
install_one isolated-app-test.apk "$test_app"
if [[ "$phase" != s224 ]]; then install_one background-test.apk "$platform_test"; fi
if [[ "$phase" == s223 || "$phase" == all ]]; then run_s223; fi
if [[ "$phase" == s224 || "$phase" == all ]]; then run_s224; fi
if [[ "$phase" == s225 || "$phase" == all ]]; then run_s225; fi
