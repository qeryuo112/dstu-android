import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { request } from 'node:http';
import test from 'node:test';
import { startSafObjectFixture } from './saf-object-fixture.mjs';

const endpoint = (fixture, key) => `http://127.0.0.1:${fixture.port}/objects/${key}`;

test('S2.07 remote document fixture receives a chunked upload and reads back the exact hash over HTTP', async t => {
  const events = [];
  const fixture = await startSafObjectFixture({ log: entry => events.push(entry) });
  t.after(() => fixture.close());
  const url = endpoint(fixture, 'remote.bin');
  const block = Buffer.alloc(97 * 1024, 0xa0);
  const sha = createHash('sha256');
  async function* body() {
    for (let i = 0; i < 24; i++) { sha.update(block); yield block; }
  }
  const stored = await fetch(url, { method: 'PUT', body: body(), duplex: 'half' });
  const expected = sha.digest('hex');
  assert.equal(stored.status, 201);
  assert.equal(stored.headers.get('x-fixture-content-sha256'), expected);
  const downloaded = await fetch(url);
  assert.equal(downloaded.status, 200);
  assert.equal(downloaded.headers.get('content-length'), String(24 * block.length));
  const actual = createHash('sha256');
  for await (const chunk of downloaded.body) actual.update(chunk);
  assert.equal(actual.digest('hex'), expected);
  assert.equal((await fetch(url, { method: 'DELETE' })).status, 204);
  assert.equal((await fetch(url, { method: 'HEAD' })).status, 404);
  assert.deepEqual(events.map(event => [event.phase, event.state, event.status]),
    [['PUT', 'responded', 201], ['GET', 'responded', 200], ['DELETE', 'responded', 204], ['HEAD', 'responded', 404]]);
  assert.ok(events.every(event => !JSON.stringify(event).includes('remote.bin')));
});

test('S2.07 remote document fixture never lands partial bytes on a truncated/aborted upload', async t => {
  const fixture = await startSafObjectFixture({ maxBytes: 4 * 1024 });
  t.after(() => fixture.close());
  const url = endpoint(fixture, 'payload.bin');
  const put = request(url, { method: 'PUT', headers: { 'Content-Length': 4096 } });
  const terminated = new Promise(done => { put.once('close', done); put.once('error', () => {}); });
  await new Promise((done, reject) => put.write(Buffer.alloc(512), error => error ? reject(error) : done()));
  put.destroy();
  await terminated;
  assert.equal((await fetch(url, { method: 'HEAD' })).status, 404);
  assert.equal((await fetch(url, { method: 'PUT', body: Buffer.alloc(4097) })).status, 413);
  assert.equal((await fetch(url, { method: 'HEAD' })).status, 404);
  assert.equal((await fetch(endpoint(fixture, 'nested/another.bin'))).status, 404);
});
