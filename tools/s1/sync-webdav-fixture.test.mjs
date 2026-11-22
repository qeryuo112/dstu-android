import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { startWebDavFixture } from './sync-webdav-fixture.mjs';

const desktop = fileURLToPath(new URL('../../../dstu-node/', import.meta.url));
const webdavUrl = new URL('../../../dstu-node/host/plugins/sync-providers/src/webdav.ts', import.meta.url);
const commit = 'e6b2657fd59014a73f5bbb1d14c2db732cf23e87';
const relative = 'host/plugins/sync-providers/src/webdav.ts';
assert.equal(execFileSync('git', ['rev-parse', 'HEAD'], { cwd: desktop, encoding: 'utf8' }).trim(), commit);
const expectedBytes = execFileSync('git', ['show', `${commit}:${relative}`], { cwd: desktop });
assert.deepEqual(readFileSync(webdavUrl), expectedBytes);
const { WebDavStorage } = await import(webdavUrl.href);

const storage = (fixture, root = 'public-roundtrip') => new WebDavStorage({
  endpoint: fixture.endpoint, username: fixture.username, password: fixture.password,
}, root, { rateLimitEnv: '0', requestWindowKey: `fixture-${fixture.port}-${root}` });

test('frozen desktop WebDAV performs real HTTP put/get/list/stat/delete with UTF-8 keys', async () => {
  const fixture = await startWebDavFixture();
  try {
    const provider = storage(fixture);
    const keys = ['data_governance/中文 空格/emoji-😀.json', 'data_governance/中文 空格/empty.bin'];
    const bytes = Buffer.from('public fixture\r\n中文😀\u0000');
    await provider.put(keys[0], bytes);
    await provider.put(keys[1], Buffer.alloc(0));
    assert.deepEqual(Buffer.from(await provider.get(keys[0])), bytes);
    assert.equal((await provider.get(keys[1])).length, 0);
    assert.equal(await provider.get('missing.bin'), null);
    assert.equal((await provider.stat(keys[0])).size, bytes.length);
    const result = await provider.listOutcome('data_governance');
    assert.equal(result.truncated, false);
    assert.deepEqual(result.files.map(file => file.key).sort(), [...keys].sort());
    await provider.delete(keys[0]);
    assert.equal(await provider.get(keys[0]), null);
    assert.equal(fixture.snapshot().size, 1);
    assert.ok(fixture.counts()['PROPFIND:207'] >= 1);
    console.log(JSON.stringify({ event: 's1.sync.fixture.desktop-http-verified', desktopCommit: commit,
      sourceSha256: createHash('sha256').update(expectedBytes).digest('hex'), requests: fixture.counts() }));
  } finally { await fixture.close(); }
});

test('loopback fixture enforces authorization, conditional writes and a bounded body', async () => {
  const fixture = await startWebDavFixture({ maxObjectBytes: 8 });
  const headers = { Authorization: `Basic ${Buffer.from(`${fixture.username}:${fixture.password}`).toString('base64')}` };
  try {
    assert.equal((await fetch(fixture.endpoint, { method: 'PROPFIND' })).status, 401);
    const url = `${fixture.endpoint}probe.bin`;
    assert.equal((await fetch(url, { method: 'PUT', headers, body: 'public' })).status, 201);
    assert.equal((await fetch(url, { method: 'PUT', headers: { ...headers, 'If-None-Match': '*' }, body: 'other' })).status, 412);
    assert.equal(await (await fetch(url, { headers })).text(), 'public');
    assert.equal((await fetch(url, { method: 'PUT', headers, body: 'too-large-body' })).status, 413);
    assert.equal(await (await fetch(url, { headers })).text(), 'public');
    fixture.faultNext({ method: 'GET', status: 503 });
    assert.equal((await fetch(url, { headers })).status, 503);
    assert.equal((await fetch(url, { headers })).status, 200);
  } finally { await fixture.close(); }
});
