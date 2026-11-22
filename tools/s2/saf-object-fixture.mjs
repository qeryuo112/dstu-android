import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { createReadStream, createWriteStream } from 'node:fs';
import { mkdtemp, rename, rm, stat } from 'node:fs/promises';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { Transform } from 'node:stream';
import { finished, pipeline } from 'node:stream/promises';
import { pathToFileURL } from 'node:url';

const objectKey = /^\/objects\/([A-Za-z0-9_-][A-Za-z0-9._-]{0,127})$/u;

/** Test-only remote object store; no user files, credentials or non-loopback bind. */
export async function startSafObjectFixture({ maxBytes = 209_715_200, log = () => {} } = {}) {
  assert.ok(Number.isSafeInteger(maxBytes) && maxBytes > 0 && maxBytes <= 209_715_200);
  const directory = await mkdtemp(join(tmpdir(), 'dstu-s207-remote-'));
  const active = new Set();
  const server = createServer({ requestTimeout: 90_000 }, (request, response) => {
    const start = process.hrtime.bigint();
    const sendOutcome = finished(response, { cleanup: true }).then(() => 'responded', () => 'aborted');
    const completion = handle(request, response).catch(() => finish(response, 500)).then(async () => {
      log({
        event: 's2.07.fixture.http', owner: 'S2.07', phase: method(request),
        state: await sendOutcome, correlationId: randomUUID(),
        elapsedMs: Number((process.hrtime.bigint() - start) / 1_000_000n),
        status: response.headersSent ? response.statusCode : null,
      });
    }).finally(() => active.delete(completion));
    active.add(completion);
  });
  server.setTimeout(90_000, socket => socket.destroy());

  async function handle(request, response) {
    const match = objectKey.exec(request.url ?? '');
    if (!match) return finish(response, 404);
    const file = join(directory, match[1]);
    switch (request.method) {
      case 'PUT': return upload(request, response, file, directory, maxBytes);
      case 'GET': case 'HEAD': return download(request, response, file);
      case 'DELETE':
        await rm(file, { force: true });
        return finish(response, 204);
      default: return finish(response, 405);
    }
  }

  try {
    await new Promise((done, reject) => {
      server.once('error', reject);
      server.listen(0, '127.0.0.1', done);
    });
  } catch (failure) {
    await rm(directory, { recursive: true, force: true });
    throw failure;
  }
  return {
    port: server.address().port,
    async close() {
      const closed = new Promise((done, reject) => server.close(error => error ? reject(error) : done()));
      server.closeAllConnections();
      try { await closed; await Promise.allSettled([...active]); }
      finally { await rm(directory, { recursive: true, force: true }); }
    },
  };
}

function method(request) {
  return ['PUT', 'GET', 'HEAD', 'DELETE'].includes(request.method) ? request.method : 'OTHER';
}

function finish(response, status) {
  if (response.destroyed || response.headersSent) return;
  response.writeHead(status, { 'Content-Length': 0 });
  response.end();
}

async function statIfPresent(path) {
  try { return await stat(path); }
  catch (failure) { if (failure.code !== 'ENOENT') throw failure; return null; }
}

async function upload(request, response, file, directory, maxBytes) {
  const rawLength = request.headers['content-length'];
  if (rawLength != null && Number(rawLength) > maxBytes) return finish(response, 413);
  const temporary = join(directory, `.${randomUUID()}.part`);
  const digest = createHash('sha256');
  let total = 0;
  const limits = new Transform({ transform(chunk, encoding, done) {
    total += chunk.length;
    if (total > maxBytes) return done(new Error('fixture body limit'));
    digest.update(chunk);
    done(null, chunk);
  } });
  try {
    await pipeline(request, limits, createWriteStream(temporary, { flags: 'wx' }));
    if (!request.complete || response.destroyed || (rawLength != null && total !== Number(rawLength))) return;
    await rename(temporary, file);
    response.writeHead(201, { 'Content-Length': 0, 'X-Fixture-Content-Sha256': digest.digest('hex') });
    response.end();
  } finally {
    await rm(temporary, { force: true });
  }
}

async function download(request, response, file) {
  const stat = await statIfPresent(file);
  if (!stat?.isFile()) return finish(response, 404);
  response.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Length': stat.size });
  if (request.method === 'HEAD') response.end();
  else await pipeline(createReadStream(file), response);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const fixture = await startSafObjectFixture({ log: event => console.log(JSON.stringify(event)) });
  console.log(JSON.stringify({ event: 's2.07.fixture.listen', port: fixture.port }));
  let stopping = false;
  const close = async () => {
    if (stopping) return;
    stopping = true;
    await fixture.close();
  };
  process.once('SIGINT', close);
  process.once('SIGTERM', close);
}
