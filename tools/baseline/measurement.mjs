import assert from 'node:assert/strict';
import { artifact, main, readJson, log } from './lib.mjs';

export function percentile(samples, fraction) {
  assert.ok(samples.length > 0 && samples.every(value => Number.isFinite(value) && value >= 0));
  assert.ok(fraction > 0 && fraction <= 1);
  return [...samples].sort((a, b) => a - b)[Math.ceil(samples.length * fraction) - 1];
}

export async function run(mode) {
  const input = readJson('config/measurement-inputs.json');
  const release = readJson('config/release-contract.json');
  const profiles = input.deviceProfiles;
  assert.equal(new Set(profiles.map(profile => profile.id)).size, 5);
  for (const profile of profiles) {
    assert.equal(profile.abi, release.abi.shipped[0]);
    assert.ok(profile.api[0] >= release.platform.minSdk);
    assert.ok(profile.requiredUnits.length > 0 && profile.source);
    if (profile.observedDevice === null) assert.equal(profile.status, 'procurement-required');
    else {
      const device = input.inventory.devices.find(device => device.id === profile.observedDevice);
      assert.ok(device && device.assignedProfiles.includes(profile.id));
      assert.equal(device.abi, profile.abi);
      assert.ok(device.api >= profile.api[0] && device.api <= profile.api[1]);
      assert.ok(device.memoryTotalKiB / 1024 >= profile.ramMiB[0] && device.memoryTotalKiB / 1024 <= profile.ramMiB[1]);
      assert.equal(device.pageSize, profile.pageSize);
      assert.ok(device.smallestWidthDp >= profile.smallestWidthDp[0] && device.smallestWidthDp <= profile.smallestWidthDp[1]);
    }
  }
  assert.ok(profiles.some(profile => profile.pageSize === 16384));
  assert.ok(profiles.some(profile => profile.permissions.includes('root-user-approved')));
  assert.equal(input.runRecord.executedAndroidRuns, 0);
  assert.equal(percentile([9, 1, 5, 3, 7], 0.5), 5);
  assert.equal(percentile(Array.from({ length: 20 }, (_, i) => i + 1), 0.95), 19);
  assert.throws(() => percentile([], 0.95));
  assert.throws(() => percentile([NaN], 0.95));
  assert.throws(() => percentile([1], 0));
  for (const metric of input.measurements) {
    assert.ok(metric.iterations >= 3 && metric.warmup > 0 && metric.harness);
  }
  artifact('config/baseline/measurement.json', {
    ...input,
    checks: { profiles: profiles.length, measurementProtocols: input.measurements.length,
      percentileCases: 5, connectedDevicesAtObservation: input.inventory.adbDevices.length,
      avdsAtObservation: input.inventory.avds.length, androidPerformanceClaimed: false },
  }, mode);
  log('measurement.accepted', { unit: 'S0.17', mode, profiles: profiles.length, androidRuns: 0 });
}

main(import.meta.url, run);
