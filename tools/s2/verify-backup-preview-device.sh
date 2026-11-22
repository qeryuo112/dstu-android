#!/usr/bin/env bash
# In-place (or temporary) App APK plus fixture-only backup instrumentation.
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
files=(app-debug.apk app-debug-androidTest.apk cases.tsv)
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
[[ "$(sha < "$candidate/cases.tsv")" == "$(sha < tools/s2/s2-10-cases.tsv)" ]]
printf 'unit=S2.10 candidateSha256=%s\n%s' "$expected_sha" "$sums"

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
for pkg in "$app_test"; do
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
  timeout 18s "$adb" logcat -d -b main -v brief -T "$started" -s DstuBackupPreview:I DstuDataSpace:I '*:S' \
    | awk '/event=(backup\.preview|storage\.dataspace)/'
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
  printf 'instrumentation %s %s pass=%s skipped=0\n' "$pkg" "$selection" "$count"
}

install "$app" app-debug.apk
install "$app_test" app-debug-androidTest.apk
passed=0
mapfile -t cases < "$candidate/cases.tsv"
for row in "${cases[@]}"; do
  read -r count selector extra <<< "$row"
  [[ "$count" =~ ^[1-9][0-9]?$ && "$selector" =~ ^com\.deepstudent\.app(\.[A-Za-z_][A-Za-z0-9_]*)+$ && -z "$extra" ]]
  require_ok "$app_test" "$count" "$selector"
  passed=$((passed + count))
done
[[ "$passed" == "$(awk '{ n += $1 } END { print n+0 }' "$candidate/cases.tsv")" && "$passed" -gt 0 ]]
sole_device
print_logs
printf 'unit=S2.10 instrumentation=%d skipped=0 candidateSha256=%s\n' "$passed" "$expected_sha"
