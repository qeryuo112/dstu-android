#!/usr/bin/env bash
# Verify the opt-in probe on a separately built standalone test APK, never on the pinned acceptance candidate.
set -euo pipefail
if [[ $# != 3 ]]; then
  printf 'usage: bash tools/s2/verify-process-exit-probe.sh SERIAL MODEL STANDALONE_TEST_APK\n' >&2
  exit 2
fi
export ANDROID_SERIAL="$1"
model="$2"
apk=$(realpath -e -- "$3")
sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"
adb=$(realpath -e -- "$sdk/platform-tools/adb")
collector="$(dirname -- "$0")/collect-process-exit.mjs"
badging=$("$sdk/build-tools/36.0.0/aapt2" dump badging "$apk")
pkg=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<< "$badging")
case "$pkg" in
  com.deepstudent.platform.database.test|com.deepstudent.services.sync.data.test) ;;
  *) printf 'a standalone DeepStudent test APK is required\n' >&2; exit 2 ;;
esac
runner="$pkg/androidx.test.runner.AndroidJUnitRunner"
probe=com.deepstudent.testing.exitprobe.ProcessExitProbeAndroidTest
listener=com.deepstudent.testing.exitprobe.ProcessExitProbeListener
one_device() { [[ "$("$adb" devices | awk 'NR > 1 && NF {print $1, $2}')" == "$ANDROID_SERIAL device" ]]; }
get() { sed -n "s/^INSTRUMENTATION_STATUS: $2=//p" "$1" | tr -d '\r'; }
sha() { sha256sum | cut -d' ' -f1; }

one_device
[[ "$("$adb" shell getprop ro.product.model)" == "$model" ]]
[[ "$("$adb" shell am get-current-user)" == 0 ]]
api=$("$adb" shell getprop ro.build.version.sdk)
[[ "$api" =~ ^[0-9]+$ && "$api" -ge 30 ]]
[[ "$("$adb" shell dumpsys package "$pkg" | tr -d '\r')" == "Unable to find package: $pkg" ]]
work=$(mktemp -d)
child=''
cleanup() {
  local status=$? installed
  trap - EXIT
  if [[ -n "$child" ]]; then kill "$child" 2>/dev/null || true; wait "$child" 2>/dev/null || true; fi
  if [[ "$("$adb" get-state 2>/dev/null || true)" != device ]]; then
    printf 'device disconnected before test-package cleanup\n' >&2
    status=1
  else
    installed=$("$adb" shell pm path --user 0 "$pkg" 2>/dev/null || true)
    if [[ -n "$installed" ]]; then
      timeout 30s "$adb" uninstall --user 0 "$pkg" || status=1
      [[ -z "$("$adb" shell pm path --user 0 "$pkg" 2>/dev/null || true)" ]] || status=1
    fi
  fi
  rm -rf -- "$work"
  exit "$status"
}
trap cleanup EXIT
expected=$(sha < "$apk")
timeout 120s "$adb" install --no-streaming --user 0 "$apk"
installed=$("$adb" shell pm path --user 0 "$pkg")
[[ "$installed" == package:* && "$installed" != *$'\n'* ]]
[[ "$("$adb" shell sha256sum "${installed#package:}" | cut -d' ' -f1)" == "$expected" ]]
"$adb" shell pm list instrumentation "$pkg" | grep -Fqx "instrumentation:$runner (target=$pkg)"
printf 'processExitProbe model=%s api=%s package=%s diagnosticApkSha256=%s\n' "$model" "$api" "$pkg" "$expected"

instrument() {
  local method=$1
  shift
  timeout 45s "$adb" shell am instrument --user 0 -w -r -e class "$probe#$method" "$@" "$runner"
}
arm() {
  local method=$1 token=$2 step
  # Background ADB is needed only so the host can terminate the active hold process.
  timeout 45s "$adb" shell am instrument --user 0 -w -r -e class "$probe#$method" \
    -e listener "$listener" -e dstuExitToken "$token" -e dstuExitProbeControl "$method" "$runner" > "$work/arm" 2>&1 &
  child=$!
  for (( step=0; step<100; step++ )); do
    if [[ "$(get "$work/arm" dstuExitState)" == armed && "$(get "$work/arm" dstuExitToken)" == "$token" &&
      "$(get "$work/arm" dstuExitPid)" =~ ^[1-9][0-9]*$ ]]; then return; fi
    kill -0 "$child" 2>/dev/null || break
    sleep 0.1
  done
  printf 'probe never armed: %s\n' "$method" >&2
  cat "$work/arm" >&2
  return 1
}
control() {
  local method=$1 reason=$2 signal=$3 token pid clock cutoff wrong exit_status=0
  token=$(tr -d -- '-\n' < /proc/sys/kernel/random/uuid)
  [[ "$token" =~ ^[0-9a-f]{32}$ ]]
  clock=$("$adb" shell date +%s%3N,%z | tr -d '\r')
  [[ "$clock" =~ ^[0-9]{13},[+-][0-9]{4}$ ]]
  arm "$method" "$token"
  pid=$(get "$work/arm" dstuExitPid)
  [[ "$pid" =~ ^[1-9][0-9]*$ && "$(get "$work/arm" dstuExitToken)" == "$token" ]]
  if [[ "$method" == hold ]]; then
    one_device
    [[ "$("$adb" shell pidof "$pkg" | tr -d '\r')" == "$pid" ]]
    timeout 10s "$adb" shell am force-stop --user 0 "$pkg"
  fi
  wait "$child" || exit_status=$?
  child=''
  (( exit_status <= 1 ))
  grep -q '^INSTRUMENTATION_RESULT: shortMsg=Process crashed\.$' "$work/arm"
  [[ "$("$adb" shell "test ! -d /proc/$pid && echo gone" | tr -d '\r')" == gone ]]
  cutoff=$("$adb" shell date +%s%3N | tr -d '\r')
  [[ "$cutoff" =~ ^[0-9]{13}$ ]]
  node "$collector" --adb "$adb" --serial "$ANDROID_SERIAL" --package "$pkg" \
    --since-ms "${clock%,*}" --since-tz "${clock#*,}" --pid "$pid"
  if ! instrument report -e dstuExitPid "$pid" -e dstuExitToken "$token" -e dstuExitCutoffMs "$cutoff" > "$work/report" 2>&1; then
    cat "$work/report" >&2
    return 1
  fi
  grep -E '^INSTRUMENTATION_STATUS: dstuExit' "$work/report" || true
  grep -qx 'OK (1 test)' "$work/report"
  grep -qx 'INSTRUMENTATION_CODE: -1' "$work/report"
  [[ "$(get "$work/report" dstuExitState)" == matched && "$(get "$work/report" dstuExitPid)" == "$pid" && \
    "$(get "$work/report" dstuExitToken)" == "$token" && "$(get "$work/report" dstuExitReason)" == "$reason" ]]
  [[ -z "$signal" || "$(get "$work/report" dstuExitStatus)" == "$signal" ]]
  printf 'exitProbeControl=%s pid=%s androidReason=%s verified=true\n' "$method" "$pid" "$reason"
  if [[ "$method" == hold ]]; then
    wrong="${token%?}0"
    if [[ "$wrong" == "$token" ]]; then wrong="${token%?}1"; fi
    instrument report -e dstuExitPid "$pid" -e dstuExitToken "$wrong" -e dstuExitCutoffMs "$cutoff" > "$work/report" 2>&1
    grep -qx 'OK (1 test)' "$work/report"
    [[ "$(get "$work/report" dstuExitState)" == missing && -z "$(get "$work/report" dstuExitReason)" ]]
    printf 'exitProbeNegative=wrong_token samePid=%s reasonNotAttributed=true\n' "$pid"
    instrument report -e dstuExitPid "$pid" -e dstuExitToken "$token" -e dstuExitCutoffMs "${clock%,*}" > "$work/report" 2>&1
    grep -qx 'OK (1 test)' "$work/report"
    [[ "$(get "$work/report" dstuExitState)" == missing && "$(get "$work/report" dstuExitDetail)" == exit_after_cutoff && \
      -z "$(get "$work/report" dstuExitReason)" ]]
    printf 'exitProbeNegative=after_cutoff samePid=%s reasonNotAttributed=true\n' "$pid"
  fi
}

# Even selecting the self-kill test directly is harmless without both explicit opt-ins.
instrument selfKill > "$work/inactive" 2>&1
grep -qx 'INSTRUMENTATION_STATUS_CODE: -4' "$work/inactive"
grep -qx 'INSTRUMENTATION_CODE: -1' "$work/inactive"
[[ -z "$(get "$work/inactive" dstuExitState)" ]]
printf 'exitProbeNegative=no_opt_in selfKillSkipped=true\n'

# Android ApplicationExitInfo constants: SIGNALED (2), USER_REQUESTED (10).
control selfKill 2 9
control hold 10 ''
