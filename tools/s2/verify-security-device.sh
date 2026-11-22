#!/usr/bin/env bash
# S2.13 main-thread receipt; the second device must use exactly the first candidate's bytes.
set -euo pipefail
[[ $# == 4 ]] || { echo 'usage: verify-security-device.sh SERIAL MODEL CANDIDATE EXPECTED_SHA256' >&2; exit 2; }
export ANDROID_SERIAL=$1
model=$2 candidate=$(realpath -e -- "$3") expected_sha=$4
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
adb=${ADB:-${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb}
app=com.deepstudent.app app_test=com.deepstudent.app.test
security_test=com.deepstudent.platform.security.test ui_test=com.deepstudent.features.sync.ui.test
files=(app-debug.apk app-debug-androidTest.apk security-debug-androidTest.apk sync-ui-debug-androidTest.apk cases.tsv)
sha() { sha256sum | cut -d' ' -f1; }
[[ "$expected_sha" =~ ^[a-f0-9]{64}$ && "$expected_sha" == "$(cat "$candidate/candidate.sha256")" ]]
sums=$(cd "$candidate" && sha256sum "${files[@]}")
[[ "$sums" == "$(cat "$candidate/SHA256SUMS")" && "$(printf '%s' "$sums" | sha)" == "$expected_sha" ]]
cmp -s "$candidate/cases.tsv" "$root/tools/s2/s2-13-cases.tsv"
printf 'unit=S2.13 card=S2.13/implementation candidateSha256=%s\n%s\n' "$expected_sha" "$sums"

sole_device() {
  [[ "$("$adb" devices | awk 'NR > 1 && NF { print $1, $2 }')" == "$ANDROID_SERIAL device" ]]
}
sole_device || { echo 'exactly the selected device must be connected' >&2; exit 2; }
[[ "$("$adb" shell getprop ro.product.model)" == "$model" ]]
[[ "$("$adb" shell getprop ro.product.cpu.abi)" == arm64-v8a ]]
[[ "$("$adb" shell am get-current-user)" == 0 ]]
printf 'time=%s model=%s api=%s abi=arm64-v8a\n' "$(TZ=Asia/Shanghai date -Is)" "$model" \
  "$("$adb" shell getprop ro.build.version.sdk)"

absent() {
  local result
  result=$("$adb" shell dumpsys package "$1") || return
  [[ "${result//$'\r'/}" == "Unable to find package: $1" ]]
}
installed_sha() {
  local path
  path=$("$adb" shell pm path --user 0 "$1") || return
  [[ "$path" =~ ^package:/[A-Za-z0-9_./+=~:-]+/base\.apk$ ]] || return 1
  timeout 30s "$adb" shell sha256sum "${path#package:}" | cut -d' ' -f1
}
for pkg in "$app_test" "$security_test" "$ui_test"; do
  absent "$pkg" || { printf 'refusing to replace existing test package: %s\n' "$pkg" >&2; exit 2; }
done
prior_app=$(installed_sha "$app" || true)
[[ "$prior_app" =~ ^[a-f0-9]{64}$ ]] || absent "$app"
start=$("$adb" shell 'date "+%m-%d %H:%M:%S.000"')
owned=() expected=()
cleanup() {
  local result=$? index pkg
  trap - EXIT
  sole_device || { echo 'selected device disconnected before cleanup' >&2; exit 1; }
  for ((index=${#owned[@]}-1; index>=0; index--)); do
    pkg=${owned[index]}
    if absent "$pkg"; then continue; fi
    if [[ "$(installed_sha "$pkg" || true)" != "${expected[index]}" ]]; then
      printf 'refusing to remove a different package: %s\n' "$pkg" >&2
      result=1
    else
      timeout 30s "$adb" uninstall --user 0 "$pkg" && absent "$pkg" || result=1
    fi
  done
  printf 'deviceCleanup=%d temporary packages checked; priorApp=%s\n' "${#owned[@]}" "${prior_app:+retained_in_place}"
  exit "$result"
}
trap cleanup EXIT

install() {
  local pkg=$1 file=$2 expected_hash
  local -a opts=()
  expected_hash=$(sha < "$candidate/$file")
  if [[ "$pkg" == "$app" && -n "$prior_app" ]]; then
    opts+=(-r)
  else
    owned+=("$pkg"); expected+=("$expected_hash")
  fi
  sole_device
  timeout 90s "$adb" install --no-streaming --user 0 -t "${opts[@]}" "$candidate/$file"
  [[ "$(installed_sha "$pkg")" == "$expected_hash" ]]
  printf 'installedSha256 %s %s\n' "$pkg" "$expected_hash"
}

print_trace() {
  [[ "$start" =~ ^[0-9-]+\ [0-9:.]+$ ]]
  timeout 25s "$adb" shell -n "logcat -d -b main -v brief -T '$start' -s DstuOperation:I DstuSecurity:I '*:S'" \
    | awk '/event=sync\.configuration\.|I\/DstuSecurity\(/'
}

instrument() {
  local pkg=$1 count=$2 selection=$3 result rc=0
  sole_device
  result=$(timeout 210s "$adb" shell -n am instrument -w -r --user 0 -e class "$selection" \
    "$pkg/androidx.test.runner.AndroidJUnitRunner" 2>&1) || rc=$?
  result=${result//$'\r'/}
  if (( rc != 0 )) || ! grep -Eq "^OK \(${count} tests?\)$" <<< "$result" ||
      ! grep -qx 'INSTRUMENTATION_CODE: -1' <<< "$result" ||
      grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|^INSTRUMENTATION_RESULT: shortMsg=|^INSTRUMENTATION_STATUS_CODE: -(1|2|3|4)$' <<< "$result" ||
      [[ "$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0$' <<< "$result")" != "$count" ]]; then
    printf 'instrumentation failed on %s (exit=%d)\n%s\n' "$selection" "$rc" "$result" >&2
    print_trace >&2 || true
    return 1
  fi
  printf 'instrumentation %s %s pass=%s skipped=0\n' "$pkg" "$selection" "$count"
  awk '/^INSTRUMENTATION_STATUS: (test|class)=/ { if (!seen[$0]++) print }' <<< "$result"
}

verify_trace() {
  awk '
    function field(key, i, parts) {
      for (i=1; i<=NF; i++) if (index($i,key "=")==1) { split($i,parts,"="); return parts[2] }
      return ""
    }
    /SECRET_SENTINEL_|PASSWORD_SENTINEL_|WRONG_SENTINEL_|old fixture|private-pw|https?:\/\// { bad=1 }
    /event=sync\.configuration\./ {
      id=field("correlationId"); phase=field("phase"); state=field("state"); status=field("status")
      if (id !~ /^[0-9a-f-]+$/ || length(id)!=36 || field("owner")!="sync.configuration" ||
          field("elapsedMs") !~ /^[0-9]+$/ || status !~ /^(none|[A-Z_]+)$/) bad=1
      if (state=="started") started[id]=phase
      else if (started[id]!=phase) bad=1
      if (phase=="read" && state=="failed" && status=="CREDENTIAL_KEY_LOST") lost=NR
      if (phase=="reset-lost-key" && state=="succeeded" && lost) reset=NR
      if (phase=="pair" && state=="succeeded" && reset) joined=NR
    }
    /operation=(DSBK_PREFLIGHT|DSBK_DECRYPT|MASTER_KEY_PROBE|MASTER_KEY_RESET) / {
      id=field("correlationId"); op=field("operation"); phase=field("phase"); state=field("state")
      if (length(id)!=32 || id !~ /^[0-9a-f]+$/ || field("owner")!="platform.security" ||
          field("elapsedMs") !~ /^[0-9]+$/ || phase !~ /^[A-Z0-9_]+$/) bad=1
      if (state=="STARTED") phaseStarted[id,phase]=op
      else if (phaseStarted[id,phase]!=op) bad=1
      if (op=="DSBK_PREFLIGHT" && phase=="HEADER" && state=="SUCCEEDED") header++
      if (op=="DSBK_DECRYPT" && phase=="COMMIT" && state=="SUCCEEDED") commit[id]=1
      if (op=="DSBK_DECRYPT" && phase=="V2_DECRYPT" && state=="FAILED" && field("error")=="AUTHENTICATION_FAILED") rejected[id]=1
      if (op=="MASTER_KEY_PROBE" && state=="FAILED" && field("reason")=="PERMANENTLY_LOST") lostKey++
      if (op=="MASTER_KEY_RESET" && phase=="RESET_APPLY" && state=="SUCCEEDED") resetKey++
    }
    END {
      if (bad || !lost || reset<=lost || joined<=reset) exit 1
      for (id in rejected) { if (id in commit) exit 1; refused++ }
      for (id in commit) activated++
      if (header<1 || activated<1 || refused<3 || lostKey<1 || resetKey<1) exit 1
      print "pairingTrace=verified confirmedLoss=yes localReset=yes rejoin=yes secretSentinels=absent"
      printf "securityTrace=verified headerPreflight=%d privateCommit=%d authenticationRejectedWithoutCommit=%d\n",header,activated,refused
    }
  '
}

install "$app" "${files[0]}"
install "$app_test" "${files[1]}"
install "$security_test" "${files[2]}"
install "$ui_test" "${files[3]}"
passed=0
mapfile -t cases < "$candidate/cases.tsv"
[[ ${#cases[@]} == 3 ]]
for row in "${cases[@]}"; do
  read -r group count selection extra <<< "$row"
  [[ "$count" =~ ^[1-9][0-9]*$ && "$selection" =~ ^[A-Za-z0-9_.,#]+$ && -z "$extra" ]]
  case "$group" in
    app) pkg=$app_test ;;
    platform) pkg=$security_test ;;
    ui) pkg=$ui_test ;;
    *) exit 2 ;;
  esac
  instrument "$pkg" "$count" "$selection"
  passed=$((passed + count))
done
[[ "$passed" == "$(awk '{ sum+=$2 } END { print sum+0 }' "$candidate/cases.tsv")" ]]
trace=$(print_trace)
printf '%s\n' "$trace"
verify_trace <<< "$trace"
printf 'unit=S2.13 instrumentation=%d skipped=0 candidateSha256=%s\n' "$passed" "$expected_sha"
