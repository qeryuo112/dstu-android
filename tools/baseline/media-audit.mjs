import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { root, desktop, artifact, main, log } from './lib.mjs';

async function worker() {
  const media = await import('../../desktop-increment/media-time.mjs');
  const { installTemplateAssetIncrement } = await import('../../desktop-increment/templates-assets.mjs');
  const hooks = [installTemplateAssetIncrement(), media.installMediaTimeIncrement()];
  const { createStorageService } = await import(pathToFileURL(resolve(desktop, 'host/platform/storage/service.ts')).href);
  const temp = mkdtempSync(join(tmpdir(), 'media-convergence-'));
  const migrationsDir = media.prepareMediaTimeMigrations(join(temp, 'migrations'));
  const stores = [];
  try {
    const events = [
      { event_id: 'audit-time-a', device_id: 'device-a', kind: 'study', resource_id: 'public-media', logical_date: '2026-11-15', seconds: 90, observed_at: '2026-11-15T08:00:00Z' },
      { event_id: 'audit-time-b', device_id: 'device-b', kind: 'study', resource_id: 'public-media', logical_date: '2026-11-15', seconds: 30, observed_at: '2026-11-15T08:00:01Z' },
      { event_id: 'audit-resume-a', device_id: 'device-a', kind: 'resume', resource_id: 'public-media', logical_date: '2026-11-15', position_ms: 90000, duration_ms: 100000, observed_at: '2026-11-15T08:01:00Z' },
      { event_id: 'audit-resume-b', device_id: 'device-b', kind: 'resume', resource_id: 'public-media', logical_date: '2026-11-15', position_ms: 12000, duration_ms: 100000, observed_at: '2026-11-15T08:02:00Z' },
    ];
    const summaries = [];
    const migrations = [];
    for (const [index, input] of [events, [...events].reverse(), [...events, ...events]].entries()) {
      const storage = createStorageService({ baseDir: join(temp, `device-${index}`), dataLocalDir: join(temp, `local-${index}`), migrationsDir });
      stores.push(storage);
      const fresh = await storage.migrations.runAll();
      assert.equal(fresh.success, true);
      const freshVfs = fresh.databases.find(row => row.id === 'vfs');
      assert.deepEqual({ fromVersion: freshVfs.fromVersion, toVersion: freshVfs.toVersion,
        appliedCount: freshVfs.appliedCount }, { fromVersion: 0, toVersion: 20261116, appliedCount: 74 });
      const restart = await storage.migrations.runAll();
      assert.equal(restart.success, true);
      const restartVfs = restart.databases.find(row => row.id === 'vfs');
      assert.deepEqual({ fromVersion: restartVfs.fromVersion, toVersion: restartVfs.toVersion,
        appliedCount: restartVfs.appliedCount }, { fromVersion: 20261116, toVersion: 20261116, appliedCount: 0 });
      const reportFields = row => ({ id: row.id, fromVersion: row.fromVersion, toVersion: row.toVersion,
        appliedCount: row.appliedCount, success: row.success, error: row.error });
      migrations.push({ freshVfs: reportFields(freshVfs), restartVfs: reportFields(restartVfs) });
      const db = storage.database('vfs');
      for (const event of input) media.appendMediaEvent(db, event);
      summaries.push(media.mediaSummary(db, 'public-media', '2026-11-15'));
    }
    assert.deepEqual(summaries[0], summaries[1]);
    assert.deepEqual(summaries[0], summaries[2]);
    assert.equal(summaries[0].seconds, 120);
    assert.equal(summaries[0].position_ms, 12000, 'newer rewind must beat older farther position');
    assert.deepEqual(summaries[0].by_device.map(row => row.seconds), [90, 30]);
    assert.throws(() => media.appendMediaEvent(stores[0].database('vfs'), { ...events[0], seconds: 91 }), /different payload/);
    assert.doesNotThrow(() => media.normalizeMediaEvent({ ...events[0], event_id: 'offset-valid', observed_at: '2026-11-15T16:00:00+08:00' }));
    assert.throws(() => media.normalizeMediaEvent({ ...events[0], event_id: 'invalid-date', observed_at: '2026-02-30T08:00:00Z' }));
    assert.throws(() => media.normalizeMediaEvent({ ...events[3], event_id: 'unsafe-integer', duration_ms: 9007199254740992n }));
    const result = { formatVersion: 1, unit: 'S0.22', permutations: 3, duplicateEventsReplayed: true,
      expectedStudySeconds: 120, expectedResumePositionMs: 12000, summary: summaries[0],
      migrations: migrations[0],
      scope: 'production desktop increment event adapter; no scheduler/playback execution' };
    process.stdout.write(JSON.stringify(result));
  } finally {
    for (const storage of stores) storage.shutdown();
    for (const hook of hooks.reverse()) hook.unregister();
    rmSync(temp, { recursive: true, force: true });
  }
}

export async function run(mode) {
  const output = execFileSync(process.execPath, [fileURLToPath(import.meta.url), '--worker'], { cwd: root, encoding: 'utf8', maxBuffer: 4 * 1024 * 1024, stdio: ['ignore', 'pipe', 'inherit'] });
  const result = JSON.parse(output);
  artifact('config/contracts/media-audit.json', result, mode);
  log('media-audit.accepted', { unit: 'S0.22', mode, permutations: result.permutations });
}

if (process.argv[2] === '--worker') worker().catch(error => { log('media-audit.failed', { message: error.message, stack: error.stack }); process.exitCode = 1; });
else main(import.meta.url, run);
