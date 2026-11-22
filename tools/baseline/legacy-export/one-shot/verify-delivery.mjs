import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { transferArchive } from './delivery.mjs';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { resolve } from 'node:path';
import { desktop } from '../../lib.mjs';

export async function verifyDelivery() {
  const bytes = Uint8Array.from({ length: 131089 }, (_, index) => index % 251);
  const hash = createHash('sha256').update(bytes).digest('hex');
  const blob = new Blob([bytes]);
  const chunks = [];
  const receipt = await transferArchive(blob, 'fixture.zip', hash, {
    create: async ({ fileName }) => { assert.equal(fileName, 'fixture.zip'); return { cancelled: false }; },
    append: async ({ offset, data }) => {
      assert.equal(Number(offset), chunks.reduce((sum, chunk) => sum + chunk.length, 0));
      const chunk = Buffer.from(data, 'base64');
      assert.ok(chunk.length <= 65536);
      chunks.push(chunk);
    },
    finish: async args => {
      assert.equal(args.bytes, String(bytes.length));
      assert.deepEqual(Buffer.concat(chunks), Buffer.from(bytes));
      assert.equal(createHash('sha256').update(Buffer.concat(chunks)).digest('hex'), args.sha256);
      return { uri: 'content://fixture/archive.zip', ...args };
    },
    abort: async () => assert.fail('successful delivery must not abort'),
  });
  assert.equal(receipt.saved, true);
  let aborted = false;
  await assert.rejects(transferArchive(blob, 'fixture.zip', hash, {
    create: async () => ({ cancelled: false }),
    append: async () => { throw new Error('injected native write failure'); },
    abort: async () => { aborted = true; },
  }), /injected native write failure/);
  assert.equal(aborted, true);
  const cancelled = await transferArchive(blob, 'fixture.zip', hash, {
    create: async () => ({ cancelled: true }),
    append: async () => assert.fail('cancelled delivery must not write'),
  });
  assert.equal(cancelled.cancelled, true);
  let rejectedReceipt = false;
  await assert.rejects(transferArchive(new Blob(['x']), 'fixture.zip', hash, {
    create: async () => ({ cancelled: false }),
    append: async () => {},
    finish: async () => ({ uri: 'content://fixture/bad.zip', bytes: '1', sha256: '0'.repeat(64) }),
    abort: async () => { rejectedReceipt = true; },
  }), /SHA-256/);
  assert.equal(rejectedReceipt, true);
  const source = readFileSync(resolve(desktop, 'mobile/node_modules/@capacitor/android/capacitor/src/main/assets/native-bridge.js'), 'utf8');
  const sent = [];
  const window = { console, androidBridge: { postMessage: data => sent.push(JSON.parse(data)) }, addEventListener() {}, removeEventListener() {}, navigator: { userAgent: 'legacy-export-host-check' }, document: { addEventListener() {} }, Document: class Document {}, HTMLDocument: class HTMLDocument {}, XMLHttpRequest: class XMLHttpRequest {}, fetch() {} };
  window.window = window;
  window.globalThis = window;
  vm.runInNewContext(source, window);
  assert.equal(window.Capacitor.isNativePlatform(), true);
  assert.equal(typeof window.Capacitor.nativePromise, 'function');
  const pending = window.Capacitor.nativePromise('LegacyExport', 'create', { fileName: 'fixture.zip' });
  assert.equal(sent[0].pluginId, 'LegacyExport');
  assert.equal(sent[0].methodName, 'create');
  window.Capacitor.fromNative({ callbackId: sent[0].callbackId, success: true, data: { cancelled: true } });
  assert.equal((await pending).cancelled, true);
  return { cases: 5, chunks: chunks.length, maxChunkBytes: 65536, dataRoundTrip: true, failureAbort: true, cancelledWithoutWrite: true, wrongReceiptRejected: true, capacitorNativePromiseRoundTrip: true, nativeAndroidExecuted: false };
}
