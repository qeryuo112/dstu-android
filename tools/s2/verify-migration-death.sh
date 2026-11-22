#!/usr/bin/env bash
# Runs only against disposable databases owned by the standalone database instrumentation APK.
set -euo pipefail

if [[ $# != 3 && ( $# != 5 || "$4" != --scenario ) ]]; then
  printf 'usage: bash tools/s2/verify-migration-death.sh SERIAL MODEL DESKTOP_VFS_FIXTURE [--scenario vfs|chat_v2|mistakes|llm_usage|desktop-vfs]\n' >&2
  exit 2
fi
# A split run is explicitly scoped and is not full-matrix acceptance by itself.
scenarios=(vfs chat_v2 mistakes llm_usage desktop-vfs)
matrix_scope=all
if [[ $# == 5 ]]; then
  case "$5" in
    vfs|chat_v2|mistakes|llm_usage|desktop-vfs) scenarios=("$5"); matrix_scope=$5 ;;
    *) printf 'invalid commit-death scenario\n' >&2; exit 2 ;;
  esac
fi
export ANDROID_SERIAL="$1"
model="$2"
fixture=$(realpath -e -- "$3")
cd "$(dirname "$0")/../.."

adb="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
pkg=com.deepstudent.platform.database.test
apk=platform/database/build/outputs/apk/androidTest/debug/database-debug-androidTest.apk
if [[ -n "${DSTU_S2_APK_DIR:-}" ]]; then
  apk="$(realpath -e -- "$DSTU_S2_APK_DIR")/database-debug-androidTest.apk"
fi
selection=com.deepstudent.platform.database.MigrationCommitDeathAndroidTest#checkpoint
run="s203-death-$(date +%s)-$$"
remote="cache/$run"
lock=config/schema/final-lock.json
repair=config/schema/s2-04-repair.json
sha() { sha256sum | cut -d' ' -f1; }
run_as() { "$adb" exec-out run-as "$pkg" --user 0 "$@"; }
installed_path() { "$adb" shell pm path --user 0 "$pkg"; }

# These reads are advisory evidence only, not part of the acceptance gate.
exit_window_start() {
  local clock
  clock=$(timeout 4s "$adb" shell date '+%s%3N,%z' 2>/dev/null) || clock=''
  clock=${clock//$'\r'/}
  if [[ "$clock" =~ ^[0-9]{13},[+-][0-9]{4}$ ]]; then printf '%s' "$clock"; else printf '0,unknown'; fi
}
collect_exit_diagnostics() {
  local window=$1
  if ! timeout 20s node tools/s2/collect-process-exit.mjs --adb "$adb" --serial "$ANDROID_SERIAL" \
      --package "$pkg" --since-ms "${window%%,*}" --since-tz "${window#*,}" >&2; then
    printf '{"type":"collector","status":"failed_or_timeout"}\n' >&2
  fi
}
connected=$("$adb" devices | awk 'NR > 1 && NF { print $1, $2 }')
[[ "$connected" == "$ANDROID_SERIAL device" ]]
[[ "$("$adb" shell getprop ro.product.model)" == "$model" ]]
[[ "$("$adb" shell getprop ro.product.cpu.abi)" == arm64-v8a ]]
[[ -z "$(installed_path)" ]] || { printf 'refusing to replace a pre-existing database test APK\n' >&2; exit 1; }
want=$(sha < "$apk")
printf 'time=%s model=%s api=%s abi=arm64-v8a\n' "$(TZ=Asia/Shanghai date --iso-8601=seconds)" \
  "$model" "$("$adb" shell getprop ro.build.version.sdk)"

cleanup() {
  local status=$?
  trap - EXIT
  if [[ "$("$adb" get-state 2>/dev/null || true)" != device ]]; then
    printf 'device disconnected before test-package cleanup\n' >&2
    exit 1
  fi
  if [[ -n "$(installed_path)" ]]; then
    timeout 20s "$adb" uninstall --user 0 "$pkg" || status=1
    [[ -z "$(installed_path)" ]] || status=1
  fi
  exit "$status"
}
trap cleanup EXIT

timeout 120s "$adb" install --no-streaming --user 0 -r "$apk"
installed=$(installed_path)
[[ "$installed" == package:* && "$installed" != *$'\n'* ]]
have=$("$adb" shell sha256sum "${installed#package:}" | cut -d' ' -f1)
[[ "$want" == "$have" ]]
printf 'installedSha256 %s %s\n' "$pkg" "$have"
"$adb" shell run-as "$pkg" --user 0 mkdir "$remote"
run_as mkdir "$remote/desktop-vfs" "$remote/vfs" "$remote/chat_v2" "$remote/mistakes" "$remote/llm_usage"
"$adb" shell -T "run-as $pkg --user 0 sh -c 'cat > $remote/desktop-vfs/database.db'" < "$fixture"
want=$(sha < "$fixture")
have=$(run_as sha256sum "$remote/desktop-vfs/database.db" | cut -d' ' -f1)
[[ "$want" == "$have" ]]
printf 'isolatedDesktopOldDbSha256=%s\n' "$have"

instrument() {
  timeout 60s "$adb" shell am instrument --user 0 -w -r -e class "$selection" \
    -e s203Run "$run" -e s203Database "$database" -e s203Origin "$origin" \
    -e s203Operation "$1" -e s203Before "$2" -e s203Kind "$3" \
    "$pkg/androidx.test.runner.AndroidJUnitRunner"
}

require_death() {
  local operation="$1" before="$2" kind="$3" version="$4" count="$5"
  local output status=0 mark actual_kind actual_database actual_count actual_version pid prior_count prior_sha window marker_status=0
  window=$(exit_window_start)
  output=$(instrument "$operation" "$before" "$kind" 2>&1) || status=$?
  if (( status > 1 )) || ! grep -q '^INSTRUMENTATION_RESULT: shortMsg=Process crashed\.$' <<< "$output" || \
    grep -Eq '^OK \(|FAILURES!!!|INSTRUMENTATION_STATUS_CODE: -(2|3|4)$' <<< "$output"; then
    printf 'expected a hard death: %s %s version=%s exit=%s\n%s\n' "$scenario" "$kind" "$version" "$status" "$output" >&2
    collect_exit_diagnostics "$window"
    return 1
  fi
  mark=$(run_as cat "$remote/$scenario/commit") || marker_status=$?
  if (( marker_status != 0 )); then
    printf 'missing or unreadable hard-death checkpoint: %s kind=%s expectedVersion=%s status=%s\n' "$scenario" "$kind" "$version" "$marker_status" >&2
    collect_exit_diagnostics "$window"
    return "$marker_status"
  fi
  IFS=$'\t' read -r actual_kind actual_database actual_count actual_version pid prior_count prior_sha <<< "$mark"
  if [[ "$actual_kind" != "$kind" || "$actual_database" != "$database" || "$actual_count" != "$count" || \
    "$actual_version" != "$version" || "$prior_count" != "$before" || ! "$pid" =~ ^[0-9]+$ || \
    ! "$prior_sha" =~ ^[a-f0-9]{64}$ || "$pid" == "$last_pid" ]]; then
    printf 'invalid hard-death marker: %s kind=%s expectedVersion=%s marker=%s\n' "$scenario" "$kind" "$version" "$mark" >&2
    collect_exit_diagnostics "$window"
    return 1
  fi
  # The runner's generic "Process crashed" is only the planned-death protocol marker, not a cause.
  # The checkpoint and actual disappearance are still required, unchanged from the original gate.
  if [[ "$("$adb" shell "test ! -d /proc/$pid && echo gone")" != gone ]]; then
    printf 'hard-death checkpoint pid still present or could not be checked: pid=%s\n' "$pid" >&2
    collect_exit_diagnostics "$window"
    return 1
  fi
  last_pid=$pid
  printf 'commitDeath scenario=%s kind=%s count=%s version=%s pid=%s priorCount=%s priorLedgerSha256=%s\n' \
    "$scenario" "$kind" "$count" "$version" "$pid" "$before" "$prior_sha"
}

verify_head() {
  local kind="$1" output receipt id actual_kind actual_count version pid ledger fingerprint applied governance window receipt_status=0
  window=$(exit_window_start)
  if ! output=$(instrument verify "$count" "$kind" 2>&1); then
    printf '%s\n' "$output" >&2
    collect_exit_diagnostics "$window"
    return 1
  fi
  if ! grep -qx 'OK (1 test)' <<< "$output" || ! grep -qx 'INSTRUMENTATION_CODE: -1' <<< "$output" || \
    grep -Eq '^INSTRUMENTATION_STATUS_CODE: -(2|3|4)$' <<< "$output"; then
    printf 'head reopen failed: %s %s\n%s\n' "$scenario" "$kind" "$output" >&2
    collect_exit_diagnostics "$window"
    return 1
  fi
  receipt=$(run_as cat "$remote/$scenario/verified") || receipt_status=$?
  if (( receipt_status != 0 )); then
    printf 'head reopen receipt missing or unreadable: %s %s status=%s\n' "$scenario" "$kind" "$receipt_status" >&2
    collect_exit_diagnostics "$window"
    return "$receipt_status"
  fi
  IFS=$'\t' read -r id actual_kind actual_count version pid ledger fingerprint applied governance <<< "$receipt"
  if [[ "$id" != "$database" || "$actual_kind" != "$kind" || "$actual_count" != "$count" || "$version" != "$head" || \
    ! "$pid" =~ ^[0-9]+$ || "$pid" == "$last_pid" || ! "$ledger" =~ ^[a-f0-9]{64}$ || \
    "$fingerprint" != "$expected_fingerprint" || "$applied" != restartApplied=0 || "$governance" != governanceUnchanged=true ]]; then
    printf 'head reopen receipt invalid: %s %s\n' "$scenario" "$kind" >&2
    collect_exit_diagnostics "$window"
    return 1
  fi
  printf 'reopened scenario=%s after=%s count=%s version=%s pid=%s ledgerWithTimestampsSha256=%s fingerprint=%s %s %s\n' \
    "$scenario" "$kind" "$count" "$version" "$pid" "$ledger" "$fingerprint" "$applied" "$governance"
}

fresh=0
old=0
legacy=0
for scenario in "${scenarios[@]}"; do
  database=$scenario origin=fresh initial=0 last_pid=''
  if [[ "$scenario" == desktop-vfs ]]; then
    database=vfs origin=desktop
    initial=$(jq -er '.fixture.vfsMigrationCount.before' config/baseline/schema-upgrade.json)
  fi
  # The frozen S0 receipt stays immutable; only the approved append changes the effective VFS head.
  info=$(jq -ce --arg id "$database" --slurpfile repair "$repair" '
    .databases[] | select(.id == $id) |
    if .id == $repair[0].database then
      if .version != $repair[0].before.version or .fingerprint != $repair[0].before.fingerprint or
        .migrationCount != $repair[0].before.migrationCount then error("repair anchor mismatch") else . end |
      .version = $repair[0].after.version | .fingerprint = $repair[0].after.fingerprint |
      .migrationCount = $repair[0].after.migrationCount |
      .history += [{version: $repair[0].migration.version, name: $repair[0].migration.name,
        checksum: $repair[0].migration.refineryChecksum}]
    else . end' "$lock")
  count=$(jq -er '.migrationCount' <<< "$info")
  head=$(jq -er '.version' <<< "$info")
  expected_fingerprint=$(jq -er '.fingerprint' <<< "$info")
  versions_text=$(jq -er '.history[].version' <<< "$info")
  mapfile -t versions <<< "$versions_text"
  [[ "${#versions[@]}" == "$count" && "${versions[count-1]}" == "$head" ]]
  for (( before=initial; before<count; before++ )); do
    require_death advance "$before" MIGRATION "${versions[before]}" "$(( before + 1 ))"
  done
  verify_head MIGRATION
  if [[ "$origin" == fresh ]]; then
    fresh=$(( fresh + count ))
    require_death governance "$count" GOVERNANCE "$head" "$count"
    verify_head GOVERNANCE
    legacy=$(( legacy + 1 ))
  else
    old=$(( old + count - initial ))
  fi
done
printf 'commitDeathMatrix model=%s scope=%s freshSql=%s desktopVfsSql=%s legacyGovernance=%s checkedAfterReopen=%s skipped=0\n' \
  "$model" "$matrix_scope" "$fresh" "$old" "$legacy" "$(( fresh + old + legacy ))"
