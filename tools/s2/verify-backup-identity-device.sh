#!/usr/bin/env bash
# S2.15 isolated candidate: Android restore plus real TLS against a controlled WebDAV repository.
set -euo pipefail
[[ $# == 4 ]] || { echo 'usage: verify-backup-identity-device.sh SERIAL MODEL CANDIDATE EXPECTED_SHA256' >&2; exit 2; }
export ANDROID_SERIAL=$1
model=$2 candidate=$(realpath -e -- "$3") expected_sha=$4
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
adb=${ADB:-${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb}
app=com.deepstudent.app app_test=com.deepstudent.app.test
identity_test=com.deepstudent.services.backup.data.test
files=(app-debug.apk app-debug-androidTest.apk backup-data-debug-androidTest.apk cases.tsv fixture-tls.p12)
sha() { sha256sum | cut -d' ' -f1; }
[[ "$expected_sha" =~ ^[a-f0-9]{64}$ && "$expected_sha" == "$(cat "$candidate/candidate.sha256")" ]]
sums=$(cd "$candidate" && sha256sum "${files[@]}")
[[ "$sums" == "$(cat "$candidate/SHA256SUMS")" && "$(printf '%s' "$sums" | sha)" == "$expected_sha" ]]
cmp -s "$candidate/cases.tsv" "$root/tools/s2/s2-15-cases.tsv"
printf 'unit=S2.15 card=S2.15/implementation candidateSha256=%s\n%s\n' "$expected_sha" "$sums"

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
for pkg in "$app_test" "$identity_test"; do
  absent "$pkg" || { printf 'refusing to replace existing test package: %s\n' "$pkg" >&2; exit 2; }
done
prior_app=$(installed_sha "$app" || true)
[[ "$prior_app" =~ ^[a-f0-9]{64}$ ]] || absent "$app"
start=$("$adb" shell 'date "+%m-%d %H:%M:%S.000"')
owned=() expected=()
fixture=cache/s215-managed
fixture_owned=false
cleanup() {
  local result=$? index pkg
  trap - EXIT
  sole_device || { echo 'selected device disconnected before cleanup' >&2; exit 1; }
  if "$fixture_owned"; then "$adb" shell -n "run-as $app rm -r $fixture" || result=1; fi
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
  timeout 25s "$adb" shell -n "logcat -d -b main -v brief -T '$start' -s DstuRestoreIdentity:I DstuBackupRestore:I DstuOperation:I '*:S'" \
    | awk '/event=backup\.(identity|queue|restore|continuity)|event=sync\.(continuity_claim|configuration\.)/'
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
    /event=backup.identity / {
      id=field("correlationId"); phase=field("phase"); state=field("state")
      if (id !~ /^[0-9a-f-]+$/ || length(id)!=36 || field("elapsedMs") !~ /^[0-9]+$/) bad=1
      if (phase=="identity-restore" && state=="COMMITTED") staged[id]=NR
      if (phase=="writer" && state=="rotated" && staged[id] && active[id]>staged[id] && NR>active[id]) rotation=1
      if (/fixture-instance|fixture-password|https?:\/\//) bad=1
    }
    /event=sync.continuity_claim / {
      id=field("correlationId"); state=field("state"); status=field("status")
      if (id !~ /^[0-9a-f-]+$/ || length(id)!=36 || field("elapsedMs") !~ /^[0-9]+$/) bad=1
      if (state=="succeeded") remote[id]=NR
      if (state=="failed" && status=="ALREADY_CLAIMED") duplicate++
    }
    /event=backup.continuity / {
      id=field("correlationId"); phase=field("phase"); state=field("state"); code=field("code")
      if (id !~ /^[0-9a-f-]+$/ || length(id)!=36 || field("elapsedMs") !~ /^[0-9]+$/ || code !~ /^[A-Za-z0-9_]+$/) bad=1
      if (phase=="claim_intent" && state=="persisted") intent[id]=NR
      if (phase=="claim" && state=="failed" && code=="REMOTE_UNAVAILABLE" && intent[id]) uncertain++
      if (phase=="claim" && state=="failed" && code=="WRITER_SEQUENCE_CONFLICT") conflict++
      if (phase=="identity-restore" && state=="COMMITTED") stagedContinuity[id]=NR
      if (phase=="identity-restore" && state=="VERIFIED") verified[id]=NR
      if (phase=="publish_writer" && state=="succeeded") published[id]=NR
      if (phase=="authority" && state=="failed" && code=="EXPIRED") expired++
      if (phase=="claim" && state=="failed" && code=="WRITER_SEQUENCE_CHANGED") stale++
    }
    /event=sync.configuration.finished / {
      id=field("correlationId"); state=field("state"); status=field("status")
      if (id !~ /^[0-9a-f-]+$/ || length(id)!=36 || field("elapsedMs") !~ /^[0-9]+$/ || field("owner")!="sync.configuration") bad=1
      if (field("phase")=="pair" && state=="succeeded") pairSuccess++
      if (field("phase")=="pair" && state=="failed" && status=="AUTHENTICATION") pairDenied++
    }
    /event=backup.queue .*phase=startup state=NEEDS_REVIEW/ {
      if (intent[field("correlationId")]) retainedReview++
    }
    /https?:\/\/|retired-source-fixture|temporary-destination-fixture/ { bad=1 }
    /event=backup.restore .*phase=ACTIVATE state=SUCCEEDED/ { active[field("correlationId")]=NR }
    /event=backup.queue .*state=PACK_CHANGED/ { rejected++ }
    END {
      for (id in remote) if (stagedContinuity[id]>remote[id] && active[id]>stagedContinuity[id] &&
          verified[id]>active[id] && published[id]>verified[id]) managed++
      if (bad || !rotation || !rejected || managed<3 || expired<1 || duplicate<1 || stale<1 ||
          conflict<2 || uncertain<1 || retainedReview<2 || pairSuccess<2 || pairDenied<1) exit 1
      print "identityTrace=verified candidateResetBeforeActivation=yes localWriterRotatedAfterActivation=yes invalidArchiveRejected=yes"
      printf "continuityTrace=verified claimedStagedActivatedAndPublished=%d duplicate=%d expired=%d stale=%d conflict=%d uncertain=%d reviewAfterIntent=%d\n",managed,duplicate,expired,stale,conflict,uncertain,retainedReview
      printf "ordinaryPairTrace=verified pairSuccess=%d oldCredentialRejected=%d\n",pairSuccess,pairDenied
    }
  '
}

install "$app" "${files[0]}"
install "$app_test" "${files[1]}"
install "$identity_test" "${files[2]}"
"$adb" shell -n "run-as $app mkdir $fixture"
fixture_owned=true
timeout 30s "$adb" shell -T "run-as $app sh -c 'cat > $fixture/fixture-tls.p12'" < "$candidate/fixture-tls.p12"
observed=$("$adb" shell -n "run-as $app sha256sum $fixture/fixture-tls.p12" | cut -d' ' -f1)
[[ "$observed" == "$(sha < "$candidate/fixture-tls.p12")" ]]
printf 'tlsFixture=loopback-only sha256=%s\n' "$observed"
passed=0
mapfile -t cases < "$candidate/cases.tsv"
[[ ${#cases[@]} == 4 ]]
for row in "${cases[@]}"; do
  read -r group count selection extra <<< "$row"
  [[ "$count" =~ ^[1-9][0-9]*$ && "$selection" =~ ^[A-Za-z0-9_.,#]+$ && -z "$extra" ]]
  case "$group" in
    app) pkg=$app_test ;;
    identity) pkg=$identity_test ;;
    *) exit 2 ;;
  esac
  instrument "$pkg" "$count" "$selection"
  passed=$((passed + count))
done
[[ "$passed" == "$(awk '{ sum+=$2 } END { print sum+0 }' "$candidate/cases.tsv")" ]]
trace=$(print_trace)
printf '%s\n' "$trace"
verify_trace <<< "$trace"
printf 'unit=S2.15 instrumentation=%d skipped=0 candidateSha256=%s\n' "$passed" "$expected_sha"
