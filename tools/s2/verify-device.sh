#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 3 && ( $# != 4 || ( "$4" != --resume-owned-database-test && "$4" != --s203 ) ) ]]; then
  printf 'usage: bash tools/s2/verify-device.sh SERIAL MODEL DESKTOP_FIXTURE [--resume-owned-database-test|--s203]\n' >&2
  exit 2
fi
export ANDROID_SERIAL="$1"
model="$2"
fixture=$(realpath -e -- "$3")
mode=${4:-}
cd "$(dirname "$0")/../.."

adb="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
app_package=com.deepstudent.app
database_package=com.deepstudent.platform.database
fixture_dir=cache/s2-desktop-vfs
base=app/build/outputs/apk/debug/app-debug.apk
inst=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
mod=platform/database/build/outputs/apk/androidTest/debug/database-debug-androidTest.apk
if [[ -n "${DSTU_S2_APK_DIR:-}" ]]; then
  apk_dir=$(realpath -e -- "$DSTU_S2_APK_DIR")
  base="$apk_dir/app-debug.apk"
  inst="$apk_dir/app-debug-androidTest.apk"
  mod="$apk_dir/database-debug-androidTest.apk"
fi
test_packages=("$database_package.test" "$app_package.test")
connected=$("$adb" devices | awk 'NR > 1 && NF { print $1, $2 }')
[[ "$connected" == "$ANDROID_SERIAL device" ]]
actual_model=$("$adb" shell getprop ro.product.model)
abi=$("$adb" shell getprop ro.product.cpu.abi)
[[ "$actual_model" == "$model" && "$abi" == arm64-v8a ]]
printf 'time=%s model=%s api=%s abi=%s\n' "$(TZ=Asia/Shanghai date --iso-8601=seconds)" \
  "$actual_model" "$("$adb" shell getprop ro.build.version.sdk)" "$abi"
start=$("$adb" shell 'date "+%m-%d %H:%M:%S.000"')

installed_path() { "$adb" shell pm path --user 0 "$1" || true; }
sha() { sha256sum | cut -d' ' -f1; }
verify_install() {
  local package="$1" file="$2" apk want have
  apk=$(installed_path "$package")
  [[ "$apk" == package:* && "$apk" != *$'\n'* ]] || return 1
  want=$(sha < "$file")
  have=$("$adb" shell sha256sum "${apk#package:}" | cut -d' ' -f1)
  [[ "$want" == "$have" ]] || return 1
  printf 'installedSha256 %s %s\n' "$package" "$have"
}
install() {
  printf 'install=%s\n' "$1"
  timeout 120s "$adb" install --no-streaming --user 0 -r "$1"
}
app_present=$(installed_path "$app_package")
reuse_database=false
for package in "${test_packages[@]}"; do
  if [[ -n "$(installed_path "$package")" ]]; then
    if [[ "$package" == "$database_package.test" && "$mode" == --resume-owned-database-test ]] && verify_install "$package" "$mod"; then
      printf 'databaseTest=verified_previous_install\n'
      reuse_database=true
      continue
    fi
    printf 'refusing to replace existing test package: %s\n' "$package" >&2
    exit 1
  fi
done

owns_fixture=false
cleanup() {
  local code=$?
  trap - EXIT
  if [[ "$("$adb" get-state 2>/dev/null || true)" != device ]]; then
    printf 'device disconnected before cleanup\n' >&2
    exit 1
  fi
  if $owns_fixture; then
    "$adb" shell run-as "$app_package" --user 0 rm -rf "$fixture_dir" || code=1
  fi
  for package in "${test_packages[@]}"; do
    if [[ -n "$(installed_path "$package")" ]]; then
      "$adb" uninstall --user 0 "$package" || code=1
      [[ -z "$(installed_path "$package")" ]] || code=1
    fi
  done
  if [[ -n "$app_present" ]]; then
    printf 'ordinaryApp=retained_in_place\n'
  elif [[ -n "$(installed_path "$app_package")" ]]; then
    "$adb" uninstall --user 0 "$app_package" || code=1
    [[ -z "$(installed_path "$app_package")" ]] || code=1
    printf 'ordinaryApp=temporary_removed\n'
  fi
  exit "$code"
}
trap cleanup EXIT

instrument() {
  local package="$1" expected="$2" selection="$3" output
  printf 'instrument=%s expected=%s class=%s\n' "$package" "$expected" "$selection"
  if ! output=$(timeout 90s "$adb" shell am instrument --user 0 -w -r -e class "$selection" \
    "$package/androidx.test.runner.AndroidJUnitRunner" 2>&1); then
    printf '%s\ninstrumentation transport failed or timed out\n' "$output" >&2
    return 1
  fi
  printf '%s\n' "$output"
  ! rg -q 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=|INSTRUMENTATION_STATUS_CODE: -(3|4)$' <<< "$output" || return 1
  rg -qx "OK \(${expected} tests?\)" <<< "$output" || return 1
  rg -qx 'INSTRUMENTATION_CODE: -1' <<< "$output"
}
sha256sum "$base" "$inst" "$mod"
if ! $reuse_database; then
  install "$mod"
  verify_install "$database_package.test" "$mod"
fi
baseline="$database_package.BundledSqliteAndroidProbeTest,$database_package.DatabaseRegistryAndroidTest,$database_package.MigrationAndroidTest"
instrument "$database_package.test" 14 "$baseline"
# Separate process per case: the tablet OEM suspended a long headless instrumentation run.
for method in freshAndRestartPreserveGovernanceAndHistory vfs20261111UpgradeMatchesFrozenFixture \
  driftPreservesExistingDatabaseAndLocatesObjects untrustedHistoryAndOrphanManagedObjectsAreRejected \
  migrationAndLedgerRollbackTogether foreignKeyRebuildIsAtomic; do
  instrument "$database_package.test" 1 "$database_package.MigrationS202AndroidTest#$method"
done

install "$base"
install "$inst"
verify_install "$app_package" "$base"
verify_install "$app_package.test" "$inst"
instrument "$app_package.test" 1 "$app_package.storage.WalStorageCompositionDeviceTest"
instrument "$app_package.test" 2 "$app_package.storage.NotesResourcesDeviceTest"
instrument "$app_package.test" 1 "$app_package.runtime.RuntimeDeviceTest"
for method in mainProcessUsesOneMainGraphDuringStartupRecovery \
  engineProbeRunsInRealEngineProcessWithoutBusinessResources isolatedProbeRunsInIsolatedProcessWithoutBusinessResources; do
  instrument "$app_package.test" 1 "$app_package.graph.ProcessGraphDeviceTest#$method"
done
"$adb" shell run-as "$app_package" --user 0 mkdir "$fixture_dir"
owns_fixture=true
"$adb" shell run-as "$app_package" --user 0 mkdir -p "$fixture_dir/active/databases"
db_path="$fixture_dir/active/databases/vfs.db"
"$adb" shell -T "run-as $app_package --user 0 sh -c 'cat > $db_path'" < "$fixture"
want=$(sha < "$fixture")
have=$("$adb" exec-out run-as "$app_package" --user 0 sha256sum "$db_path" | cut -d' ' -f1)
[[ "$want" == "$have" ]]
printf 'isolatedDesktopOldDbSha256=%s\n' "$have"
instrument "$app_package.test" 1 "$app_package.storage.DesktopVfsUpgradeDeviceTest#desktopVfsOpensAndUpgradesThroughAppStorage"

if [[ "$mode" == --s203 ]]; then
  instrument "$app_package.test" 1 "$app_package.ui.MigrationMaintenanceUiDeviceTest"
  instrument "$app_package.test" 1 "$app_package.storage.MigrationStartupDeviceTest"
fi

"$adb" logcat -d -T "$start" -s DstuRegistryTest:I DstuStorage:I DstuDesktopUpgrade:I '*:S'
