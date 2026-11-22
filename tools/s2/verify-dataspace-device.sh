#!/usr/bin/env bash
# An in-place App update; all destructive tests run against synthetic roots, never ordinary filesDir.
set -euo pipefail
if [[ $# != 4 || ! "$4" =~ ^[0-9a-f]{64}$ ]]; then
  printf 'usage: %s SERIAL MODEL CANDIDATE_DIRECTORY CANDIDATE_SHA256\n' "$0" >&2
  exit 2
fi
export ANDROID_SERIAL="$1"
model=$2
candidate=$(realpath -e -- "$3")
expected_sha=$4
cd "$(dirname "$0")/../.."
adb="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
app=com.deepstudent.app
app_test=$app.test
storage_test=com.deepstudent.platform.storage.test
files=(app-debug.apk app-debug-androidTest.apk storage-debug-androidTest.apk cases.tsv)
sha() { sha256sum | cut -d' ' -f1; }
sums=''
for filename in "${files[@]}"; do
  file="$candidate/$filename"
  [[ -s "$file" && ! -L "$file" ]]
  sums+="$(sha < "$file")  $filename"$'\n'
done
[[ -f "$candidate/SHA256SUMS" && ! -L "$candidate/SHA256SUMS" ]]
cmp -s <(printf '%s' "$sums") "$candidate/SHA256SUMS"
[[ "$(printf '%s' "$sums" | sha)" == "$expected_sha" ]]
[[ "$(sha < "$candidate/cases.tsv")" == "$(sha < tools/s2/s2-09-cases.tsv)" ]]
printf 'unit=S2.09 candidateSha256=%s\n%s' "$expected_sha" "$sums"

sole_device() {
  local connected
  connected=$("$adb" devices | awk 'NR > 1 && NF { print $1, $2 }')
  [[ "$connected" == "$ANDROID_SERIAL device" ]]
}
sole_device || { printf 'exactly the selected device must be connected\n' >&2; exit 2; }
[[ "$("$adb" shell getprop ro.product.model)" == "$model" ]]
[[ "$("$adb" shell getprop ro.product.cpu.abi)" == arm64-v8a ]]
[[ "$("$adb" shell am get-current-user)" == 0 ]]

absent() {
  local result
  result=$("$adb" shell dumpsys package "$1") || return
  [[ "${result//$'\r'/}" == "Unable to find package: $1" ]]
}
installed_sha() {
  local result
  result=$("$adb" shell pm path --user 0 "$1") || return
  [[ "$result" =~ ^package:/[A-Za-z0-9_./+=~:-]+/base\.apk$ ]] || return 1
  timeout 15s "$adb" shell sha256sum "${result#package:}" | cut -d' ' -f1
}
for pkg in "$app_test" "$storage_test"; do
  absent "$pkg" || { printf 'refusing to replace existing test package: %s\n' "$pkg" >&2; exit 2; }
done
prior_app=$(installed_sha "$app" || true)
[[ -n "$prior_app" ]] || absent "$app"
started=$("$adb" shell 'date "+%m-%d %H:%M:%S.000"')
printf 'time=%s model=%s api=%s abi=arm64-v8a\n' "$(TZ=Asia/Shanghai date -Is)" "$model" \
  "$("$adb" shell getprop ro.build.version.sdk)"

owned=()
expected=()
cleanup() {
  local status=$? i pkg sum
  trap - EXIT
  if ! sole_device; then printf 'selected device disconnected before cleanup\n' >&2; exit 1; fi
  for i in "${!owned[@]}"; do
    pkg=${owned[i]}
    if absent "$pkg"; then continue; fi
    sum=$(installed_sha "$pkg") || sum=''
    if [[ "$sum" != "${expected[i]}" ]]; then
      printf 'refusing to uninstall unverified package: %s\n' "$pkg" >&2; status=1
    else
      timeout 30s "$adb" uninstall --user 0 "$pkg" || status=1
      absent "$pkg" || status=1
    fi
  done
  if [[ -n "$prior_app" ]]; then printf 'ordinaryApp=retained_in_place\n'; fi
  exit "$status"
}
trap cleanup EXIT

install() {
  local pkg=$1 filename=$2 sum
  sum=$(sha < "$candidate/$filename")
  if [[ "$pkg" != "$app" || -z "$prior_app" ]]; then owned+=("$pkg"); expected+=("$sum"); fi
  sole_device
  timeout 90s "$adb" install --no-streaming --user 0 -r -t "$candidate/$filename"
  [[ "$(installed_sha "$pkg")" == "$sum" ]]
  printf 'installedSha256 %s %s\n' "$pkg" "$sum"
}

instrument() {
  local pkg=$1 selection=$2
  shift 2
  sole_device
  timeout 135s "$adb" shell am instrument --user 0 -w -r -e class "$selection" "$@" \
    "$pkg/androidx.test.runner.AndroidJUnitRunner"
}

print_logs() {
  timeout 18s "$adb" logcat -d -b main -v brief -T "$started" -s DstuDataSpace:I DstuOperation:I '*:S' \
    | awk '/event=(storage\.dataspace|resources\.asset)/'
}

require_ok() {
  local pkg=$1 count=$2 selection=$3 result rc=0
  shift 3
  result=$(instrument "$pkg" "$selection" "$@" 2>&1) || rc=$?
  result=${result//$'\r'/}
  if (( rc != 0 )) || ! grep -Eq "^OK \(${count} tests?\)$" <<< "$result" ||
      ! grep -qx 'INSTRUMENTATION_CODE: -1' <<< "$result" ||
      grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|^INSTRUMENTATION_RESULT: shortMsg=|^INSTRUMENTATION_STATUS_CODE: -(1|2|3|4)$' <<< "$result" ||
      [[ "$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0$' <<< "$result")" != "$count" ]]; then
    printf 'instrumentation failed on %s (exit=%s)\n%s\n' "$selection" "$rc" "$result" >&2
    print_logs >&2 || true
    return 1
  fi
  last_result=$result
  printf 'instrumentation %s %s pass=%s skipped=0\n' "$pkg" "$selection" "$count"
}

death_case='com.deepstudent.platform.storage.DataSpaceDeathAndroidTest'
require_death_reopen() {
  local phase=$1 expected_active=$2 run output rc=0 receipt version actual pid_record pid verification
  [[ "$phase" =~ ^[A-Z_]+$ && "$expected_active" =~ ^(old|new)$ ]]
  run="s209-death-$(date +%s)-$$${hard_cases}"
  output=$(instrument "$storage_test" "${death_case}#checkpoint" -e s209Run "$run" -e s209Phase "$phase" 2>&1) || rc=$?
  output=${output//$'\r'/}
  if (( rc > 1 )) || ! grep -qx 'INSTRUMENTATION_RESULT: shortMsg=Process crashed\.' <<< "$output" ||
      grep -Eq '^OK \(|FAILURES!!!|^INSTRUMENTATION_STATUS_CODE: -(2|3|4)$' <<< "$output"; then
    printf 'expected deliberate SIGKILL at %s (exit=%s)\n%s\n' "$phase" "$rc" "$output" >&2
    return 1
  fi
  receipt=$("$adb" exec-out run-as "$storage_test" --user 0 cat "cache/$run/receipt")
  local -a fields
  mapfile -t fields <<< "$receipt"
  [[ ${#fields[@]} == 3 ]]
  version=${fields[0]}; actual=${fields[1]}; pid_record=${fields[2]}
  [[ "$version" == version=1 && "$actual" == "phase=$phase" && "$pid_record" =~ ^pid=[1-9][0-9]+$ ]]
  pid=${pid_record#pid=}
  "$adb" shell "run-as $storage_test --user 0 sh -c 'test ! -d /proc/$pid'"
  require_ok "$storage_test" 1 "${death_case}#verify" -e s209Run "$run" -e s209Phase "$phase"       > /dev/null
  # The verifier emits one explicit fixture receipt under a non-terminal instrumentation status.
  verification=${last_result//$'\r'/}
  local receipt_pattern="^INSTRUMENTATION_STATUS: s209Verify=S209_VERIFY phase=$phase oldPid=$pid newPid=([1-9][0-9]+) active=$expected_active old=preserved$"
  local lines
  lines=$(grep '^INSTRUMENTATION_STATUS: s209Verify=' <<< "$verification")
  [[ "$lines" =~ $receipt_pattern && "${BASH_REMATCH[1]}" != "$pid" ]]
  printf 'hardDeath phase=%s oldPid=%s newPid=%s active=%s old=preserved\n' "$phase" "$pid" "${BASH_REMATCH[1]}" "$expected_active"
  "$adb" shell run-as "$storage_test" --user 0 rm -r "cache/$run"
}

install "$app" app-debug.apk
install "$app_test" app-debug-androidTest.apk
install "$storage_test" storage-debug-androidTest.apk
passed=0
hard_cases=0
last_result=''
mapfile -t cases < "$candidate/cases.tsv"
for row in "${cases[@]}"; do
  read -r kind count selector extra <<< "$row"
  [[ -z "$extra" ]]
  if [[ "$kind" == death ]]; then
    require_death_reopen "$count" "$selector"
    hard_cases=$((hard_cases + 1))
    continue
  fi
  [[ "$count" =~ ^[1-9][0-9]?$ ]]
  case "$kind,$selector" in
    app,com.deepstudent.app.*) pkg=$app_test ;;
    storage,com.deepstudent.platform.storage.*) pkg=$storage_test ;;
    *) exit 2 ;;
  esac
  require_ok "$pkg" "$count" "$selector"
  passed=$((passed + count))
done
[[ "$passed" == "$(awk '$1 == "app" || $1 == "storage" { n += $2 } END { print n+0 }' "$candidate/cases.tsv")" ]]
[[ "$hard_cases" == "$(grep -c '^death ' "$candidate/cases.tsv")" ]]
sole_device
print_logs
printf 'unit=S2.09 instrumentation=%d hardDeathReopens=%d skipped=0 candidateSha256=%s\n' "$passed" "$hard_cases" "$expected_sha"
