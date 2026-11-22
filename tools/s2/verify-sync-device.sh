#!/usr/bin/env bash
# Same ordinary debug candidate, one ARM64 device at a time. No user data is cleared.
set -euo pipefail

if [[ $# != 4 || ( "$4" != --capture && "$4" != --capture-and-changes ) ]]; then
  printf 'usage: bash tools/s2/verify-sync-device.sh SERIAL MODEL DESKTOP_CHANGE_ORACLE [--capture|--capture-and-changes]\n' >&2
  printf '  --capture-and-changes requires DSTU_S2_ORACLE_SHA256 pinned to the same fixture on every device.\n' >&2
  printf '  Optional: DSTU_S2_APK_DIR and DSTU_S2_CANDIDATE_SHA256 pin the four-APK candidate set.\n' >&2
  exit 2
fi
export ANDROID_SERIAL="$1"
model="$2"
fixture=$(realpath -e -- "$3")
mode="$4"
cd "$(dirname "$0")/../.."

adb="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
app=com.deepstudent.app
app_test=$app.test
sync_test=com.deepstudent.services.sync.data.test
db_test=com.deepstudent.platform.database.test
base=app/build/outputs/apk/debug/app-debug.apk
inst=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
module=services/sync/data/build/outputs/apk/androidTest/debug/data-debug-androidTest.apk
database=platform/database/build/outputs/apk/androidTest/debug/database-debug-androidTest.apk
if [[ -n "${DSTU_S2_APK_DIR:-}" ]]; then
  options=$(realpath -e -- "$DSTU_S2_APK_DIR")
  base=$options/app-debug.apk
  inst=$options/app-debug-androidTest.apk
  module=$options/data-debug-androidTest.apk
  database=$options/database-debug-androidTest.apk
fi
cache=cache/s2-change-log-oracle
sha() { sha256sum | cut -d' ' -f1; }
globally_absent() {
  local info
  info=$("$adb" shell dumpsys package "$1") || return
  [[ "${info//$'\r'/}" == "Unable to find package: $1" ]]
}
path_of() {
  local path status=0
  path=$("$adb" shell pm path --user 0 "$1") || status=$?
  if (( status != 0 )); then
    # Some devices return 1 for an absent package. Verify global absence rather than hiding an ADB error.
    if (( status == 1 )) && [[ -z "$path" ]] && globally_absent "$1"; then
      return 0
    fi
    return "$status"
  fi
  printf '%s' "$path"
}

# Device-clock window is advisory evidence only; a clock/collector failure never changes the test gate.
exit_window_start() {
  local clock
  clock=$(timeout 4s "$adb" shell date '+%s%3N,%z' 2>/dev/null) || clock=''
  clock=${clock//$'\r'/}
  if [[ "$clock" =~ ^[0-9]{13},[+-][0-9]{4}$ ]]; then printf '%s' "$clock"; else printf '0,unknown'; fi
}
collect_exit_diagnostics() {
  local target=$1 window=$2
  # Diagnostics must finish before the EXIT trap uninstalls owned packages, even if Node/ADB fails.
  if ! timeout 20s node tools/s2/collect-process-exit.mjs --adb "$adb" --serial "$ANDROID_SERIAL" \
      --package "$target" --since-ms "${window%%,*}" --since-tz "${window#*,}" >&2; then
    printf '{"type":"collector","status":"failed_or_timeout"}\n' >&2
  fi
}

# Pin the *same* oracle across invocations/devices before touching either device.
fixture_sha=$(sha < "$fixture")
if [[ "$mode" == --capture-and-changes ]]; then
  if [[ ! "${DSTU_S2_ORACLE_SHA256:-}" =~ ^[0-9a-f]{64}$ || "$fixture_sha" != "${DSTU_S2_ORACLE_SHA256:-}" ]]; then
    printf 'DSTU_S2_ORACLE_SHA256 is missing, malformed or does not match the oracle\n' >&2
    exit 2
  fi
fi
for apk in "$base" "$inst" "$module" "$database"; do
  [[ -f "$apk" && -s "$apk" ]] || { printf 'candidate APK missing or empty: %s\n' "$apk" >&2; exit 2; }
done
base_sha=$(sha < "$base")
inst_sha=$(sha < "$inst")
module_sha=$(sha < "$module")
database_sha=$(sha < "$database")
candidate_sha=$(printf 'app=%s\nappTest=%s\nsyncTest=%s\ndatabaseTest=%s\n' \
  "$base_sha" "$inst_sha" "$module_sha" "$database_sha" | sha)
if [[ -n "${DSTU_S2_CANDIDATE_SHA256:-}" && "$candidate_sha" != "$DSTU_S2_CANDIDATE_SHA256" ]]; then
  printf 'DSTU_S2_CANDIDATE_SHA256 does not match the four-APK candidate\n' >&2
  exit 2
fi
printf 'candidateSha256=%s oracleSha256=%s\n' "$candidate_sha" "$fixture_sha"
connected=$("$adb" devices | awk 'NR > 1 && NF { print $1, $2 }')
[[ "$connected" == "$ANDROID_SERIAL device" ]]
[[ "$("$adb" shell getprop ro.product.model)" == "$model" ]]
[[ "$("$adb" shell getprop ro.product.cpu.abi)" == arm64-v8a ]]
[[ "$("$adb" shell am get-current-user)" == 0 ]]
for package in "$app_test" "$sync_test" "$db_test"; do
  if ! globally_absent "$package"; then
    printf 'refusing to replace existing test package: %s\n' "$package" >&2
    exit 1
  fi
done
installed_app=$(path_of "$app")
if [[ -z "$installed_app" ]]; then
  # Refuse if hidden or belonging to another Android user: a temporary install must be ours to remove.
  globally_absent "$app"
fi
owns_fixture=false
printf 'time=%s model=%s api=%s abi=arm64-v8a\n' "$(TZ=Asia/Shanghai date --iso-8601=seconds)" "$model" \
  "$("$adb" shell getprop ro.build.version.sdk)"
started=$("$adb" shell 'date "+%m-%d %H:%M:%S.000"')

cleanup() {
  local status=$?
  trap - EXIT
  if [[ "$("$adb" get-state 2>/dev/null || true)" != device ]]; then
    printf 'device disconnected before cleanup\n' >&2
    exit 1
  fi
  if $owns_fixture; then
    "$adb" shell -T "run-as $app --user 0 sh -c 'if [ -f $cache/fixture.json ]; then unlink $cache/fixture.json; fi'" || status=1
    "$adb" shell run-as "$app" --user 0 rmdir "$cache" || status=1
  fi
  for package in "$app_test" "$sync_test" "$db_test"; do
    if [[ -n "$(path_of "$package")" ]]; then
      timeout 30s "$adb" uninstall --user 0 "$package" || status=1
      [[ -z "$(path_of "$package")" ]] || status=1
    fi
  done
  if [[ -n "$installed_app" ]]; then
    printf 'ordinaryApp=retained_in_place\n'
  elif [[ -n "$(path_of "$app")" ]]; then
    timeout 30s "$adb" uninstall --user 0 "$app" || status=1
    [[ -z "$(path_of "$app")" ]] || status=1
    printf 'ordinaryApp=temporary_removed\n'
  fi
  exit "$status"
}
trap cleanup EXIT

install_candidate() {
  local apk=$1 package=$2 expected=$3 installed actual
  [[ "$(sha < "$apk")" == "$expected" ]]
  timeout 120s "$adb" install --no-streaming --user 0 -r "$apk"
  installed=$(path_of "$package")
  [[ "$installed" == package:* && "$installed" != *$'\n'* ]]
  actual=$("$adb" shell sha256sum "${installed#package:}" | cut -d' ' -f1)
  [[ "$actual" == "$expected" && "$(sha < "$apk")" == "$expected" ]]
  printf 'installedSha256 %s %s\n' "$package" "$actual"
}

instrument() {
  local package=$1 expected=$2 selection=$3 output status=0 window target
  shift 3
  # Standalone library test APKs run in their own processes. App instrumentation runs in the app.
  target=$package
  if [[ "$package" == "$app_test" ]]; then target=$app; fi
  printf 'instrument=%s expected=%s class=%s\n' "$package" "$expected" "$selection"
  window=$(exit_window_start)
  output=$(timeout 120s "$adb" shell am instrument --user 0 -w -r -e class "$selection" "$@" \
    "$package/androidx.test.runner.AndroidJUnitRunner" 2>&1) || status=$?
  if (( status != 0 )); then
    printf '%s\ninstrumentation transport failed or timed out\n' "$output" >&2
    collect_exit_diagnostics "$target" "$window"
    return 1
  fi
  # Preserve the existing runner transcript; only the new collector redacts device logs.
  printf '%s\n' "$output"
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|^INSTRUMENTATION_RESULT: shortMsg=|^INSTRUMENTATION_STATUS_CODE: -(2|3|4)$' <<< "$output"; then
    printf 'instrumentation reported failure or unexpected short message\n' >&2
    collect_exit_diagnostics "$target" "$window"
    return 1
  fi
  # These are exactly the original three success checks; diagnostic failure does not affect them.
  if ! grep -qx "OK (${expected} tests\?)" <<< "$output" || \
      ! grep -qx 'INSTRUMENTATION_CODE: -1' <<< "$output" || \
      [[ "$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0$' <<< "$output")" != "$expected" ]]; then
    printf 'instrumentation returned an incomplete or unexpected result\n' >&2
    collect_exit_diagnostics "$target" "$window"
    return 1
  fi
}

# Start a fresh instrumentation process per case (OEM can freeze a headless grouped run).
install_candidate "$database" "$db_test" "$database_sha"
for method in freshAndRestartApplyRepair oldHeadUpgradePreservesRowsAndLogThenDelete \
  rejectsUntrustedInputsWithoutOpening rejectsOldHeadDriftWithoutChangingData; do
  instrument "$db_test" 1 "com.deepstudent.platform.database.CoordinatedRepairAndroidTest#$method"
done
install_candidate "$module" "$sync_test" "$module_sha"
for method in frozenRegistryAndAllFourRealSchemasAgree effectiveSchemaVersionsAreDefensiveAndUnmodifiable \
  rowSyncCrudAndLocalCrudAreIsolatedInRealDatabases coordinatedEntitiesPreserveObservedCaptureSemantics \
  pluginCrudRecordsTheSameLogicalKeyThroughTombstoneAndDelete repairedTriggerHashMatchesStoredSqlAndOldFrozenTriggerDrifts \
  injectedBadDeleteIsDiagnosedWithoutLosingRowOrLog missingAndAlteredCaptureAreLocatedWithoutValues \
  extraCaptureAndCompetingTriggerAreRejectedWithoutLeakingUnknownName changedChangeLogDefinitionFailsBeforeSynchronization \
  unknownTriggerOnChangeLogFailsWithoutLeakingName wrongCompositePrimaryKeyIsLocated \
  scopeAllowsOnlyRegisteredConsumption unexpectedReadFailureBlocksEveryConsumptionScope \
  sourceBytesAreCheckedWithoutLeakingSuppliedDetails callerCannotMisidentifyARealSharedDatabase; do
  instrument "$sync_test" 1 "com.deepstudent.services.sync.data.capture.SyncCaptureContractAndroidTest#$method"
done
install_candidate "$base" "$app" "$base_sha"
install_candidate "$inst" "$app_test" "$inst_sha"
instrument "$app_test" 1 "$app.sync.CapturePreflightDeviceTest#captureDriftStopsTheRealTransferBeforeAnyNetworkOrJournal"
for method in realFactoryReopensIsolatedConfigurationAndCredential realFactoryPerformsHttpJoin; do
  instrument "$app_test" 1 "$app.sync.MainSyncResourcesDeviceTest#$method"
done
instrument "$app_test" 1 "$app.storage.MigrationStartupDeviceTest#ordinaryMainActivityEntersWorkspaceWithGovernedDatabase"
if [[ "$mode" == --capture-and-changes ]]; then
  "$adb" shell run-as "$app" --user 0 mkdir "$cache"
  owns_fixture=true
  "$adb" shell -T "run-as $app --user 0 sh -c 'cat > $cache/fixture.json'" < "$fixture"
  have=$("$adb" exec-out run-as "$app" --user 0 sha256sum "$cache/fixture.json" | cut -d' ' -f1)
  [[ "$fixture_sha" == "$have" && "$(sha < "$fixture")" == "$fixture_sha" ]]
  printf 'desktopChangeOracleSha256=%s\n' "$have"
  for method in frozenDesktopVfsResourcesProduceSameChangesAndWireOrder \
    frozenDesktopChatCompositeProduceSameChangesAndWireOrder \
    repairedDesktopVfsPluginDeletesProduceSameChangesAndWireOrder; do
    instrument "$app_test" 1 "$app.sync.DesktopChangeLogOracleDeviceTest#$method" -e oracleSha256 "$have"
  done
fi
"$adb" logcat -d -T "$started" -s DstuSyncCapture:I DstuS204Test:I DstuS205Oracle:I '*:S'
