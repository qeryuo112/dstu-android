// One install session per device, followed by independent, exact instrumentation dispatches.
import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { bindDisposableReverse } from '../s1/benchmark-device-lib.mjs';
import { verifyRestoreRoundtrip } from './s2-16-host-roundtrip.mjs';
import { appId, isolatedArguments, withIsolatedDevice } from './isolated-device-session.mjs';
import { inspectApplyTrace } from './s2-30-results.mjs';

const restoreTest = 'com.deepstudent.app.backup.RestoreBaselineDeviceTest';
const corpus = 'cache/s2-remediation';

async function editCheckpoint(device, running, verifyAndroidEdit) {
  const ready = 's230-android-attachment-ready';
  const checked = 's230-desktop-readback';
  const deadline = Date.now() + 100_000;
  while (Date.now() < deadline) {
    if (await device.shell(`run-as ${appId} sh -c 'if test -f ${corpus}/${ready}; then echo ready; fi'`) === 'ready') {
      await verifyAndroidEdit();
      await device.shell('run-as', appId, 'touch', `${corpus}/${checked}`);
      return;
    }
    await Promise.race([delay(300), running.then(() => { throw new Error('instrumentation exited before desktop checkpoint'); })]);
  }
  throw new Error('timeout waiting for the new Android attachment');
}

async function runRestore(device) {
  const sha256ByFixture = {};
  const roundtrip = await verifyRestoreRoundtrip(async ({ root, parameters, port, verifyAndroidEdit }) => {
    for (const name of ['frozen-desktop-vfs.db', 'fixture-blob']) sha256ByFixture[name] = await device.copyFixture(join(root, name));
    const binding = await bindDisposableReverse(device.adb, port);
    try {
      const values = { ...parameters, ENDPOINT: `http://127.0.0.1:${binding.devicePort}/dav/` };
      const args = Object.fromEntries(Object.entries(values).map(([key, value]) => [`s216_${key}`, value]));
      const running = device.instrument('restore-reconfigured', `${restoreTest}#coldRestoreRepairsWriterAndSyncsNewChangesWithFrozenDesktop`,
        { ...args, s230_attachments: 'true' });
      try {
        await editCheckpoint(device, running, verifyAndroidEdit);
        await running;
      } catch (error) {
        // An assertion failure must not make cleanup race with a still-running instrumentation.
        await running.catch(() => {});
        throw error;
      }
    } finally {
      await device.adb(['reverse', '--remove', binding.tunnel]);
    }
  }, 'physical-android', { attachments: true });
  return { ...roundtrip, sha256ByFixture };
}

async function verify(device) {
  await device.instrument('restore-four-databases', `${restoreTest}#allFourDatabasesResetOnlyInTheCandidateAndKeepNewChangesOnReopen`);
  await device.instrument('restore-failed-database', `${restoreTest}#lastDatabaseFailureKeepsTheActiveSlotAndCanBeRetried`);
  await device.instrument('incoming-production', 'com.deepstudent.app.sync.ProductionApplyDeviceTest#migratedDomainRowsRoundtripThroughProductionFactoryWithNoUploadEcho');
  await device.instrument('incoming-replay', 'com.deepstudent.app.sync.ProductionApplyDeviceTest#lastOwnerFailureKeepsCursorAndReplayDoesNotRepeatCommittedRows');
  await device.instrument('incoming-preferences', 'com.deepstudent.app.sync.ApplyEdgesDeviceTest#portablePreferencesRoundtripViaTheirSeparateWireContractAndMigratedRows');
  await device.instrument('incoming-stream-cancel', 'com.deepstudent.app.sync.ApplyEdgesDeviceTest#interruptedDownloadCleansRealFileStageAndRetryMaterializesVerifiedBytes');
  await device.instrument('query-invalidation', 'com.deepstudent.app.sync.SyncEntryDeviceTest#transferSurvivesPageRemovalAndReturnsResult');
  const restore = await runRestore(device);
  await writeFile(join(device.out, 'desktop-roundtrip.json'), `${JSON.stringify(restore, null, 2)}\n`, { flag: 'wx', mode: 0o600 });
  assert.equal(restore.attachments, 'bidirectional');
  assert.match(device.since, /^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.000$/u);
  const raw = await device.shell(`logcat -d --uid=${device.uid} -b main -v tag -T '${device.since}' -s DstuOperation '*:S'`);
  const { lines, ...trace } = inspectApplyTrace(raw);
  await writeFile(join(device.out, 'apply-trace.log'), `${lines.join('\n')}\n`, { flag: 'wx', mode: 0o600 });
  return { restore, trace };
}

const receipt = await withIsolatedDevice(isolatedArguments(process.argv.slice(2)), verify);
console.log(JSON.stringify({ event: 's2.30.physical', model: receipt.model, passed: receipt.cases.length,
  skipped: 0, apkSetSha256: receipt.apkSetSha256 }));
