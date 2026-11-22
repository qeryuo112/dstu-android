import { createHash, timingSafeEqual } from 'node:crypto';
import { createServer } from 'node:http';

const xml = value => value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
  .replaceAll('"', '&quot;').replaceAll("'", '&apos;');
const etag = bytes => `"${createHash('sha256').update(bytes).digest('hex')}"`;

function requestPath(raw, base) {
  const url = new URL(raw, 'http://127.0.0.1');
  if (url.search || url.hash) return null;
  let path;
  try { path = decodeURIComponent(url.pathname); } catch { return null; }
  const parts = path.split('/').filter(Boolean);
  if (parts.some(part => part === '.' || part === '..' || /[\\\u0000-\u001f\u007f]/u.test(part))) return null;
  path = `/${parts.join('/')}`;
  if (path !== base.slice(0, -1) && !path.startsWith(base)) return null;
  return path;
}

function propertyResponse(path, file) {
  const href = path.split('/').map(encodeURIComponent).join('/') + (file === null ? '/' : '');
  const properties = file === null ? '<d:resourcetype><d:collection/></d:resourcetype><d:getcontentlength>0</d:getcontentlength>'
    : `<d:resourcetype/><d:getcontentlength>${file.length}</d:getcontentlength><d:getetag>${xml(etag(file))}</d:getetag>`;
  return `<d:response><d:href>${xml(href)}</d:href><d:propstat><d:prop>${properties}<d:getlastmodified>Wed, 18 Nov 2026 00:00:00 GMT</d:getlastmodified></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>`;
}

export async function startWebDavFixture({ username = 'public-sync-user', password = 'public-fixture-password',
  basePath = '/dav/', maxObjectBytes = 16 * 1024 * 1024, maxTotalBytes = 64 * 1024 * 1024 } = {}) {
  if (!/^\/[a-z0-9-]+\/$/u.test(basePath)) throw new Error('invalid fixture base path');
  const expectedAuthorization = Buffer.from(`Basic ${Buffer.from(`${username}:${password}`).toString('base64')}`);
  const files = new Map();
  const directories = new Set([basePath.slice(0, -1)]);
  const counts = new Map();
  let totalBytes = 0;
  let pendingFault = null;
  const respond = (response, method, status, bytes = Buffer.alloc(0), headers = {}) => {
    counts.set(`${method}:${status}`, (counts.get(`${method}:${status}`) ?? 0) + 1);
    response.writeHead(status, { 'Content-Length': bytes.length, ...headers });
    response.end(method === 'HEAD' ? undefined : bytes);
  };
  const readBody = async request => {
    const chunks = [];
    let size = 0;
    for await (const chunk of request) {
      size += chunk.length;
      if (size > maxObjectBytes) throw new Error('fixture body limit');
      chunks.push(chunk);
    }
    return Buffer.concat(chunks, size);
  };
  const handle = async (request, response) => {
    const method = request.method;
    const authorization = Buffer.from(request.headers.authorization ?? '');
    if (authorization.length !== expectedAuthorization.length || !timingSafeEqual(authorization, expectedAuthorization)) {
      request.resume();
      respond(response, method, 401, Buffer.alloc(0), { 'WWW-Authenticate': 'Basic realm="sync-fixture"' });
      return;
    }
    const path = requestPath(request.url, basePath);
    if (path === null) { request.resume(); respond(response, method, 400); return; }
    if (pendingFault?.method === method && (pendingFault.path === undefined || pendingFault.path === path)) {
      const fault = pendingFault;
      pendingFault = null;
      request.resume();
      respond(response, method, fault.status, Buffer.from(fault.body ?? ''), fault.headers ?? {});
      return;
    }
    if (method === 'MKCOL') {
      request.resume();
      const parent = path.slice(0, path.lastIndexOf('/'));
      if (directories.has(path) || files.has(path)) respond(response, method, 405);
      else if (!directories.has(parent)) respond(response, method, 409);
      else { directories.add(path); respond(response, method, 201); }
      return;
    }
    if (method === 'PUT') {
      const body = await readBody(request);
      if (!directories.has(path.slice(0, path.lastIndexOf('/')))) { respond(response, method, 409); return; }
      const previous = files.get(path);
      if (request.headers['if-none-match'] === '*' && previous !== undefined) { respond(response, method, 412); return; }
      if (request.headers['if-match'] && (!previous || request.headers['if-match'] !== etag(previous))) {
        respond(response, method, 412); return;
      }
      if (totalBytes - (previous?.length ?? 0) + body.length > maxTotalBytes) { respond(response, method, 507); return; }
      totalBytes += body.length - (previous?.length ?? 0);
      files.set(path, body);
      respond(response, method, previous ? 204 : 201, Buffer.alloc(0), { ETag: etag(body) });
      return;
    }
    request.resume();
    if (method === 'GET' || method === 'HEAD') {
      const file = files.get(path);
      if (file === undefined) respond(response, method, 404);
      else respond(response, method, 200, file, { 'Content-Type': 'application/octet-stream', ETag: etag(file) });
      return;
    }
    if (method === 'DELETE') {
      const file = files.get(path);
      if (file !== undefined) { files.delete(path); totalBytes -= file.length; respond(response, method, 204); }
      else if (directories.has(path) && path !== basePath.slice(0, -1)) {
        for (const [key, bytes] of files) if (key.startsWith(`${path}/`)) { files.delete(key); totalBytes -= bytes.length; }
        for (const key of directories) if (key === path || key.startsWith(`${path}/`)) directories.delete(key);
        respond(response, method, 204);
      } else respond(response, method, 404);
      return;
    }
    if (method === 'PROPFIND') {
      if (!directories.has(path) && !files.has(path)) { respond(response, method, 404); return; }
      const depth = request.headers.depth ?? 'infinity';
      if (!['0', '1', 'infinity'].includes(depth)) { respond(response, method, 400); return; }
      const visible = key => key === path || (depth !== '0' && key.startsWith(`${path}/`) &&
        (depth === 'infinity' || !key.slice(path.length + 1).includes('/')));
      const entries = [...directories].filter(visible).sort().map(key => propertyResponse(key, null));
      entries.push(...[...files].filter(([key]) => visible(key)).sort(([a], [b]) => a.localeCompare(b))
        .map(([key, file]) => propertyResponse(key, file)));
      const bytes = Buffer.from(`<?xml version="1.0" encoding="UTF-8"?><d:multistatus xmlns:d="DAV:">${entries.join('')}</d:multistatus>`);
      respond(response, method, 207, bytes, { 'Content-Type': 'application/xml; charset=utf-8' });
      return;
    }
    respond(response, method, 405);
  };
  const server = createServer((request, response) => {
    handle(request, response).catch(() => {
      request.resume();
      if (!response.headersSent) respond(response, request.method, 413);
      else response.destroy();
    });
  });
  server.requestTimeout = 30_000;
  server.headersTimeout = 10_000;
  await new Promise((done, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', done); });
  const port = server.address().port;
  return {
    endpoint: `http://127.0.0.1:${port}${basePath}`, port, username, password,
    snapshot: () => new Map([...files].map(([path, bytes]) => [path, Buffer.from(bytes)])),
    counts: () => Object.fromEntries(counts),
    faultNext: fault => { if (pendingFault !== null) throw new Error('fixture fault already pending'); pendingFault = fault; },
    async close() {
      server.closeAllConnections();
      await new Promise((done, reject) => server.close(error => error ? reject(error) : done()));
      for (const file of files.values()) file.fill(0);
      files.clear();
      expectedAuthorization.fill(0);
    },
  };
}
