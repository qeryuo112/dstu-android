#!/usr/bin/env bash
# Compare each device's actual export against the independently generated desktop source.
set -euo pipefail
[[ $# == 4 ]] || { echo 'usage: verify-encrypted-backup-device.sh SERIAL MODEL CANDIDATE SHA256' >&2; exit 2; }
export ANDROID_SERIAL=$1
model=$2 candidate=$(realpath -e -- "$3") expected_sha=$4
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
adb=${ADB:-${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb}
app=com.deepstudent.app test=com.deepstudent.app.test
corpus=cache/s214-interop
inputs=(controlled-full.zip desktop-portable-v2.zip desktop-portable-v1-converted.zip desktop-portable-v1-to-v2.zip)
sha() { sha256sum | cut -d' ' -f1; }
files=(app-debug.apk app-debug-androidTest.apk cases.tsv "${inputs[@]}" oracle.json)
sums=$(cd "$candidate" && sha256sum "${files[@]}")
[[ "$expected_sha" =~ ^[a-f0-9]{64}$ && "$expected_sha" == "$(cat "$candidate/candidate.sha256")" ]]
[[ "$sums" == "$(cat "$candidate/SHA256SUMS")" && "$(printf '%s' "$sums" | sha)" == "$expected_sha" ]]
cmp -s "$candidate/cases.tsv" "$root/tools/s2/s2-14-cases.tsv"
sole() { [[ "$("$adb" devices | awk 'NR>1 && NF {print $1,$2}')" == "$ANDROID_SERIAL device" ]]; }
absent() { [[ "$("$adb" shell -n dumpsys package "$1")" == "Unable to find package: $1" ]]; }
installed_sha() {
    local path
    path=$("$adb" shell -n pm path --user 0 "$1") || return
    [[ "$path" =~ ^package:/[A-Za-z0-9_./+=~:-]+/base\.apk$ ]] || return 1
    timeout 30s "$adb" shell -n sha256sum "${path#package:}" | cut -d' ' -f1
}
sole || { echo 'connect only the selected device' >&2; exit 2; }
[[ "$("$adb" shell -n getprop ro.product.model)" == "$model" && "$("$adb" shell -n getprop ro.product.cpu.abi)" == arm64-v8a ]]
[[ "$("$adb" shell -n am get-current-user)" == 0 ]]
absent "$test" || { echo 'existing instrumentation package will not be replaced' >&2; exit 2; }
prior=$(installed_sha "$app" || true)
[[ "$prior" =~ ^[a-f0-9]{64}$ ]] || absent "$app"
start=$("$adb" shell -n 'date "+%m-%d %H:%M:%S.000"')
[[ "$start" =~ ^[0-9-]+\ [0-9:.]+$ ]]
work=$(mktemp -d "${TMPDIR:-/tmp}/s214-device.XXXXXX")
owned=() hashes=() own_corpus=false
cleanup() {
    local result=$? i
    trap - EXIT
    rm -r -- "$work"
    sole || { echo 'device disconnected before cleanup' >&2; exit 1; }
    if "$own_corpus"; then "$adb" shell -n "run-as $app rm -r '$corpus'" || result=1; fi
    for ((i=${#owned[@]}-1;i>=0;i--)); do
        if absent "${owned[i]}"; then continue; fi
        if [[ "$(installed_sha "${owned[i]}" || true)" != "${hashes[i]}" ]]; then
            echo 'refusing to remove different installation' >&2; result=1
        else timeout 30s "$adb" uninstall --user 0 "${owned[i]}" && absent "${owned[i]}" || result=1
        fi
    done
    printf 'deviceCleanup=%d temporary packages checked; test corpus removed\n' "${#owned[@]}"
    exit "$result"
}
trap cleanup EXIT
printf 'time=%s model=%s api=%s unit=S2.14 card=S2.14/implementation candidateSha256=%s\n%s\n' \
    "$(TZ=Asia/Shanghai date -Is)" "$model" "$("$adb" shell -n getprop ro.build.version.sdk)" "$expected_sha" "$sums"
install() {
    local pkg=$1 file=$2 hash
    local -a opts=()
    hash=$(sha < "$candidate/$file")
    if [[ "$pkg" == "$app" && -n "$prior" ]]; then opts+=(-r); else owned+=("$pkg"); hashes+=("$hash"); fi
    sole
    timeout 90s "$adb" install --no-streaming --user 0 -t "${opts[@]}" "$candidate/$file"
    [[ "$(installed_sha "$pkg")" == "$hash" ]]
    printf 'installedSha256 %s %s\n' "$pkg" "$hash"
}
install "$app" "${files[0]}"
install "$test" "${files[1]}"
"$adb" shell -n "run-as $app mkdir '$corpus'"
own_corpus=true
for file in "${inputs[@]}"; do
    sole
    timeout 30s "$adb" shell -T "run-as $app sh -c 'cat > $corpus/$file'" < "$candidate/$file"
    observed=$("$adb" shell -n "run-as $app sha256sum '$corpus/$file'" | cut -d' ' -f1)
    [[ "$observed" == "$(sha < "$candidate/$file")" ]]
    printf 'deviceFixture %s %s\n' "$file" "$observed"
done
mapfile -t cases < "$candidate/cases.tsv"
count=0
for spec in "${cases[@]}"; do
    read -r n selection extra <<< "$spec"
    [[ "$n" =~ ^[1-9][0-9]*$ && "$selection" =~ ^[A-Za-z0-9_.,#]+$ && -z "$extra" ]]
    sole
    rc=0
    stdout=$(timeout 200s "$adb" shell -n am instrument -w -r --user 0 -e class "$selection" \
        "$test/androidx.test.runner.AndroidJUnitRunner" 2>&1) || rc=$?
    stdout=${stdout//$'\r'/}
    if (( rc != 0 )) || ! grep -Eq "^OK \($n tests?\)$" <<< "$stdout" ||
        ! grep -qx 'INSTRUMENTATION_CODE: -1' <<< "$stdout" ||
        grep -Eq 'FAILURES!!!|^INSTRUMENTATION_RESULT: shortMsg=|^INSTRUMENTATION_STATUS_CODE: -(1|2|3|4)$' <<< "$stdout" ||
        [[ "$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0$' <<< "$stdout")" != "$n" ]]; then
        printf 'instrumentation failed on %s (exit=%s)\n%s\n' "$selection" "$rc" "$stdout" >&2; exit 1
    fi
    count=$((count + n)); printf 'instrumentation %s pass=%s skipped=0\n' "$selection" "$n"
    awk '/^INSTRUMENTATION_STATUS: (test|class)=/ { if (!seen[$0]++) print }' <<< "$stdout"
done
[[ "$count" == "$(awk '{ n+=$1 } END { print n+0 }' "$candidate/cases.tsv")" ]]
"$adb" exec-out run-as "$app" cat "$corpus/android-export.zip" > "$work/android-export.zip"
[[ -s "$work/android-export.zip" ]]
printf 'androidExportSha256=%s\n' "$(sha < "$work/android-export.zip")"
S214_TEST_PASSWORD=fixture-password "${NODE:-node}" "$root/tools/s2/s2-14-desktop-interop.mjs" verify-android \
    --input "$work/android-export.zip" --password-env S214_TEST_PASSWORD --expect-zip "$candidate/controlled-full.zip"
trace=$(timeout 25s "$adb" shell -n "logcat -d -b main -v brief -T '$start' -s DstuBackupCrypto:I DstuBackupRestore:I '*:S'")
awk '/event=backup\.(encrypted|restore|queue)/' <<< "$trace"
awk -f "$root/tools/s2/s2-14-restore-trace.awk" <<< "$trace"
printf 'unit=S2.14 instrumentation=%d skipped=0 candidateSha256=%s\n' "$count" "$expected_sha"
