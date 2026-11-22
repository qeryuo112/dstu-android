import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import {
  artifact, desktop, desktopRequire, git, log, main, readDesktop, root, sha256,
} from './lib.mjs';
import { loadDesktopModule } from './desktop-module.mjs';

const FORMAT = 'dstu-s0.18-sync-golden/v2';
const UNIT = 'S0.18';
const DESKTOP_COMMIT = 'e6b2657fd59014a73f5bbb1d14c2db732cf23e87';
const encoder = new TextEncoder();
const bytesHash = bytes => sha256(Buffer.from(bytes));
const textBytes = text => encoder.encode(text);
const hex = bytes => Buffer.from(bytes).toString('hex');
const load = file => import(pathToFileURL(resolve(desktop, file)).href);

function wireValue(value) {
  if (typeof value === 'bigint') return { $bigint: value.toString() };
  if (value instanceof Map) return [...value].map(([key, item]) => [key, wireValue(item)]);
  if (Array.isArray(value)) return value.map(wireValue);
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, wireValue(item)]));
  }
  return value;
}

function outputRecord(text, extra = {}) {
  const bytes = textBytes(text);
  return { text, bytes: bytes.length, sha256: bytesHash(bytes), utf8Hex: hex(bytes), ...extra };
}

function sourceRecord(file) {
  return { file, sha256: sha256(readFileSync(resolve(desktop, file))) };
}

function sourceRecords(files) {
  return files.map(sourceRecord);
}

function errorRecord(run) {
  try {
    const value = run();
    return { rejected: false, value: wireValue(value) };
  } catch (error) {
    return { rejected: true, name: error?.name ?? 'Error', message: error instanceof Error ? error.message : String(error) };
  }
}

async function errorRecordAsync(run) {
  try {
    const value = await run();
    return { rejected: false, value: wireValue(value) };
  } catch (error) {
    return { rejected: true, name: error?.name ?? 'Error', message: error instanceof Error ? error.message : String(error) };
  }
}

async function loadApi() {
  const loaded = await loadDesktopModule(`
    export { parseJsonLossless, stringifyJsonLossless, JsonFloat, isJsonFloat } from './host/platform/util/lossless-json.ts';
    export { parseJsonDocument, parseJsonDocumentBytes } from './host/platform/util/json-exact.ts';
    export { compareUtf8, decodeUtf8Strict, encodeUtf8, utf8ByteLength, validUtf8Prefix } from './host/platform/util/utf8.ts';
    export {
      changeOperationAsStr, changeOperationFromStr,
    } from './host/plugins/sync/src/change-log.ts';
    export {
      parseSyncChange, serializeSyncChange,
    } from './host/plugins/sync/src/change-payload.ts';
    export {
      parseChangesPayload, parseLegacyPendingChanges, unsigned,
    } from './host/plugins/sync/src/download-payload.ts';
    export {
      buildChangeKeyV3, buildChangeKeyV4, parseChangeKey, parseVersionFromKey,
      normalizeVersionToSeconds, pathIdMatchesDevice, missingSequenceIsProven,
    } from './host/plugins/sync/src/change-keys.ts';
    export {
      canonicalLwwKey, compareLwwTimestamps, timestampValueToLwwString,
      lwwDevicePair, lwwTimestampMillis, parseFlexibleTimestamp,
    } from './host/plugins/sync/src/lww.ts';
    export { Hlc, HlcClock, MAX_HLC_COUNTER, MAX_HLC_MILLIS } from './host/plugins/sync/src/hlc.ts';
    export {
      fieldMergeColumnsForTable, fieldMergeTables, mergeField, jsonEquals,
    } from './host/plugins/sync/src/field-merge.ts';
    export {
      parseSyncManifest, deviceManifestKey, deviceManifestLegacyKey,
      DIVERGED_CHECKSUM_SENTINEL,
    } from './host/plugins/sync/src/remote-manifest.ts';
    export {
      parseFormatDescriptor, descriptorToJson, CURRENT_FORMAT_VERSION,
      MIN_COMPATIBLE_FORMAT_VERSION, FORMAT_KEY, validateRemoteFormat,
    } from './host/plugins/sync/src/remote-format.ts';
    export {
      createManifest, detectConflicts, applyMergeStrategy,
    } from './host/plugins/sync/src/merge.ts';
    export { deviceIdShortHash, normalizeDeviceId } from './host/plugins/sync-providers/src/device_id.ts';
    export {
      readUnknownFields, readUnknownFieldsForRoundTrip, vaultUnknownFields,
      vaultUnknownFieldsWithVersion, rehydrateUnknownFieldsIfColumnsExist,
    } from './host/plugins/sync/src/unknown-fields.ts';
    export { getRecordData } from './host/plugins/sync/repository/record-data.ts';
  `);
  return loaded;
}

function sourceBindings(loaded) {
  const implementation = [
    'host/plugins/sync/repository/unknown-fields.ts',
    'host/plugins/sync/repository/record-data.ts',
  ];
  const wrappers = [
    'host/plugins/sync/src/unknown-fields.ts',
  ];
  const protocol = [
    'host/platform/util/lossless-json.ts',
    'host/platform/util/json-exact.ts',
    'host/platform/util/utf8.ts',
    'host/platform/util/portable.ts',
    'host/platform/crypto/sync.ts',
    'host/plugins/sync/src/change-log.ts',
    'host/plugins/sync/src/change-payload.ts',
    'host/plugins/sync/src/download-payload.ts',
    'host/plugins/sync/src/change-keys.ts',
    'host/plugins/sync/src/lww.ts',
    'host/plugins/sync/src/hlc.ts',
    'host/plugins/sync/src/field-merge.ts',
    'host/plugins/sync/src/remote-manifest.ts',
    'host/plugins/sync/src/remote-format.ts',
    'host/plugins/sync/src/merge.ts',
    'host/plugins/sync/src/classification.ts',
    'host/plugins/sync-providers/src/device_id.ts',
  ];
  const inputs = new Set(loaded.inputs.map(source => source.file));
  for (const file of [...implementation, ...wrappers, ...protocol]) {
    assert.ok(inputs.has(file), `${file}: imported but absent from esbuild inputs`);
  }
  const wrapperText = readDesktop(wrappers[0]);
  assert.equal(wrapperText, "export * from '../repository/unknown-fields.ts';\n",
    'unknown-fields wrapper must stay a transparent re-export');
  return {
    commit: DESKTOP_COMMIT,
    head: git(desktop, 'rev-parse', 'HEAD'),
    implementation: sourceRecords(implementation),
    wrappers: sourceRecords(wrappers),
    protocol: sourceRecords(protocol),
    buildInputsSha256: sha256(JSON.stringify(loaded.inputs)),
    buildInputs: loaded.inputs.length,
    baselineV1BuildInputs: 121,
    wrapperSplit: 'S0.18 v1 imported unknown-field logic from src/unknown-fields.ts; the frozen desktop tree moved it to repository/unknown-fields.ts and left the src module as a re-export wrapper.',
  };
}

function productionBoundary(api) {
  const source = readDesktop('host/plugins/sync/src/plugin.ts');
  const runSyncSource = readDesktop('host/plugins/sync/src/run-sync.ts');
  const match = source.match(/commands:\s*\[([\s\S]*?)\]/u);
  assert.ok(match, 'sync plugin command list');
  const commands = [...match[1].matchAll(/'([^']+)'/gu)].map(item => item[1]);
  assert.ok(commands.includes('data_governance_run_sync'));
  assert.ok(!commands.some(command => /v5/iu.test(command)), 'v5 must stay absent from the registered command surface');
  assert.match(source, /runSync\(direction/u);
  assert.doesNotMatch(match[0], /runSyncV5/u);
  assert.match(runSyncSource, /export async function runSyncV5\(/u);
  return {
    registeredCommands: commands,
    registeredCommandCount: commands.length,
    sources: {
      plugin: sourceRecord('host/plugins/sync/src/plugin.ts'),
      runSync: sourceRecord('host/plugins/sync/src/run-sync.ts'),
    },
    v5: {
      registered: false,
      activeFormat: api.CURRENT_FORMAT_VERSION,
      minimumReader: api.MIN_COMPATIBLE_FORMAT_VERSION,
      reason: 'runSyncV5 is exported from api.ts but is not registered by createSyncPlugin; the current production command surface negotiates v4/min_reader v3.',
    },
  };
}

async function loadNativeApi() {
  const [compressionModule, connectionModule] = await Promise.all([
    load('host/platform/native/compression/index.ts'),
    load('host/platform/storage/connection.ts'),
  ]);
  const files = [
    'host/platform/native/compression/index.ts',
    'host/platform/storage/connection.ts',
  ];
  return {
    exports: { compression: compressionModule.compression, Connection: connectionModule.Connection },
    inputs: files.map(file => ({ file, sha256: sha256(readFileSync(resolve(desktop, file))) })),
  };
}

function captureChanges(api) {
  const cases = [
    {
      id: 'change-full-minimal',
      input: '{"table_name":"notes","record_id":"n1","operation":"Update","data":{"id":"n1","title":"中文🙂"},"changed_at":"2026-11-15T00:00:00Z"}',
    },
    {
      id: 'change-optional-omitted',
      input: '{"table_name":"notes","record_id":"n1","operation":"INSERT","data":null,"changed_at":"2026-11-15T00:00:00.123456789Z"}',
    },
    {
      id: 'change-i64-u64-boundaries',
      input: '{"table_name":"messages","record_id":"m1","operation":"update","data":{"big":9007199254740993,"min":-9223372036854775808,"max":9223372036854775807,"real":1.0,"nil":null},"changed_at":"2026-11-15T00:00:00+08:00","change_log_id":-9223372036854775808,"database_name":"chat_v2","suppress_change_log":false,"source_device_id":"peer","source_seq":18446744073709551615,"unknown":"ignored"}',
    },
    {
      id: 'change-unknown-and-explicit-null-optionals',
      input: '{"unknown":{"nested":[1,2]},"table_name":"notes","record_id":"n2","operation":"Delete","data":null,"changed_at":"2026-11-15T00:00:00Z","database_name":null,"suppress_change_log":null,"source_seq":null,"change_log_id":0}',
    },
  ];
  return cases.map(item => {
    const parsed = api.parseSyncChange(item.input);
    const serialized = api.serializeSyncChange(parsed);
    const reparsed = api.parseSyncChange(serialized);
    const reserialized = api.serializeSyncChange(reparsed);
    assert.equal(reserialized, serialized, `${item.id}: record serialization is not stable`);
    if (item.id === 'change-unknown-and-explicit-null-optionals') {
      assert.equal(parsed.suppress_change_log, false);
      assert.equal(Object.hasOwn(parsed, 'database_name'), true);
      assert.equal(parsed.database_name, null);
      assert.equal(Object.hasOwn(parsed, 'source_seq'), true);
      assert.equal(parsed.source_seq, null);
      assert.doesNotMatch(serialized, /"database_name":null|"source_seq":null/u);
    }
    if (item.id === 'change-optional-omitted') {
      assert.equal(Object.hasOwn(parsed, 'change_log_id'), false);
      assert.equal(Object.hasOwn(parsed, 'database_name'), false);
      assert.equal(Object.hasOwn(parsed, 'source_seq'), false);
    }
    return {
      ...item,
      input: outputRecord(item.input),
      parsed: wireValue(parsed),
      expected: outputRecord(serialized),
      reparsed: wireValue(reparsed),
      serializationStable: true,
      nullOptionalPolicy: {
        parserPreservesExplicitNullKeys: true,
        serializerOmitsNullOptionalKeys: true,
        suppressChangeLogNullParsedAs: false,
        omittedOptionalKeysStayOmitted: true,
        exactObjectRoundtrip: false,
      },
    };
  });
}

function captureEnvelope(api) {
  const text = '{"changes":[{"table_name":"notes","record_id":"n1","operation":"Update","data":{"id":"n1","x":1,"x":2,"big":9007199254740993},"changed_at":"2026-11-15T00:00:00Z"}],"total_count":1,"device_id":"peer","format_version":4,"source_seq":18446744073709551615,"source_device_id":"peer","operation_id":"550e8400-e29b-41d4-a716-446655440000","unknown_field":{"kept":"ignored"}}';
  const value = api.parseJsonLossless(text);
  const parsed = api.parseChangesPayload(value);
  const legacyText = '{"entries":[{"id":7,"sync_version":0,"table_name":"notes","record_id":"n1","operation":"Update","changed_at":"2026-11-15T00:00:00Z"}],"changes_by_table":{"notes":1},"total_count":1,"earliest_change":"2026-11-15T00:00:00Z","latest_change":"2026-11-15T00:00:00Z"}';
  const legacy = api.parseLegacyPendingChanges(api.parseJsonLossless(legacyText));
  const format = api.parseFormatDescriptor('{"format_version":4,"min_client":3,"features":["v4_change_shards"],"checkpoint":{"generation":18446744073709551615,"shard_cursors":{"a":9007199254740993},"complete":true,"full_listing_verified":false},"unknown":"ignored"}');
  return {
    batch: {
      input: outputRecord(text),
      parsed: wireValue(parsed),
      formatVersion: parsed.format_version.toString(),
      sourceSeq: parsed.source_seq.toString(),
      operationId: parsed.operation_id,
    },
    legacyPending: {
      input: outputRecord(legacyText),
      parsed: wireValue(legacy),
    },
    format: {
      parsed: wireValue(format),
      expected: outputRecord(api.stringifyJsonLossless(api.descriptorToJson(format), true)),
    },
  };
}

function captureManifest(api) {
  const syncText = '{"sync_transaction_id":"tx-1","databases":{"vfs":{"schema_version":20261111,"data_version":18446744073709551615,"checksum":"abc","last_updated_at":null,"unknown":1}},"status":"Complete","created_at":"2026-11-15T00:00:00Z","device_id":"peer","format_version":4,"published_max_seq":9007199254740993,"cursors":{"peer":18446744073709551614},"superseded_by":null,"snapshot_seen":{},"unknown":true}';
  const sync = api.parseSyncManifest(syncText);
  const format = api.parseFormatDescriptor('{"format_version":4,"min_reader_version":3,"features":[]}');
  const generated = api.createManifest('local', { vfs: { schema_version: 20261111, data_version: 1, checksum: 'left' } });
  assert.match(generated.sync_transaction_id, /^[0-9a-f-]{36}$/);
  assert.match(generated.created_at, /^\d{4}-\d{2}-\d{2}T/u);
  const left = {
    ...generated,
    sync_transaction_id: '<uuid>',
    created_at: '<rfc3339>',
  };
  const right = {
    ...left,
    sync_transaction_id: 'cloud',
    device_id: 'cloud',
    databases: { vfs: { schema_version: 20261023, data_version: 2, checksum: 'right' } },
  };
  return {
    note: 'S0.18 owns the sync-manifest wire; backup version manifests and tombstone/file manifests are parent-owned and intentionally omitted.',
    keys: {
      deviceKey: api.deviceManifestKey('peer'),
      legacyDeviceKey: api.deviceManifestLegacyKey('peer'),
      divergentSentinel: api.DIVERGED_CHECKSUM_SENTINEL,
    },
    sync: {
      input: outputRecord(syncText),
      parsed: wireValue(sync),
      encoded: outputRecord(api.stringifyJsonLossless(sync, true)),
    },
    generated: {
      validated: {
        syncTransactionId: 'uuid-v4',
        createdAt: 'rfc3339',
        status: generated.status,
        formatVersion: generated.format_version,
        deviceId: generated.device_id,
      },
      normalized: wireValue(left),
    },
    format: {
      parsed: wireValue(format),
      encoded: outputRecord(api.stringifyJsonLossless(api.descriptorToJson(format), true)),
    },
    conflicts: wireValue(api.detectConflicts(left, right)),
    keepLatest: wireValue(api.applyMergeStrategy('KeepLatest', [{
      database_name: 'vfs', table_name: 'notes', record_id: 'n1', local_version: 1, cloud_version: 1,
      local_updated_at: '2026-11-15T00:00:00Z', cloud_updated_at: '2026-11-15T00:00:01Z',
      local_data: { id: 'n1' }, cloud_data: { id: 'n1', device_id: 'cloud' },
    }])),
  };
}

async function captureFormatBoundaries(api) {
  const legacy = api.parseFormatDescriptor('{}');
  const v4 = api.parseFormatDescriptor('{"format_version":4,"min_client":3,"features":["v4_change_shards"]}');
  const v3Root = api.parseFormatDescriptor('{"format_version":3,"min_reader_version":3,"features":[]}');
  const future = api.parseFormatDescriptor('{"format_version":5,"min_reader_version":4,"features":["v5_framed_pack"]}');
  assert.deepEqual([api.CURRENT_FORMAT_VERSION, api.MIN_COMPATIBLE_FORMAT_VERSION], [4, 3]);
  assert.deepEqual({ ...v4, checkpoint: null, compatibility_lease: null }, {
    ...v3Root, format_version: 4, features: ['v4_change_shards'],
  });
  const storage = text => ({
    async get(key) {
      assert.equal(key, api.FORMAT_KEY);
      return text === null ? null : textBytes(text);
    },
  });
  const options = { deviceId: 's0-18-format', codec: { encode: async bytes => bytes, decode: async bytes => bytes } };
  const negotiate = async (id, text) => {
    const run = () => api.validateRemoteFormat(storage(text), false, options);
    try {
      return { id, input: text, rejected: false, parsed: wireValue(api.descriptorToJson(await run())) };
    } catch (error) {
      return { id, input: text, rejected: true, name: error?.name ?? 'Error',
        message: error instanceof Error ? error.message : String(error) };
    }
  };
  const negotiation = await Promise.all([
    negotiate('missing-format-reads-v3', null),
    negotiate('v3-root-reads', '{"format_version":3,"min_reader_version":3,"features":[]}'),
    negotiate('v4-min-reader-3-reads', '{"format_version":4,"min_client":3,"features":["v4_change_shards"]}'),
    negotiate('v5-reader-rejected', '{"format_version":5,"min_reader_version":4,"features":["v5_framed_pack"]}'),
    negotiate('v5-format-rejected', '{"format_version":5,"min_client":3,"features":["v5_framed_pack"]}'),
    negotiate('v2-format-rejected', '{"format_version":2,"min_reader_version":2,"features":[]}'),
    negotiate('corrupt-format-rejected', '{"format_version":'),
  ]);
  assert.deepEqual(negotiation.map(item => [item.id, item.rejected]), [
    ['missing-format-reads-v3', false],
    ['v3-root-reads', false],
    ['v4-min-reader-3-reads', false],
    ['v5-reader-rejected', true],
    ['v5-format-rejected', true],
    ['v2-format-rejected', true],
    ['corrupt-format-rejected', true],
  ]);
  return {
    constants: {
      current: api.CURRENT_FORMAT_VERSION,
      minimumReader: api.MIN_COMPATIBLE_FORMAT_VERSION,
      currentIsV4: api.CURRENT_FORMAT_VERSION === 4,
      minimumReaderIsV3: api.MIN_COMPATIBLE_FORMAT_VERSION === 3,
    },
    cases: [
      {
        id: 'missing-descriptor-defaults-v3',
        input: '{}',
        expected: wireValue(api.descriptorToJson(legacy)),
      },
      {
        id: 'v3-root-accepted',
        input: '{"format_version":3,"min_reader_version":3,"features":[]}',
        expected: wireValue(api.descriptorToJson(v3Root)),
      },
      {
        id: 'v4-min-reader-3-accepted',
        input: '{"format_version":4,"min_client":3,"features":["v4_change_shards"]}',
        expected: wireValue(api.descriptorToJson(v4)),
      },
      {
        id: 'future-v5-descriptor-parses-diagnostically',
        input: '{"format_version":5,"min_reader_version":4,"features":["v5_framed_pack"]}',
        expected: wireValue(api.descriptorToJson(future)),
        note: 'Parsing is not format activation; validateRemoteFormat rejects any descriptor above the frozen current version before writing.',
      },
    ],
    negotiation,
    boundary: {
      writableCurrent: api.CURRENT_FORMAT_VERSION,
      writableMinimumReader: api.MIN_COMPATIBLE_FORMAT_VERSION,
      futureRejectedBy: ['format_version', 'min_reader_version'],
      v5DescriptorsCanBeParsedForDiagnosticsOnly: true,
      productionActivatesV5: false,
    },
  };
}

function captureKeys(api) {
  const v3 = api.buildChangeKeyV3('device-1', 42n, 1707500000n);
  const v4 = api.buildChangeKeyV4('device-1', 43n, 1707500001n);
  const v3Parsed = api.parseChangeKey(v3);
  const v4Parsed = api.parseChangeKey(v4);
  assert.equal(v3Parsed.kind, 'v3');
  assert.equal(v4Parsed.kind, 'v4Shard');
  const v3Uuid = v3.split('/').pop().slice(0, -'.json.zst'.length).split('-').slice(2).join('-');
  const v4Uuid = v4Parsed.operationId;
  assert.match(v3Uuid, /^[0-9a-f-]{36}$/);
  assert.match(v4Uuid, /^[0-9a-f-]{36}$/);
  const normalizedV3 = v3.replace(v3Uuid, '<uuid>');
  const normalizedV4 = v4.replace(v4Uuid, '<uuid>');
  const cases = [
    ['v3', normalizedV3],
    ['v4', normalizedV4],
    ['legacy-nonce', 'data_governance/changes/peer/12345-acde.json'],
    ['legacy-plain', 'data_governance/changes/peer/12345.json.zst'],
    ['malformed-extension', 'data_governance/changes/peer/12345.json.gz'],
    ['bad-seq', 'data_governance/v4/shards/peer/00000000000x-1-op.json.zst'],
    ['u64-overflow', 'data_governance/changes/peer/000000000001-18446744073709551616.json.zst'],
    ['blank-operation', 'data_governance/v4/shards/peer/000000000001-1- .json.zst'],
  ];
  return {
    deviceHash: api.deviceIdShortHash('device-1'),
    normalizedDeviceId: api.normalizeDeviceId(' device-1 '),
    v3: {
      rawKey: v3,
      normalizedKey: normalizedV3,
      uuid: '<uuid>',
      normalizedUuid: '<uuid>',
      parsedNormalized: wireValue(api.parseChangeKey(normalizedV3)),
      version: api.parseVersionFromKey(v3).toString(),
    },
    v4: {
      rawKey: v4,
      normalizedKey: normalizedV4,
      uuid: '<uuid>',
      normalizedUuid: '<uuid>',
      parsedNormalized: wireValue({ ...v4Parsed, operationId: '<uuid>' }),
    },
    cases: cases.map(([id, key]) => ({ id, key, parsed: wireValue(api.parseChangeKey(key)) })),
    normalizedVersion: [
      ['milliseconds', '1707500000123'],
      ['seconds', '1707500000'],
      ['threshold', '100000000000'],
    ].map(([id, value]) => ({ id, input: value, expected: api.normalizeVersionToSeconds(BigInt(value)).toString() })),
    missingSequence: [
      ['proven', '2', '3', '3'],
      ['not-proven', '2', '3', '2'],
      ['equal', '1', '1', '1'],
    ].map(([id, expected, observed, published]) => ({
      id, expected, observed, published,
      result: api.missingSequenceIsProven(BigInt(expected), BigInt(observed), BigInt(published)),
    })),
    pathMatch: ['device-1', api.deviceIdShortHash('device-1'), 'other'].map(value => ({
      value, result: api.pathIdMatchesDevice(value, 'device-1'),
    })),
  };
}

function captureLww(api) {
  const comparisons = [
    ['hlc-newer', '000000000000001-00001', '000000000000001-00000'],
    ['hlc-counter', '000000000000001-00002', '000000000000001-00001'],
    ['device-tie', '2026-11-15T00:00:00Z', '2026-11-15T00:00:00Z'],
    ['rfc-offset', '2026-11-15T08:00:00+08:00', '2026-11-15T00:00:00Z'],
    ['integer-millis', '1700000000000', '2026-11-14T00:00:00Z'],
    ['sqlite-time', '2026-11-15 00:00:00', '2026-11-14T00:00:00Z'],
    ['invalid', 'not-a-time', '2026-11-14T00:00:00Z'],
  ].map(([id, local, cloud]) => ({
    id, local, cloud,
    ordering: api.compareLwwTimestamps(local, 'dev-a', 'content-a', cloud, 'dev-b', 'content-b'),
    symmetric: api.compareLwwTimestamps(local, '', '', cloud, '', ''),
  }));
  const clock = new api.HlcClock(api.Hlc.ZERO);
  const ticks = [1000n, 1000n, 999n, 1001n].map(millis => clock.tick(millis).toString());
  return {
    comparisons,
    hlc: {
      parse: ['000000000000001-00002', '+1-+2', '18446744073709551615-65535', '18446744073709551616-0', '1-65536', '1-x'].map(value => ({
        value, parsed: wireValue(api.Hlc.parse(value)), normalized: api.Hlc.parse(value)?.toString() ?? null,
      })),
      ticks,
      receive: wireValue(clock.receive(new api.Hlc(5000n, 2), 1000n)),
      constructorRecord: wireValue(new api.Hlc(0n, 65536)),
      clockRejectsCounterOverflow: errorRecord(() => new api.HlcClock(new api.Hlc(0n, 65536))),
    },
    timestampValues: [null, true, 0, 1700000000000, '2026-11-15T00:00:00Z', { x: 1 }].map(value => ({
      input: wireValue(value), expected: api.timestampValueToLwwString(value),
    })),
    devicePair: [
      [{ device_id: 'local' }, { device_id: 'cloud' }, null],
      [{ device_id: 'local' }, { device_id: 'cloud' }, 'hint'],
      [{}, { device_id: 'cloud' }, null],
    ].map(([local, cloud, hint]) => ({ local, cloud, hint, expected: api.lwwDevicePair(local, cloud, hint) })),
  };
}

function captureMerge(api) {
  const cases = [
    ['tag-union', 'notes', 'tags', ['math', 'physics'], ['physics', 'chemistry']],
    ['tag-numeric-max', 'notes', 'tags', ['_hits:3', '_hits:10'], ['_hits:5']],
    ['tag-enum', 'notes', 'tags', ['_type:fact'], ['_type:study']],
    ['max-i64', 'review_plans', 'total_reviews', 9007199254740993n, 9007199254740992n],
    ['boolean-or', 'questions', 'is_favorite', 0, 1],
    ['unknown-column', 'questions', 'images_json', [{ id: 'a' }], [{ id: 'b' }]],
    ['derived-counter', 'todo_items', 'completed_pomodoros', 3, 5],
    ['json-float-max-fallback', 'review_plans', 'total_reviews', new api.JsonFloat(10), 7],
  ];
  const rows = cases.map(([id, table, column, left, right]) => {
    const [value, merged, conflict] = api.mergeField(table, column, left, right);
    const [reverse, reverseMerged, reverseConflict] = api.mergeField(table, column, right, left);
    const [again, againMerged, againConflict] = api.mergeField(table, column, value, right);
    const commutative = !conflict && !reverseConflict ? api.jsonEquals(value, reverse) : null;
    const idempotent = !conflict ? api.jsonEquals(value, again) : null;
    assert.equal(commutative, conflict ? null : true, `${id}: merge not commutative`);
    assert.equal(idempotent, conflict ? null : true, `${id}: merge not idempotent`);
    return {
      id, table, column,
      input: wireValue({ left, right }),
      expected: wireValue({ value, merged, conflict }),
      reverse: wireValue({ value: reverse, merged: reverseMerged, conflict: reverseConflict }),
      secondPass: wireValue({ value: again, merged: againMerged, conflict: againConflict }),
      commutative, idempotent,
    };
  });
  return {
    cases: rows,
    tables: api.fieldMergeTables(),
    columns: Object.fromEntries(api.fieldMergeTables().map(table => [table, api.fieldMergeColumnsForTable(table)])),
  };
}

function captureUtf8(api) {
  const values = ['中文🙂', 'e\u0301', 'é', '\ufeff正文', 'line\r\nnext', 'path/空 格/文件.md', 'a\u0000b'];
  return {
    cases: values.map(value => {
      const bytes = api.encodeUtf8(value);
      return {
        input: value,
        bytes: bytes.length,
        sha256: bytesHash(bytes),
        utf8Hex: hex(bytes),
        decoded: api.decodeUtf8Strict(bytes),
        jsLength: value.length,
      };
    }),
    ordering: [
      ['ascii', 'a', 'b'],
      ['unicode', 'é', '中'],
      ['prefix', 'a', 'ab'],
    ].map(([id, left, right]) => ({ id, left, right, expected: api.compareUtf8(left, right) })),
    invalid: [
      ['truncated', 'e4b8'],
      ['overlong', 'c080'],
      ['surrogate', 'eda080'],
      ['too-high', 'f4908080'],
      ['ff', 'ff'],
    ].map(([id, value]) => ({
      id, hex: value,
      prefix: api.validUtf8Prefix(Buffer.from(value, 'hex')),
      error: errorRecord(() => api.decodeUtf8Strict(Buffer.from(value, 'hex'))),
    })),
  };
}

function captureLossless(api) {
  const text = '{"i":1,"float":1.0,"negzero":-0.0,"big":9007199254740993,"i64min":-9223372036854775808,"u64":18446744073709551615,"null":null,"string":"中文🙂","array":[1,1.0,9007199254740993],"unknown":{"nested":true}}';
  const parsed = api.parseJsonLossless(text);
  const output = api.stringifyJsonLossless(parsed);
  assert.equal(output, text, 'lossless JSON canonical text drift');
  const document = api.parseJsonDocument(text);
  return {
    input: outputRecord(text),
    parsed: wireValue(parsed),
    expected: outputRecord(output),
    exactDocumentKind: document.kind,
    duplicateKeys: {
      input: '{"a":1,"a":2}',
      parsed: wireValue(api.parseJsonLossless('{"a":1,"a":2}')),
    },
    rejected: [
      ['bad-json', '{"a":'],
      ['trailing', '{"a":1} x'],
      ['bom', '\ufeff{}'],
    ].map(([id, value]) => ({ id, input: value, result: errorRecord(() => api.parseJsonLossless(value)) })),
    rejectedBytes: [['invalid-utf8', Uint8Array.from([0x7b, 0xff, 0x7d])], ['bom-bytes', Uint8Array.from([0xef, 0xbb, 0xbf, 0x7b, 0x7d])]]
      .map(([id, value]) => ({ id, hex: hex(value), result: errorRecord(() => api.parseJsonDocumentBytes(value)) })),
  };
}

async function captureCompression(api, lossless) {
  const source = Buffer.from(lossless.input.text, 'utf8');
  const compressed = Buffer.from(await api.compression.zstdEncode(source, 0));
  const decoded = Buffer.from(await api.compression.zstdDecode(compressed));
  assert.equal(decoded.toString('utf8'), lossless.input.text);
  const empty = Buffer.from(await api.compression.zstdEncode(new Uint8Array(0), 0));
  const emptyDecoded = Buffer.from(await api.compression.zstdDecode(empty));
  const corrupt = Buffer.from(compressed);
  corrupt[corrupt.length - 1] ^= 0xff;
  return {
    codec: 'node:zlib zstdCompress/zstdDecode (desktop nativeCompression implementation)',
    input: { bytes: source.length, sha256: bytesHash(source) },
    expected: { bytes: compressed.length, sha256: bytesHash(compressed), zstdHex: hex(compressed) },
    decoded: { bytes: decoded.length, sha256: bytesHash(decoded), equal: decoded.equals(source) },
    empty: { inputBytes: 0, compressedBytes: empty.length, compressedSha256: bytesHash(empty), decodedBytes: emptyDecoded.length },
    corrupt: await errorRecordAsync(() => api.compression.zstdDecode(corrupt)),
  };
}

function captureUnknownColumns(api, nativeApi) {
  const dir = mkdtempSync(join(tmpdir(), 's0-18-sync-golden-'));
  const db = new nativeApi.Connection(join(dir, 'unknown.db'), { label: 's0-18-unknown', profile: { journalMode: 'DELETE' } });
  try {
    db.exec(`
      CREATE TABLE notes (
        id TEXT PRIMARY KEY, title TEXT, updated_at TEXT, deleted_at TEXT
      );
      INSERT INTO notes(id,title,updated_at,deleted_at) VALUES ('n1','title','2026-11-15T00:00:00Z',NULL);
    `);
    api.vaultUnknownFieldsWithVersion(db, 'notes', 'n1', {
      future_text: { value: '中文🙂', hlc: '000000000000002-00001', dev: 'peer' },
      future_int: { value: 9007199254740993n, hlc: '000000000000002-00001', dev: 'peer' },
      future_null: { value: null, hlc: '000000000000002-00001', dev: 'peer' },
      future_obj: { value: { nested: [1, true, null] }, hlc: '000000000000002-00001', dev: 'peer' },
    });
    const before = api.getRecordData(db, 'notes', 'n1', 'id');
    const read = api.readUnknownFieldsForRoundTrip(db, 'notes', 'n1');
    assert.deepEqual(Object.keys(read.values).sort(), ['future_int', 'future_null', 'future_obj', 'future_text']);
    const raw = db.pluck('SELECT fields_json FROM __sync_unknown_fields WHERE table_name=?1 AND record_id=?2', ['notes', 'n1']);
    assert.equal(typeof raw, 'string');
    const vaultUpdatedAt = db.pluck('SELECT updated_at FROM __sync_unknown_fields WHERE table_name=?1 AND record_id=?2', ['notes', 'n1']);
    assert.match(String(vaultUpdatedAt), /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/u);
    db.exec('ALTER TABLE notes ADD COLUMN future_text TEXT; ALTER TABLE notes ADD COLUMN future_int INTEGER;');
    api.rehydrateUnknownFieldsIfColumnsExist(db, 'notes');
    const after = api.getRecordData(db, 'notes', 'n1', 'id');
    const remaining = api.readUnknownFields(db, 'notes', 'n1');
    assert.equal(after.future_text, '中文🙂');
    assert.equal(after.future_int, 9007199254740993n);
    assert.deepEqual(Object.keys(remaining).sort(), ['future_null', 'future_obj']);
    const sqlite = {
      engine: String(db.pluck('SELECT sqlite_version()')),
      futureTextType: db.pluck("SELECT typeof(future_text) FROM notes WHERE id='n1'"),
      futureIntType: db.pluck("SELECT typeof(future_int) FROM notes WHERE id='n1'"),
      futureIntText: db.pluck("SELECT CAST(future_int AS TEXT) FROM notes WHERE id='n1'"),
    };

    db.exec('DROP TABLE notes');
    db.exec('CREATE TABLE notes (id TEXT PRIMARY KEY, title TEXT, updated_at TEXT, deleted_at TEXT)');
    db.exec('DELETE FROM __sync_unknown_fields');
    db.exec("INSERT INTO notes(id,title,updated_at,deleted_at) VALUES ('n2','second','2026-11-15T00:00:00Z',NULL)");
    api.vaultUnknownFieldsWithVersion(db, 'notes', 'n2', {
      late_obj: { value: { a: [1, null] }, hlc: '000000000000003-00001', dev: 'peer' },
      late_null: { value: null, hlc: '000000000000003-00001', dev: 'peer' },
      late_big: { value: 9007199254740993n, hlc: '000000000000003-00001', dev: 'peer' },
    });
    const lateBefore = api.readUnknownFieldsForRoundTrip(db, 'notes', 'n2');
    db.exec('ALTER TABLE notes ADD COLUMN late_obj TEXT');
    api.rehydrateUnknownFieldsIfColumnsExist(db, 'notes');
    const lateRow = db.get('SELECT late_obj FROM notes WHERE id=?1', ['n2']);
    const lateRemaining = api.readUnknownFieldsForRoundTrip(db, 'notes', 'n2');
    assert.deepEqual(lateBefore.values, {
      late_obj: { a: [1, null] }, late_null: null, late_big: 9007199254740993n,
    });
    assert.equal(lateRow.late_obj, '{"a":[1,null]}');
    assert.deepEqual(lateRemaining.values, { late_null: null, late_big: 9007199254740993n });
    assert.deepEqual(Object.keys(lateRemaining.fieldDeltas).sort(), ['late_big', 'late_null']);

    return {
      provenance: 'real desktop Connection with production unknown-fields vault/getRecordData/rehydrate functions',
      before: wireValue(before),
      vault: {
        storedFieldsJson: raw,
        read: wireValue(read),
        volatileUpdatedAt: { present: true, format: 'YYYY-MM-DD HH:MM:SS', excludedFromComparison: true },
      },
      after: wireValue(after),
      remaining: wireValue(remaining),
      sqlite,
      partialRehydrate: {
        provenance: 'second real Connection row; only the column that now exists is restored',
        before: wireValue(lateBefore),
        row: wireValue(lateRow),
        remaining: wireValue(lateRemaining),
        restoredColumn: 'late_obj',
        preservedUnknownColumns: ['late_big', 'late_null'],
      },
    };
  } finally {
    db.close();
    rmSync(dir, { recursive: true, force: true });
  }
}

async function build() {
  const started = performance.now();
  const loaded = await loadApi();
  log('sync-golden.desktop-module', {
    inputs: loaded.inputs?.length ?? 0,
    bundleInputsSha256: sha256(JSON.stringify(loaded.inputs ?? [])),
  });
  const api = loaded.exports;
  const nativeLoaded = await loadNativeApi();
  const nativeApi = nativeLoaded.exports;
  log('sync-golden.native-module', {
    inputs: nativeLoaded.inputs?.length ?? 0,
    bundleInputsSha256: sha256(JSON.stringify(nativeLoaded.inputs ?? [])),
  });
  const lossless = captureLossless(api);
  const sourceBinding = sourceBindings(loaded);
  const data = {
    formatVersion: 2,
    format: FORMAT,
    unit: UNIT,
    generatedAt: new Date().toISOString(),
    scope: {
      included: ['change record', 'change batch envelope', 'sync manifest', 'change keys', 'lossless JSON', 'HLC/LWW', 'field merge', 'UTF-8/path codecs', 'zstd compression', 'unknown-column vault', 'format descriptor boundaries'],
      excluded: ['backup version manifest (parent file golden)', 'tombstone/file manifests (parent)', 'DSBK encryption golden (parent)', 'provider transport/integration (parent)', 'S0.19 classification', 'v5 deferred replay and cloud publication (not registered by the production plugin)', 'plugin sandbox opaque fields (parent source, not a wire function)'],
    },
    sourceInputs: {
      binding: sourceBinding,
      protocol: loaded.inputs,
      native: nativeLoaded.inputs,
      combinedSha256: sha256(JSON.stringify([...loaded.inputs, ...nativeLoaded.inputs])),
    },
    environment: {
      desktop,
      node: process.version,
      loader: 'tools/baseline/desktop-module.mjs',
      esbuild: desktopRequire('esbuild/package.json').version,
      sqlite: desktopRequire('better-sqlite3/package.json').version,
    },
    changes: captureChanges(api),
    envelopes: captureEnvelope(api),
    manifests: captureManifest(api),
    keys: captureKeys(api),
    lossless,
    lww: captureLww(api),
    merge: captureMerge(api),
    utf8: captureUtf8(api),
    compression: await captureCompression(nativeApi, lossless),
    unknownColumns: captureUnknownColumns(api, nativeApi),
    formats: await captureFormatBoundaries(api),
    productionSurface: productionBoundary(api),
    negative: [],
  };
  data.negative = [
    ['change-missing-required', () => api.parseSyncChange('{"table_name":"notes"}')],
    ['change-i64-low', () => api.parseSyncChange('{"table_name":"notes","record_id":"n1","operation":"Update","changed_at":"2026-11-15T00:00:00Z","change_log_id":-9223372036854775809}')],
    ['change-i64-high', () => api.parseSyncChange('{"table_name":"notes","record_id":"n1","operation":"Update","changed_at":"2026-11-15T00:00:00Z","change_log_id":9223372036854775808}')],
    ['change-u64-high', () => api.parseSyncChange('{"table_name":"notes","record_id":"n1","operation":"Update","changed_at":"2026-11-15T00:00:00Z","source_seq":18446744073709551616}')],
    ['change-bad-operation', () => api.parseSyncChange('{"table_name":"notes","record_id":"n1","operation":"Upsert","changed_at":"2026-11-15T00:00:00Z"}')],
    ['envelope-missing-changes', () => api.parseChangesPayload(api.parseJsonLossless('{"device_id":"peer","total_count":0}'))],
    ['envelope-u64-high', () => api.parseChangesPayload(api.parseJsonLossless('{"changes":[],"device_id":"peer","total_count":0,"source_seq":18446744073709551616}'))],
    ['manifest-u32-high', () => api.parseSyncManifest('{"sync_transaction_id":"t","databases":{},"status":"Complete","created_at":"2026-11-15T00:00:00Z","device_id":"d","format_version":4294967296}')],
    ['manifest-bad-status', () => api.parseSyncManifest('{"sync_transaction_id":"t","databases":{},"status":"Unknown","created_at":"2026-11-15T00:00:00Z","device_id":"d"}')],
    ['manifest-u64-high', () => api.parseSyncManifest('{"sync_transaction_id":"t","databases":{},"status":"Complete","created_at":"2026-11-15T00:00:00Z","device_id":"d","published_max_seq":18446744073709551616}')],
    ['format-duplicate-min', () => api.parseFormatDescriptor('{"format_version":4,"min_client":3,"min_reader_version":3}')],
    ['format-negative-u64', () => api.parseFormatDescriptor('{"format_version":4,"checkpoint":{"generation":-1}}')],
    ['hlc-clock-counter-overflow', () => new api.HlcClock(new api.Hlc(0n, 65536))],
  ].map(([id, run]) => ({ id, expected: 'rejected', ...errorRecord(run) }));
  assert.ok(data.negative.every(item => item.rejected === true), 'negative sync cases did not all reject');
  data.replay = {
    deterministicInputs: ['change record', 'change batch', 'manifest', 'keys', 'merge', 'UTF-8', 'lossless JSON'],
    zstdByteStable: true,
    uuidPolicy: 'v3/v4 key UUIDs captured once, raw key retained, normalized key and parsed comparison use <uuid>',
    randomFieldsPolicy: 'createManifest UUID and RFC3339 time validated then replaced with <uuid>/<rfc3339>; vault updated_at validated but excluded',
    mergeConvergence: 'value equality only; merged flags are recorded but not used for commutativity/idempotence',
    artifactSha256: null,
  };
  const comparable = JSON.parse(JSON.stringify({ ...data, generatedAt: null, captureMs: null, replay: { ...data.replay, artifactSha256: null } }));
  data.replay.artifactSha256 = sha256(JSON.stringify(comparable));
  data.captureMs = Math.round(performance.now() - started);
  return data;
}

function verify(data) {
  const hashInput = JSON.parse(JSON.stringify({ ...data, generatedAt: null, captureMs: null, replay: { ...data.replay, artifactSha256: null } }));
  assert.equal(data.replay.artifactSha256, sha256(JSON.stringify(hashInput)), 'artifact self-hash drift');
  for (const row of data.changes) {
    assert.equal(row.expected.sha256, bytesHash(textBytes(row.expected.text)), `${row.id}: serialized bytes drift`);
  }
  assert.equal(data.envelopes.batch.sourceSeq, '18446744073709551615');
  assert.equal(data.manifests.sync.parsed.published_max_seq.$bigint, '9007199254740993');
  assert.equal(data.manifests.sync.parsed.databases.vfs.data_version.$bigint, '18446744073709551615');
  assert.equal(data.keys.v3.parsedNormalized.kind, 'v3');
  assert.equal(data.keys.v4.parsedNormalized.operationId, '<uuid>');
  assert.equal(data.lossless.input.text, data.lossless.expected.text);
  assert.equal(data.manifests.generated.normalized.sync_transaction_id, '<uuid>');
  assert.equal(data.manifests.generated.normalized.created_at, '<rfc3339>');
  assert.equal(data.compression.decoded.equal, true);
  assert.equal(data.compression.decoded.sha256, data.compression.input.sha256);
  assert.equal(data.unknownColumns.after.future_int.$bigint, '9007199254740993');
  assert.equal(data.unknownColumns.vault.volatileUpdatedAt.excludedFromComparison, true);
  assert.ok(Object.hasOwn(data.unknownColumns.vault.read.values, 'future_null'));
  assert.equal(data.unknownColumns.vault.read.values.future_null, null);
  assert.equal(data.unknownColumns.vault.read.values.future_obj.nested[2], null);
  assert.equal(data.formats.constants.current, 4);
  assert.equal(data.formats.constants.minimumReader, 3);
  assert.equal(data.formats.negotiation.filter(item => item.rejected).length, 4);
  assert.equal(data.productionSurface.v5.registered, false);
  assert.ok(!data.productionSurface.registeredCommands.some(command => /v5/iu.test(command)));
  assert.equal(data.sourceInputs.binding.commit, DESKTOP_COMMIT);
  assert.equal(data.sourceInputs.binding.head, DESKTOP_COMMIT);
  assert.equal(data.sourceInputs.binding.implementation.length, 2);
  assert.equal(data.sourceInputs.binding.wrappers.length, 1);
  assert.equal(data.sourceInputs.binding.protocol.length, 17);
  assert.equal(data.sourceInputs.combinedSha256, sha256(JSON.stringify([...data.sourceInputs.protocol, ...data.sourceInputs.native])));
  for (const row of data.merge.cases) {
    if (row.commutative !== null) assert.equal(row.commutative, true, `${row.id}: commutativity`);
    if (row.idempotent !== null) assert.equal(row.idempotent, true, `${row.id}: idempotence`);
  }
  assert.ok(data.negative.every(item => item.rejected));
  for (const source of [...data.sourceInputs.protocol, ...data.sourceInputs.native]) {
    assert.equal(sha256(readFileSync(resolve(desktop, source.file))), source.sha256, `${source.file}: source hash drift`);
  }
  for (const source of [...data.sourceInputs.binding.implementation, ...data.sourceInputs.binding.wrappers, ...data.sourceInputs.binding.protocol]) {
    assert.equal(sha256(readFileSync(resolve(desktop, source.file))), source.sha256, `${source.file}: binding hash drift`);
  }
  for (const source of Object.values(data.productionSurface.sources)) {
    assert.equal(sha256(readFileSync(resolve(desktop, source.file))), source.sha256, `${source.file}: production surface hash drift`);
  }
  assert.equal(data.sourceInputs.protocol.length, 122);
  assert.equal(data.sourceInputs.native.length, 2);
}

export async function run(mode) {
  if (mode === 'capture') {
    const data = await build();
    artifact('config/baseline/sync-golden.json', data, mode);
    log('sync-golden.accepted', {
      unit: UNIT,
      changes: data.changes.length,
      negative: data.negative.length,
      merge: data.merge.cases.length,
      lww: data.lww.comparisons.length,
      compressionBytes: data.compression.expected.bytes,
      artifactSha256: data.replay.artifactSha256,
    });
    return data;
  }
  assert.equal(mode, 'verify');
  const saved = JSON.parse(readFileSync(resolve(root, 'config/baseline/sync-golden.json'), 'utf8'));
  assert.equal(saved.format, FORMAT);
  verify(saved);
  const fresh = await build();
  const strip = value => {
    const copy = JSON.parse(JSON.stringify(value));
    delete copy.generatedAt;
    delete copy.captureMs;
    delete copy.replay.artifactSha256;
    // The raw key strings are captured once as evidence. Their UUID suffixes are
    // random, so validate their shape and omit them from deterministic replay.
    assert.match(copy.keys.v3.rawKey,
      /^data_governance\/changes\/[0-9a-f]{16}\/000000000042-1707500000-[0-9a-f-]{36}\.json\.zst$/u);
    assert.match(copy.keys.v4.rawKey,
      /^data_governance\/v4\/shards\/[0-9a-f]{16}\/000000000043-1707500001-[0-9a-f-]{36}\.json\.zst$/u);
    assert.equal(copy.keys.v3.uuid, '<uuid>');
    assert.equal(copy.keys.v4.uuid, '<uuid>');
    delete copy.keys.v3.rawKey;
    delete copy.keys.v4.rawKey;
    return copy;
  };
  assert.deepEqual(strip(saved), strip(fresh), 'deterministic sync golden replay drift');
  artifact('config/baseline/sync-golden.json', saved, mode);
  log('sync-golden.verified', {
    unit: UNIT,
    changes: saved.changes.length,
    negative: saved.negative.length,
    artifactSha256: saved.replay.artifactSha256,
  });
  return saved;
}

main(import.meta.url, run);
