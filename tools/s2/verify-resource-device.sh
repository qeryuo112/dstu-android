#!/usr/bin/env bash
# Verify the resource ABI on exactly one connected device. The ordinary App's data is preserved.
set -euo pipefail

if [[ $# != 5 || ! "$3" =~ ^S2\.0[678]$ || ! "$5" =~ ^[0-9a-f]{64}$ ]]; then
  printf 'usage: %s SERIAL MODEL UNIT CANDIDATE_DIRECTORY SHA256 < cases.tsv\n' "$0" >&2
  printf 'one case per line: app|resources EXPECTED_TEST_COUNT FULL_TEST_CLASS[#METHOD]\n' >&2
  exit 2
fi
export ANDROID_SERIAL="$1"
model=$2
unit=$3
candidate=$(realpath -e -- "$4")
expected_sha=$5
cd "$(dirname "$0")/../.."

instrumentation_arguments=()
case ${DSTU_S207_MANUAL_PICKER:-0} in
  0) ;;
  1) [[ "$unit" == S2.07 ]] || exit 2; instrumentation_arguments+=(-e s207ManualPicker true) ;;
  *) printf 'invalid S2.07 manual picker mode\n' >&2; exit 2 ;;
esac
cloud_port=${DSTU_S207_DEVICE_PORT:-}
if [[ -n "$cloud_port" ]]; then
  if [[ "$unit" != S2.07 || ! "$cloud_port" =~ ^[1-9][0-9]{3,4}$ ]] || (( cloud_port > 65535 )); then
    printf 'invalid S2.07 document fixture port\n' >&2
    exit 2
  fi
  instrumentation_arguments+=(-e s207CloudPort "$cloud_port")
fi

case_kinds=()
case_counts=()
case_selectors=()
while read -r kind count selector extra; do
  [[ "$kind" =~ ^(app|resources)$ && "$count" =~ ^[1-9][0-9]?$ && -z "$extra" ]]
  [[ "$selector" =~ ^com\.deepstudent(\.[A-Za-z_][A-Za-z0-9_]*)+(#[A-Za-z_][A-Za-z0-9_]*)?$ ]]
  case "$kind,$selector" in
    app,com.deepstudent.app.*|resources,com.deepstudent.services.resources.data.*) ;;
    *) exit 2 ;;
  esac
  case_kinds+=("$kind"); case_counts+=("$count"); case_selectors+=("$selector")
done
[[ ${#case_kinds[@]} -gt 0 ]]

adb="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
app=com.deepstudent.app
app_test=$app.test
resources_test=com.deepstudent.services.resources.data.test
base="$candidate/app-debug.apk"
instrumentation="$candidate/app-debug-androidTest.apk"
resources="$candidate/resources-debug-androidTest.apk"
sha() { sha256sum | cut -d' ' -f1; }
for file in "$base" "$instrumentation" "$resources"; do
  [[ -s "$file" ]] || { printf 'missing candidate APK: %s\n' "${file##*/}" >&2; exit 2; }
done
base_sha=$(sha < "$base")
app_test_sha=$(sha < "$instrumentation")
resources_sha=$(sha < "$resources")
set_sha=$(printf 'app=%s\nappTest=%s\nresourcesTest=%s\n' "$base_sha" "$app_test_sha" "$resources_sha" | sha)
[[ "$set_sha" == "$expected_sha" ]] || { printf 'candidate digest mismatch\n' >&2; exit 2; }
printf 'unit=%s candidateSha256=%s appSha256=%s appTestSha256=%s resourcesTestSha256=%s\n' \
  "$unit" "$set_sha" "$base_sha" "$app_test_sha" "$resources_sha"

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
path_of() {
  local result code=0
  result=$("$adb" shell pm path --user 0 "$1") || code=$?
  if (( code == 1 )) && [[ -z "$result" ]] && absent "$1"; then return 0; fi
  (( code == 0 )) || return "$code"
  printf '%s' "${result//$'\r'/}"
}

for target in "$app_test" "$resources_test"; do
  absent "$target" || { printf 'refusing to replace existing test package: %s\n' "$target" >&2; exit 2; }
done
prior_app=$(path_of "$app")
[[ -n "$prior_app" ]] || absent "$app"
started=$("$adb" shell 'date "+%m-%d %H:%M:%S.000"')
printf 'started=%s model=%s api=%s abi=arm64-v8a\n' "$(TZ=Asia/Shanghai date --iso-8601=seconds)" \
  "$model" "$("$adb" shell getprop ro.build.version.sdk)"
owned=()
owned_digests=()
cleanup() {
  local status=$? package installed actual i
  trap - EXIT
  if [[ "$("$adb" get-state 2>/dev/null || true)" != device ]]; then
    printf 'device disconnected before fixture cleanup\n' >&2
    exit 1
  fi
  for i in "${!owned[@]}"; do
    package=${owned[i]}
    if absent "$package"; then continue; fi
    installed=$(path_of "$package") || installed=''
    if [[ "$installed" != package:/* || "$installed" == *$'\n'* ]] ||
        ! actual=$(timeout 15s "$adb" shell sha256sum "${installed#package:}" | cut -d' ' -f1) ||
        [[ "$actual" != "${owned_digests[i]}" ]]; then
      printf 'refusing to remove unverified package: %s\n' "$package" >&2
      status=1
    else
      timeout 30s "$adb" uninstall --user 0 "$package" || status=1
      absent "$package" || status=1
    fi
  done
  if [[ -n "$prior_app" ]]; then printf 'ordinaryApp=retained_in_place\n'; fi
  exit "$status"
}
trap cleanup EXIT

install_apk() {
  local file=$1 dest=$2 wanted=$3 installed actual
  sole_device
  timeout 90s "$adb" install --no-streaming --user 0 -r -t "$file"
  installed=$(path_of "$dest")
  [[ "$installed" == package:/* && "$installed" != *$'\n'* ]]
  actual=$("$adb" shell sha256sum "${installed#package:}" | cut -d' ' -f1)
  [[ "$wanted" == "$actual" && "$(sha < "$file")" == "$wanted" ]]
  printf 'installedSha256 %s %s\n' "$dest" "$actual"
}
clock() {
  local result
  result=$(timeout 4s "$adb" shell date '+%s%3N,%z' 2>/dev/null) || result=''
  result=${result//$'\r'/}
  if [[ "$result" =~ ^[0-9]{13},[+-][0-9]{4}$ ]]; then printf '%s' "$result"; else printf '0,unknown'; fi
}
exit_evidence() {
  local window=$1 target=$2
  timeout 20s node tools/s2/collect-process-exit.mjs --adb "$adb" --serial "$ANDROID_SERIAL" \
    --package "$target" --since-ms "${window%%,*}" --since-tz "${window#*,}" >&2 \
    || printf '{"type":"collector","status":"failed_or_timeout"}\n' >&2
}

install_resources() {
  owned+=("$resources_test"); owned_digests+=("$resources_sha")
  install_apk "$resources" "$resources_test" "$resources_sha"
}
install_app() {
  owned+=("$app_test"); owned_digests+=("$app_test_sha")
  if [[ -z "$prior_app" ]]; then owned+=("$app"); owned_digests+=("$base_sha"); fi
  install_apk "$base" "$app" "$base_sha"
  install_apk "$instrumentation" "$app_test" "$app_test_sha"
}

print_device_logs() {
  timeout 18s "$adb" logcat -d -b main -v threadtime -T "$started" -s DstuOperation:I "Dstu${unit//./}:I" DstuSafGrant:I DstuSafTest:I '*:S' \
    | awk '/event=(resources\.|s2\.0[678]\.)/'
}

instrument() {
  local test_package=$1 process=$2 count=$3 selection=$4 window result code=0
  shift 4
  sole_device
  window=$(clock)
  printf 'instrument=%s expected=%s class=%s\n' "$test_package" "$count" "$selection"
  result=$(timeout 135s "$adb" shell am instrument --user 0 -w -r -e class "$selection" "$@" \
    "$test_package/androidx.test.runner.AndroidJUnitRunner" 2>&1) || code=$?
  result=${result//$'\r'/}
  if (( code != 0 )) || grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|^INSTRUMENTATION_RESULT: shortMsg=|^INSTRUMENTATION_STATUS_CODE: -(1|2|3|4)$' <<< "$result" || \
      ! grep -qx "OK (${count} tests\?)" <<< "$result" || \
      ! grep -qx 'INSTRUMENTATION_CODE: -1' <<< "$result" || \
      [[ "$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0$' <<< "$result")" != "$count" ]]; then
    printf '%s\ninstrumentation failed, timed out or did not execute every expected case\n' "$result" >&2
    print_device_logs || printf 'resourceLogcat=unavailable\n' >&2
    exit_evidence "$window" "$process"
    return 1
  fi
  grep -E '^(OK \(|INSTRUMENTATION_CODE:)' <<< "$result"
}

ran_app=false
ran_resources=false
passed=0
for i in "${!case_kinds[@]}"; do
  if [[ "${case_kinds[i]}" == app ]]; then
    if ! $ran_app; then install_app; ran_app=true; fi
    instrument "$app_test" "$app" "${case_counts[i]}" "${case_selectors[i]}" "${instrumentation_arguments[@]}"
  else
    if ! $ran_resources; then install_resources; ran_resources=true; fi
    instrument "$resources_test" "$resources_test" "${case_counts[i]}" "${case_selectors[i]}" "${instrumentation_arguments[@]}"
  fi
  (( passed += case_counts[i] ))
done
sole_device
# The selected producers use fixed diagnostics (no URI, payload, private paths, or exception message).
print_device_logs
printf 'unit=%s executed=%d skipped=0 candidateSha256=%s\n' "$unit" "$passed" "$set_sha"
