# The ID begins at authentication and survives the durable restore journal and cold activation.
function field(key, i) {
  for (i=1; i<=NF; i++) if (index($i, key "=") == 1) return substr($i, length(key)+2)
  return ""
}
/event=backup\.(encrypted|queue|restore) / {
  id=field("correlationId"); phase=field("phase"); state=field("state"); code=field("code")
  if (id !~ /^[a-f0-9-]+$/ || length(id) != 36 || field("owner") == "" ||
      phase !~ /^[A-Za-z_-]+$/ || state !~ /^[A-Za-z_-]+$/ || field("elapsedMs") !~ /^[0-9]+$/ ||
      /fixture-password|public-test-passphrase|desktop-key-fixture|desktop-script-fixture/) bad=1
  event=field("event")
  if (event == "backup.encrypted") {
    if (field("owner") != "app.backup" || code !~ /^[A-Za-z0-9_]+$/) bad=1
    if (phase == "authenticate" && state == "verified") authenticated[id]=NR
    if (phase == "prepare" && state == "failed" && code == "AUTHENTICATION_FAILED") rejected[id]=NR
    if (phase == "write" && state == "succeeded") exported++
  }
  if (event == "backup.queue" && phase == "queue" && state == "QUEUED") queued[id]=NR
  if (event == "backup.restore" && phase == "ACTIVATE" && state == "SUCCEEDED") activated[id]=NR
  if (event == "backup.queue" && phase == "startup" && (state == "APPLIED" || state == "APPLIED_REPAIR_SYNC")) applied[id]=NR
}
END {
  for (id in rejected) {
    reject++
    if ((id in authenticated) || (id in queued) || (id in activated) || (id in applied)) bad=1
  }
  for (id in authenticated) {
    verified++
    if (queued[id]>authenticated[id] && activated[id]>queued[id] && applied[id]>activated[id]) correlated++
  }
  if (bad || reject<1 || exported<1 || correlated<4 || correlated != verified) {
    printf "encryptedTrace=failed malformed=%d authenticated=%d wrongPasswordRejected=%d resolverExport=%d correlatedActivations=%d\n", bad,verified,reject,exported,correlated > "/dev/stderr"
    exit 1
  }
  printf "encryptedTrace=verified authenticated=%d wrongPasswordRejected=%d resolverExport=%d correlatedActivations=%d\n", verified,reject,exported,correlated
}
