import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { startWebDavFixture } from './sync-webdav-fixture.mjs';
import { androidFixtureBytes, androidFixtureHash, encryptionFixturePassword, fixtureBytes, fixtureContent,
  openDesktopFixture } from './sync-notes-desktop.mjs';
import { bindDisposableReverse, classifyAdbFailure } from './benchmark-device-lib.mjs';
import { assertNoCanaries, assertSameObjects, mutationCount } from './sync-notes-wire.mjs';

// Public canaries only. Never pass real credentials or a real WebDAV endpoint to this harness.
const project = fileURLToPath(new URL('../../', import.meta.url));
const sdk = process.env.ANDROID_HOME ?? resolve(homedir(), 'Android/Sdk');
const adb = resolve(sdk, 'platform-tools/adb');
const candidate = process.env.DSTU_S2_APK_DIR;
const app = candidate ? resolve(candidate, 'app-debug.apk') : resolve(project, 'app/build/outputs/apk/debug/app-debug.apk');
const test = candidate ? resolve(candidate, 'app-debug-androidTest.apk') : resolve(project, 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk');
const args = process.argv.slice(2);
const flags = new Set(args);
assert.ok(flags.size === args.length && args.every(flag => ['--encrypted', '--reuse-verified-app'].includes(flag)),
  'unexpected roundtrip argument');
const encrypted = flags.has('--encrypted');
const reuseVerifiedApp = flags.has('--reuse-verified-app');
assert.ok(existsSync(app) && existsSync(test), 'build app and androidTest APKs first');
const connected = execFileSync(adb, ['devices'], { encoding: 'utf8' }).split('\n').filter(line => /\tdevice$/u.test(line));
assert.equal(connected.length, 1, 'exactly one device is required');
const serial = connected[0].split('\t')[0];
const quietAdb = (args, encoding = 'utf8') => {
  try { return execFileSync(adb, ['-s', serial, ...args], { encoding, timeout: 30_000,
    maxBuffer: 256 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] }); }
  catch (error) { throw Object.assign(new Error('ADB fixture operation failed'),
    { s127ChildFailure: classifyAdbFailure(error, { reverse: args[0] === 'reverse' }) }); }
};
const user = quietAdb(['shell', 'am', 'get-current-user']).trim();
assert.match(user, /^\d+$/u, 'could not determine current Android user');
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const packageApks = [['com.deepstudent.app', app], ['com.deepstudent.app.test', test]].map(([pkg, apk]) => {
  const badging = execFileSync(resolve(sdk, 'build-tools/36.0.0/aapt'), ['dump', 'badging', apk], { encoding: 'utf8' });
  assert.ok(badging.match(/^package: name='([^']+)'/mu)?.[1] === pkg, 'unexpected fixture APK identity');
  const reuse = reuseVerifiedApp && pkg === 'com.deepstudent.app' &&
    quietAdb(['shell', 'pm', 'path', '--user', user, pkg]).trim() !== '';
  if (!reuse) assert.ok(quietAdb(['shell', 'dumpsys', 'package', pkg]).trim() === `Unable to find package: ${pkg}`,
    'fixture packages must be absent for all Android users; refusing to replace or clear existing data');
  const entry = { pkg, apk, sha256: digest(readFileSync(apk)), reuse };
  if (reuse) verifyInstalled(entry); // Never reinstall, clear data or uninstall the existing App in this mode.
  return entry;
});
function verifyInstalled({ pkg, sha256 }) {
  const path = quietAdb(['shell', 'pm', 'path', '--user', user, pkg]).trim();
  assert.ok(/^package:\/data\/app\/[a-zA-Z0-9_+=.,~\/-]+\.apk$/u.test(path), 'expected exactly one installed APK');
  assert.ok(digest(quietAdb(['exec-out', 'cat', path.slice('package:'.length)], null)) === sha256, 'installed APK hash mismatch');
}

const root = mkdtempSync(join(tmpdir(), 'sync-notes-roundtrip-'));
const fixture = await startWebDavFixture();
const canaries = [fixture.username, fixture.password, encryptionFixturePassword, 'incorrect fixture',
  'incorrect stored fixture', fixtureContent, 'Android edited note: 中文 😀', 'Android retried batch',
  Buffer.from(`${fixture.username}:${fixture.password}`).toString('base64')];
const id = randomUUID();
const stage = encrypted ? 's1-26' : 's1-25';
const installed = [];
let ready = false;
let desktop, tunnel, deviceEndpoint, seeded, secondNote, desktopSequence = 0n, androidDevice;
let activeStep = 'setup', failed = null, androidFrame;

const run = async (args, label) => {
  const child = spawn(adb, ['-s', serial, ...args], { stdio: ['ignore', 'pipe', 'pipe'] });
  const chunks = [];
  let size = 0, exceeded = false;
  for (const stream of [child.stdout, child.stderr]) stream.on('data', chunk => {
    size += chunk.length;
    if (size > 4 * 1024 * 1024) { exceeded = true; child.kill('SIGTERM'); }
    else chunks.push(chunk);
  });
  const timer = setTimeout(() => child.kill('SIGTERM'), 240_000);
  let code;
  try { code = await new Promise((done, reject) => { child.once('error', reject); child.once('close', done); }); }
  finally { clearTimeout(timer); }
  const output = Buffer.concat(chunks).toString('utf8');
  // Keep process output in memory only; do not accidentally publish a failure's secrets or note content.
  assertNoCanaries(output, canaries);
  androidFrame = output.match(/\bat com\.deepstudent\.app\.sync\.[A-Za-z0-9_.$]+\((?:PlainNotesRoundtripDeviceTest|EncryptedSyncRetry)\.kt:\d+\)/u)?.[0];
  assert.ok(!exceeded, `${label} output exceeded the fixture limit`);
  assert.equal(code, 0, `${label} failed`);
  return output;
};
const phase = async name => {
  activeStep = name;
  const output = await run(['shell', 'am', 'instrument', '--user', user, '-w', '-r', '-e', 'class', 'com.deepstudent.app.sync.PlainNotesRoundtripDeviceTest',
    '-e', 'testId', id, '-e', 'phase', name, '-e', 'endpoint', deviceEndpoint, '-e', 'noteId', seeded?.noteId ?? 'cleanup',
    '-e', 'repository', stage, '-e', 'secondNote', secondNote ?? 'unused', '-e', 'desktopSeq', desktopSequence.toString(),
    '-e', 'blobHash', seeded?.hash ?? 'cleanup', 'com.deepstudent.app.test/androidx.test.runner.AndroidJUnitRunner'], `instrumentation ${name}`);
  assert.doesNotMatch(output, /FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=/u);
  assert.match(output, /OK \(1 test\)/u);
  assert.match(output, /INSTRUMENTATION_CODE: -1/u);
  // Filter only this app's sync tags, never clear the shared device log and never print it.
  await run(['logcat', '-d', '-v', 'brief', '-s', 'DstuOperation:I', 'DstuSyncTest:I', '*:S'], 'sync log scan');
  console.log(JSON.stringify({ event: 's1.sync.fixture.phase', phase: name, result: 'verified' }));
};

function verifyEncryptedWire(objects) {
  const relevant = [...objects].filter(([key]) => key.startsWith(`/dav/${stage}/data_governance/`) &&
    !key.endsWith('/format.json') && !key.endsWith('/instance.json'));
  assert.ok(relevant.length >= 4, 'missing encrypted data-governance objects');
  for (const [key, bytes] of relevant) {
    assert.equal(bytes.subarray(0, 4).toString('ascii'), 'DSBK', 'unencrypted sync object');
    assert.equal(bytes[4], key.includes('/data_governance/blobs/') ? 2 : 1, 'unexpected DSBK version');
  }
  for (const bytes of objects.values()) assertNoCanaries(bytes, canaries);
}

async function inventory(objects = fixture.snapshot()) {
  const groups = await desktop.inspectChanges(objects);
  const desktopBatches = groups.get(desktop.deviceId) ?? [];
  desktopSequence = desktopBatches.at(-1)?.sequence ?? 0n;
  const others = [...groups.keys()].filter(device => device !== desktop.deviceId);
  assert.ok(others.length <= 1, 'unexpected fixture writer');
  if (others.length) {
    if (androidDevice) assert.equal(others[0], androidDevice, 'Android writer changed identity');
    androidDevice = others[0];
  }
  return { groups, desktopBatches, androidBatches: androidDevice ? groups.get(androidDevice) : [] };
}

async function assertManifestWatermarks(objects, groups) {
  const manifests = await desktop.inspectManifests(objects);
  for (const [device, batches] of groups) {
    assert.ok(manifests.has(device), 'missing device manifest');
    assert.equal(BigInt(manifests.get(device).published_max_seq), batches.at(-1).sequence, 'manifest publication sequence mismatch');
  }
}

function assertReceived(received, batches, noteId) {
  assert.ok(batches.length > 0, 'Android did not publish a new batch');
  const last = batches.at(-1).sequence;
  const lastChange = received.downloaded.changes.find(change => change.record_id === noteId &&
    change.source_device_id === androidDevice && BigInt(change.source_seq) === last);
  assert.ok(lastChange, 'desktop did not receive the expected Android note and sequence');
  assert.equal(received.downloaded.cursor_advancements.get(androidDevice), last, 'desktop download advancement mismatch');
  assert.equal(received.cursors.get(androidDevice), last, 'desktop committed cursor mismatch');
}

async function verifyRetry() {
  const before = await inventory();
  const priorAndroidSeq = before.androidBatches.at(-1).sequence;
  await phase('reserve');
  const reservedObjects = fixture.snapshot();
  const reserved = await inventory(reservedObjects);
  assert.equal(reserved.androidBatches.length, before.androidBatches.length + 1, 'exactly one reserved batch expected');
  const batch = reserved.androidBatches.at(-1);
  assert.equal(batch.sequence, priorAndroidSeq + 1n);
  const reservedBytes = reservedObjects.get(batch.path);
  assert.ok(reservedBytes, 'reserved batch missing from WebDAV');
  assert.ok(batch.changes.some(change => change.record_id === seeded.noteId), 'reserved batch does not contain the edited note');

  const oldDesktopSeq = desktopSequence;
  secondNote = await desktop.secondNote();
  const beforeWrong = fixture.snapshot();
  const pending = await inventory(beforeWrong);
  assert.equal(desktopSequence, oldDesktopSeq + 1n);
  assert.ok(pending.desktopBatches.at(-1).changes.some(change => change.record_id === secondNote));
  const mutations = mutationCount(fixture.counts());
  await phase('retry-wrong');
  assertSameObjects(beforeWrong, fixture.snapshot());
  assert.equal(mutationCount(fixture.counts()), mutations, 'wrong password attempted a remote mutation');

  await phase('retry-correct');
  const afterCorrect = fixture.snapshot();
  assert.ok(afterCorrect.get(batch.path)?.equals(reservedBytes), 'reserved ciphertext changed on retry');
  const after = await inventory(afterCorrect);
  assert.equal(after.androidBatches.length, reserved.androidBatches.length, 'retry published a second batch');
  assert.equal(after.androidBatches.at(-1).sequence, batch.sequence);
  await assertManifestWatermarks(afterCorrect, after.groups);
  const received = await desktop.receive();
  assertReceived(received, [batch], seeded.noteId);
  assert.equal(desktop.notes.getNoteWithContent(seeded.noteId, desktop.db)[1], 'Android retried batch');
  verifyEncryptedWire(afterCorrect);
}

try {
  activeStep = 'desktop-seed';
  desktop = await openDesktopFixture(root, fixture, encrypted);
  seeded = await desktop.seed();
  const seededInventory = await inventory();
  assert.equal(desktopSequence, 1n, 'desktop seed must be sequence one');
  assert.ok(seededInventory.desktopBatches[0].changes.some(change => change.record_id === seeded.noteId));
  activeStep = 'reverse-allocate';
  const binding = await bindDisposableReverse(quietAdb, fixture.port);
  tunnel = binding.tunnel;
  deviceEndpoint = `http://127.0.0.1:${binding.devicePort}/dav/`;
  activeStep = 'fresh-apk-install';
  for (const entry of packageApks) {
    if (!entry.reuse) {
      installed.push(entry); // Cleanup verifies this expected candidate even if install returned late.
      const output = await run(['install', '-R', '-t', '--user', user, entry.apk], 'fixture APK install');
      assert.ok(/^Success\s*$/mu.test(output), 'fixture APK install did not report success');
    }
    verifyInstalled(entry);
  }
  ready = true;
  console.log(JSON.stringify({ event: 's1.sync.fixture.installed-apks', appSha256: packageApks[0].sha256,
    testSha256: packageApks[1].sha256, noReplacement: true, reusedApp: packageApks[0].reuse }));
  for (const name of ['edit', 'delete', 'restore', 'repeat']) {
    const before = await inventory();
    await phase(name);
    const objects = fixture.snapshot();
    const after = await inventory(objects);
    const newAndroid = after.androidBatches.slice(before.androidBatches.length);
    const received = await desktop.receive();
    if (name === 'repeat') {
      assert.equal(newAndroid.length, 0, 'repeat must not publish a batch');
      assert.equal(received.downloaded.changes.length, 0, 'repeat must not echo');
    } else assertReceived(received, newAndroid, seeded.noteId);
    await assertManifestWatermarks(objects, after.groups);
    const note = desktop.notes.getNoteIncludingDeleted(seeded.noteId, desktop.db);
    assert.ok(note, 'desktop note exists');
    assert.equal(note.deletedAt != null, name === 'delete');
    if (name !== 'delete') assert.equal(desktop.notes.getNoteWithContent(seeded.noteId, desktop.db)[1], 'Android edited note: 中文 😀\n');
    const relation = desktop.db.get('SELECT resource_id FROM note_learning_relations WHERE id = ?1', [seeded.relationId]);
    assert.equal(relation?.resource_id, seeded.resourceId);
    desktop.verifyAndroidAttachment(seeded.noteId, seeded.resourceId);
    if (name === 'edit') {
      const hashes = await desktop.inspectBlobHashes(objects);
      assert.equal(hashes.get(seeded.hash), fixtureBytes.length, 'desktop blob wire hash/size mismatch');
      assert.equal(hashes.get(androidFixtureHash), androidFixtureBytes.length, 'Android blob wire hash/size mismatch');
      if (encrypted) verifyEncryptedWire(objects);
    }
  }
  if (encrypted) {
    await verifyRetry();
    await phase('create');
    await desktop.verifyAndroidMarker('s1-26-android-create');
  }
  console.log(JSON.stringify({ event: 's1.sync.notes.roundtrip.verified', encrypted,
    desktopSequence: desktopSequence.toString(), androidSequence: (await inventory()).androidBatches.at(-1).sequence.toString(),
    desktopBlobSha256: seeded.hash, androidBlobSha256: androidFixtureHash, requests: fixture.counts(), desktopDiagnostics: desktop.diagnosticCounts }));
} catch (error) {
  failed = { step: activeStep, type: ['AssertionError', 'Error', 'TypeError', 'RangeError'].includes(error?.name) ? error.name : 'Error', frame: androidFrame };
} finally {
  // Cleanup failures are also reported without raw process output or exception messages.
  try { if (ready) { packageApks.forEach(verifyInstalled); await phase('cleanup'); } }
  catch { failed ??= { step: 'cleanup', type: 'Error' }; }
  for (const entry of installed.reverse()) {
    try {
      if (!quietAdb(['shell', 'pm', 'path', '--user', user, entry.pkg]).trim()) continue;
      verifyInstalled(entry);
      const output = await run(['uninstall', '--user', user, entry.pkg], 'fixture APK cleanup');
      assert.ok(/^Success\s*$/mu.test(output));
    } catch {
      console.error(JSON.stringify({ event: 's1.sync.fixture.apk-cleanup.unconfirmed', package: entry.pkg }));
      failed ??= { step: 'apk-cleanup', type: 'Error' };
    }
  }
  try { if (tunnel) await run(['reverse', '--remove', tunnel], 'adb reverse cleanup'); }
  catch { failed ??= { step: 'reverse-cleanup', type: 'Error' }; }
  try { desktop?.close(); } catch { failed ??= { step: 'desktop-cleanup', type: 'Error' }; }
  try { await fixture.close(); } catch { failed ??= { step: 'fixture-cleanup', type: 'Error' }; }
  rmSync(root, { recursive: true, force: true });
}
if (failed) {
  console.error(JSON.stringify({ event: 's1.sync.notes.roundtrip.failed', ...failed }));
  process.exitCode = 1;
}
