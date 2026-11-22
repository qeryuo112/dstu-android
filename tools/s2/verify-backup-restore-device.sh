#!/usr/bin/env bash
# In-place (or temporary) App APK plus fixture-only backup instrumentation.
set -euo pipefail
if [[ $# -lt 4 || $# -gt 5 || ! "$4" =~ ^[0-9a-f]{64}$ ]]; then
  printf 'usage: %s SERIAL MODEL CANDIDATE_DIRECTORY CANDIDATE_SHA256 [S2.11|S2.12]\n' "$0" >&2
  exit 2
fi
export ANDROID_SERIAL="$1"
model=$2
candidate=$(realpath -e -- "$3")
expected_sha=$4
unit=${5:-S2.11}
cd "$(dirname "$0")/../.."
case "$unit" in
  S2.11) case_spec=tools/s2/s2-11-cases.tsv ;;
  S2.12) case_spec=tools/s2/s2-12-cases.tsv ;;
  *) printf 'unsupported restore unit\n' >&2; exit 2 ;;
esac
adb="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
app=com.deepstudent.app
app_test=$app.test
restore_test=com.deepstudent.services.backup.data.test
files=(app-debug.apk app-debug-androidTest.apk backup-data-debug-androidTest.apk cases.tsv)
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
[[ "$(sha < "$candidate/cases.tsv")" == "$(sha < "$case_spec")" ]]
printf 'unit=%s candidateSha256=%s\n%s' "$unit" "$expected_sha" "$sums"

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
for pkg in "$app_test" "$restore_test"; do
  absent "$pkg" || { printf 'refusing to replace existing test package: %s\n' "$pkg" >&2; exit 2; }
done
prior_app=$(installed_sha "$app" || true)
[[ -n "$prior_app" ]] || absent "$app"
started=$("$adb" shell 'date "+%m-%d %H:%M:%S.000"')
printf 'time=%s model=%s api=%s abi=arm64-v8a\n' "$(TZ=Asia/Shanghai date -Is)" "$model" \
  "$("$adb" shell getprop ro.build.version.sdk)"

owned=()
protocol_logs=()
expected=()
cleanup() {
  local status=$? i pkg sum
  trap - EXIT
  for logfile in "${protocol_logs[@]}"; do rm -- "$logfile"; done
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
  timeout 18s "$adb" logcat -d -b main -v brief -T "$started" -s DstuBackupRestore:I DstuBackupPreview:I DstuDataSpace:I '*:S' \
    | awk '/event=(backup\.(preview|queue|restore|legacy_import)|storage\.dataspace)/'
}

verify_legacy_trace() {
  # At least two independent legacy operations validate all four databases and references before activation.
  awk '
    function field(key, i, pair) {
      for (i=1; i<=NF; i++) if (index($i,key "=")==1) { split($i,pair,"="); return pair[2] }
      return ""
    }
    /event=backup.legacy_import / {
      id=field("correlationId"); phase=field("phase"); state=field("state"); code=field("code")
      if (id !~ /^[0-9a-f-]+$/ || length(id)!=36 || field("owner")!="app.backup" || field("elapsedMs") !~ /^[0-9]+$/) exit 1
      if (phase !~ /^(database_(vfs|chat_v2|mistakes|llm_usage)|references)$/ || code !~ /^[A-Za-z_]+$/) exit 1
      if (state=="verified") {
        if (phase=="references") refs[id]=NR; else db[id,phase]=1
      } else if (state=="failed") {
        if (code=="SOURCE_SCHEMA_MISMATCH") schema[id]=1
        if (code=="BLOB_NOT_IN_ARCHIVE") missing[id]=1
      }
    }
    /event=backup.restore / && /phase=ACTIVATE state=SUCCEEDED / { active[field("correlationId")]=NR }
    /event=backup.preview / && /state=verified / && /sourceKind=legacy_mobile/ { previews++ }
    END {
      for (id in refs) if (active[id]>refs[id] && db[id,"database_vfs"] && db[id,"database_chat_v2"] &&
        db[id,"database_mistakes"] && db[id,"database_llm_usage"]) verified++
      for (id in schema) if (!(id in active)) badSchema++
      for (id in missing) if (!(id in active)) badBlob++
      if (verified<2 || previews<2 || badSchema<1 || badBlob<1) exit 1
      printf "legacyTrace=verified archivePreviews=%d imports=%d schemaMismatch=%d missingZipBlob=%d\n",previews,verified,badSchema,badBlob
    }
  '
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

death_case=com.deepstudent.services.backup.data.RestoreKillAndroidTest
require_restore_death() {
  local phase=$1 before=$2 run capture child receipt='' old_pid disposition line
  local -a receipt_fields
  [[ "$phase" =~ ^[A-Z_]+$ && "$before" =~ ^(old|new)$ ]]
  run="s211-${phase}-$(date +%s)-$$"
  capture=$(mktemp "${TMPDIR:-/tmp}/s211-instrument.XXXXXX")
  protocol_logs+=("$capture")
  instrument "$restore_test" "$death_case#checkpoint" -e s211Run "$run" -e s211Phase "$phase" > "$capture" 2>&1 &
  child=$!
  for _ in $(seq 1 40); do
    receipt=$("$adb" exec-out run-as "$restore_test" --user 0 cat "cache/s211-$run/receipt" 2>/dev/null || true)
    # exec-out may report a not-yet-created remote path on stdout. Only the target receipt is a checkpoint.
    [[ "$receipt" == "version=1"$'\n'"phase=$phase"$'\n'pid=* ]] && break
    kill -0 "$child" 2>/dev/null || break
    sleep 0.5
  done
  mapfile -t receipt_fields <<< "$receipt"
  if [[ ${#receipt_fields[@]} != 3 || "${receipt_fields[0]}" != version=1 || "${receipt_fields[1]}" != "phase=$phase" ||
        ! "${receipt_fields[2]}" =~ ^pid=[1-9][0-9]+$ ]]; then
    printf 'invalid restore checkpoint %s\n' "$phase" >&2; cat "$capture" >&2
    return 1
  fi
  old_pid=${receipt_fields[2]#pid=}
  sole_device
  [[ "$("$adb" shell pidof "$restore_test")" == "$old_pid" ]]
  "$adb" shell run-as "$restore_test" --user 0 kill -9 "$old_pid"
  wait "$child" || true
  "$adb" shell "run-as $restore_test --user 0 sh -c 'test ! -d /proc/$old_pid'"
  grep -qx 'INSTRUMENTATION_RESULT: shortMsg=Process crashed\.' <(tr -d '\r' < "$capture")
  require_ok "$restore_test" 1 "$death_case#verifyAndResume" -e s211Run "$run" -e s211Phase "$phase" >/dev/null
  if [[ "$before" == new ]]; then disposition=ALREADY_ACTIVE; else disposition=NEWLY_COMMITTED; fi
  line=$(grep '^INSTRUMENTATION_STATUS: s211Verify=' <<< "$last_result")
  local pattern="^INSTRUMENTATION_STATUS: s211Verify=S211_VERIFY phase=$phase oldPid=$old_pid newPid=([1-9][0-9]+) activeBefore=$before active=new old=preserved disposition=$disposition sameOp=once$"
  [[ "$line" =~ $pattern && "${BASH_REMATCH[1]}" != "$old_pid" ]]
  printf 'sigkill=external phase=%s oldPid=%s newPid=%s activeBefore=%s disposition=%s old=preserved sameOp=once\n' \
    "$phase" "$old_pid" "${BASH_REMATCH[1]}" "$before" "$disposition"
}

install "$app" app-debug.apk
install "$app_test" app-debug-androidTest.apk
install "$restore_test" backup-data-debug-androidTest.apk
passed=0
hard=0
last_result=''
mapfile -t cases < "$candidate/cases.tsv"
for row in "${cases[@]}"; do
  read -r kind count selector extra <<< "$row"
  [[ -z "$extra" ]]
  if [[ "$kind" == death ]]; then
    require_restore_death "$count" "$selector"
    hard=$((hard + 1))
    continue
  fi
  [[ "$count" =~ ^[1-9][0-9]?$ && "$selector" =~ ^com\.deepstudent(\.[A-Za-z_][A-Za-z0-9_]*)+$ ]]
  case "$kind,$selector" in
    app,com.deepstudent.app.*) pkg=$app_test ;;
    restore,com.deepstudent.services.backup.data.*) pkg=$restore_test ;;
    *) exit 2 ;;
  esac
  require_ok "$pkg" "$count" "$selector"
  passed=$((passed + count))
done
[[ "$passed" == "$(awk '$1 != "death" { n += $2 } END { print n+0 }' "$candidate/cases.tsv")" ]]
[[ "$hard" == "$(grep -c '^death ' "$candidate/cases.tsv")" ]]
sole_device
trace=$(print_logs)
printf '%s\n' "$trace"
if [[ "$unit" == S2.12 ]]; then verify_legacy_trace <<< "$trace"; fi
printf 'unit=%s instrumentation=%d hardDeathReopens=%d skipped=0 candidateSha256=%s\n' "$unit" "$passed" "$hard" "$expected_sha"
