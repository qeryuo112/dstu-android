#!/usr/bin/env bash
# Standalone S2.29 instrumented SQLite tests. Never installs the ordinary App.
set -euo pipefail
umask 077
if [[ $# != 5 || ! "$1" =~ ^[A-Za-z0-9_.:-]+$ || ! "$4" =~ ^[a-f0-9]{64}$ ]]; then
  echo 'usage: verify-s229-publication-device.sh SERIAL MODEL TEST_APK EXPECTED_SHA256 NEW_OUTPUT_DIRECTORY' >&2
  exit 2
fi
repo=$(cd -- "$(dirname -- "$0")/../.." && pwd -P)
serial=$1 model=$2 apk=$(realpath -e -- "$3") expected=$4
output=$(realpath -m -- "$5")
if [[ "$output/" == "$repo/"* ]] || git -C "$(dirname -- "$output")" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo 'raw instrumentation evidence must stay outside a source checkout' >&2
  exit 2
fi
sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
adb_bin="$sdk/platform-tools/adb"
aapt="$sdk/build-tools/36.0.0/aapt"
package=com.deepstudent.services.sync.data.test
runner=androidx.test.runner.AndroidJUnitRunner
ordinary=com.deepstudent.app
isolated="$ordinary.s2device"
classes=(
  com.deepstudent.services.sync.data.S229PublicationAndroidTest
  com.deepstudent.services.sync.data.S229StreamingBlobAndroidTest
  com.deepstudent.services.sync.data.capture.SyncCaptureContractAndroidTest
  com.deepstudent.services.sync.data.WebDavAndroidTest
  com.deepstudent.services.sync.data.LocalSyncStoreAndroidTest
)
selection=$(IFS=,; echo "${classes[*]}")
cases=24
adb() { "$adb_bin" -s "$serial" "$@"; }
device() { adb shell -n "$@" | tr -d '\r'; }
sha_file() { sha256sum -- "$1" | cut -d ' ' -f1; }
absent() { [[ "$(device dumpsys package "$1")" == "Unable to find package: $1" ]]; }
package_state() { if absent "$1"; then echo absent; else device pm path --user 0 "$1"; fi; }
installed_sha() {
  local path
  path=$(device pm path --user 0 "$package") || return
  [[ "$path" =~ ^package:/data/app/[A-Za-z0-9_+=.,~/-]+/base\.apk$ ]] || return 1
  device sha256sum "${path#package:}" | cut -d ' ' -f1
}

[[ "$(sha_file "$apk")" == "$expected" ]]
badging=$("$aapt" dump badging "$apk")
[[ "$badging" == "package: name='$package' "* ]]
manifest=$("$aapt" dump xmltree "$apk" AndroidManifest.xml)
grep -F "android:name(0x01010003)=\"$runner\"" <<< "$manifest" >/dev/null
grep -F "android:targetPackage(0x01010021)=\"$package\"" <<< "$manifest" >/dev/null
connected=$("$adb_bin" devices -l | awk 'NR > 1 && NF { print $1, $2, $3 }')
[[ "$connected" == "$serial device usb:"* && "$connected" != *$'\n'* ]]
[[ "$(device getprop ro.product.model)" == "$model" ]]
[[ "$(device am get-current-user)" == 0 && "$(device getprop ro.product.cpu.abi)" == arm64-v8a ]]
[[ "$(device getprop ro.kernel.qemu)" != 1 && "$(device getprop ro.boot.qemu)" != 1 ]]
if ! absent "$package"; then
  echo 'refusing to replace an existing sync test installation' >&2
  exit 1
fi
ordinary_before=$(package_state "$ordinary")
isolated_before=$(package_state "$isolated")
mkdir -m 0700 -- "$output"
owned=false
cleanup() {
  local status=$? after='' installed=''
  trap - EXIT
  if $owned; then
    installed=$(installed_sha) || installed=''
    if [[ "$installed" == "$expected" ]] && timeout 30s "$adb_bin" -s "$serial" uninstall --user 0 "$package" >/dev/null && absent "$package"; then
      echo 'ownedSyncTestRemoved=true'
    else
      echo 'ownedSyncTestRemoved=unconfirmed' >&2
      status=1
    fi
  fi
  if after=$(package_state "$ordinary") && [[ "$after" == "$ordinary_before" ]] && \
     after=$(package_state "$isolated") && [[ "$after" == "$isolated_before" ]]; then
    echo 'normalAndIsolatedAppStateUnchanged=true'
  else
    echo 'normalAndIsolatedAppStateUnchanged=unconfirmed' >&2
    status=1
  fi
  exit "$status"
}
trap cleanup EXIT
printf 'time=%s model=%s android=%s api=%s abi=arm64-v8a\n' "$(TZ=Asia/Shanghai date --iso-8601=seconds)" \
  "$model" "$(device getprop ro.build.version.release)" "$(device getprop ro.build.version.sdk)"
if ! timeout 120s "$adb_bin" -s "$serial" install --no-streaming -R -t --user 0 "$apk"; then
  if [[ "$(installed_sha || true)" == "$expected" ]]; then owned=true; fi
  exit 1
fi
owned=true
[[ "$(installed_sha)" == "$expected" ]]
printf 'installedSha256=%s\n' "$expected"
timeout 180s "$adb_bin" -s "$serial" shell -n am instrument --user 0 -w -r -e class "$selection" -e s229_foreground true \
  "$package/$runner" > "$output/instrumentation.log" 2>&1
node --input-type=module - "$repo" "$output" "$selection" "$cases" <<'JS'
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
const [repo, output, selection, count] = process.argv.slice(2);
const { parseInstrumentResults } = await import(pathToFileURL(resolve(repo, 'tools/s2/s2-16-18-results.mjs')).href);
const cases = parseInstrumentResults(readFileSync(resolve(output, 'instrumentation.log'), 'utf8'), selection, Number(count));
const counts = Object.fromEntries(selection.split(',').map(cls => [cls, cases.filter(value => value.startsWith(`${cls}#`)).length]));
assert.deepEqual(Object.values(counts), [5, 1, 16, 1, 1]);
writeFileSync(resolve(output, 'cases.json'), `${JSON.stringify(cases, null, 2)}\n`, { mode: 0o600 });
console.log(JSON.stringify({ event: 's229.sync.data.device', passed: cases.length, skipped: 0, counts }));
JS
