/** S2.14 desktop portable v1 ZIP conversion. All paths here are private scratch paths. */
import { isDeepStrictEqual } from 'node:util';
import { createHash } from 'node:crypto';
import { open, statfs } from 'node:fs/promises';
import { join } from 'node:path';

const MANIFEST = 'manifest.json';
const PAYLOAD = 'portable_secrets.dsbk';
const CHECKSUMS = 'checksums.sha256';
const MAX_METADATA = 16 * 1024 * 1024;
const SHA256 = /^[0-9a-f]{64}$/i;
const digest = bytes => createHash('sha256').update(bytes).digest('hex');

// JSON.parse accepts duplicate keys. Reject them and keep value spans so that only
// the two necessary values in the original outer manifest need to be rewritten.
function strictJson(bytes) {
  const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  let value;
  try { value = JSON.parse(text); } catch { throw new Error('invalid manifest JSON'); }
  let i = 0;
  const ws = () => { while (/\s/.test(text[i] ?? '') && i < text.length) i++; };
  function string() {
    const start = i++;
    while (i < text.length) {
      if (text[i] === '\\') { i += 2; continue; }
      if (text[i++] === '"') return JSON.parse(text.slice(start, i));
    }
    throw new Error('unterminated JSON string');
  }
  function node(depth = 0) {
    if (depth > 128) throw new Error('manifest nesting exceeds limit');
    ws();
    const start = i;
    if (text[i] === '{') {
      i++; ws();
      const members = new Map();
      while (text[i] !== '}') {
        const key = string(); ws(); i++; // JSON.parse already checked ':'
        const child = node(depth + 1);
        if (members.has(key)) throw new Error('duplicate manifest key');
        members.set(key, child); ws();
        if (text[i] !== ',') break;
        i++; ws();
      }
      i++;
      return { start, end: i, members };
    }
    if (text[i] === '[') {
      i++; ws();
      const items = [];
      while (text[i] !== ']') {
        items.push(node(depth + 1)); ws();
        if (text[i] !== ',') break;
        i++; ws();
      }
      i++;
      return { start, end: i, items };
    }
    if (text[i] === '"') return { start, end: (string(), i) };
    while (i < text.length && !/[\s,}\]]/.test(text[i])) i++;
    return { start, end: i };
  }
  const tree = node(); ws();
  if (i !== text.length) throw new Error('trailing manifest content');
  return { text, tree, value };
}

async function inspectZip(path, desktop, { secret = false, limit }) {
  const reader = await desktop.zip.ZipFileReader.open(path, {
    maxEntries: desktop.policy.MAX_ARCHIVE_FILES, maxCentralDirectoryBytes: MAX_METADATA,
  });
  try {
    const entries = desktop.policy.archiveEntries(reader);
    try {
      if (secret) desktop.policy.validateSecretsArchive(entries);
      else desktop.policy.validateImportArchive(entries);
    } catch { throw new Error('portable ZIP archive policy rejected'); }
    const names = new Map(), files = new Map();
    let total = 0;
    for (const entry of reader.entries) {
      const name = entry.isDirectory ? entry.name.replace(/\/$/, '') : entry.name;
      if (!name || name.includes('\\') || /[\x00-\x1f\x7f]/.test(name) || desktop.port.isUnsafeArchivePath(name)) {
        throw new Error('unsafe or noncanonical ZIP entry name');
      }
      if (names.has(name)) throw new Error('duplicate ZIP entry');
      names.set(name, entry);
      if (!Number.isSafeInteger(entry.size) || entry.size < 0) throw new Error('invalid ZIP entry size');
      total += entry.size;
      if (!Number.isSafeInteger(total) || total > limit) throw new Error('expanded ZIP exceeds conversion budget');
    }
    for (const [name] of names) {
      const parts = name.split('/');
      for (let n = 1; n < parts.length; n++) {
        const parent = names.get(parts.slice(0, n).join('/'));
        if (parent && !parent.isDirectory) throw new Error('ZIP file/directory collision');
      }
    }
    for (const [name, entry] of names) {
      if (entry.isDirectory) continue;
      const hash = createHash('sha256');
      let size = 0;
      for await (const chunk of await reader.openEntryStream(entry)) {
        size += chunk.length;
        if (size > entry.size) throw new Error('ZIP entry size mismatch');
        hash.update(chunk);
      }
      if (size !== entry.size) throw new Error('ZIP entry size mismatch');
      files.set(name, { path: name, size, sha256: hash.digest('hex'), entry });
    }
    return { reader, files, total };
  } catch (error) { await reader.close(); throw error; }
}

async function smallEntry(catalog, name) {
  const record = catalog.files.get(name);
  if (!record || record.size > MAX_METADATA) throw new Error(`missing or oversized ${name}`);
  return catalog.reader.readEntry(record.entry);
}

async function extractEntry(catalog, name, destination) {
  const record = catalog.files.get(name);
  if (!record) throw new Error(`missing ${name}`);
  const handle = await open(destination, 'wx', 0o600);
  try {
    let size = 0;
    const hash = createHash('sha256');
    for await (const chunk of await catalog.reader.openEntryStream(record.entry)) {
      size += chunk.length;
      if (size > record.size) throw new Error('ZIP entry changed while extracting');
      hash.update(chunk);
      await handle.writeFile(chunk);
    }
    if (size !== record.size || hash.digest('hex') !== record.sha256) throw new Error('ZIP entry changed while extracting');
    await handle.sync();
  } finally { await handle.close(); }
}

function validateManifest(document, desktop) {
  try {
    const manifest = desktop.manifest.parseManifest(document.value);
    desktop.manifest.validateUntrusted(manifest);
    if (manifest.snapshot_kind === 'full') desktop.manifest.validateForSlotRestore(manifest);
    return manifest;
  } catch { throw new Error('portable manifest validation failed'); }
}

function validateDeclared(manifest, content) {
  const declared = new Map();
  for (const file of manifest.files) {
    if (declared.has(file.path) || !SHA256.test(file.sha256)) throw new Error('invalid or duplicate declared file');
    declared.set(file.path, file.sha256.toLowerCase());
    const actual = content.get(file.path);
    if (!actual || actual.size !== file.size || actual.sha256 !== file.sha256.toLowerCase()) {
      throw new Error('declared file integrity mismatch');
    }
  }
  for (const asset of manifest.assets?.files ?? []) {
    if (asset.is_directory) continue;
    const actual = content.get(asset.relative_path);
    const expected = asset.checksum?.toLowerCase() || declared.get(asset.relative_path);
    if (!expected || !SHA256.test(expected) || !actual || actual.size !== asset.size || actual.sha256 !== expected) {
      throw new Error('asset integrity is missing or mismatched');
    }
    const previous = declared.get(asset.relative_path);
    if (previous && previous !== expected) throw new Error('conflicting asset declarations');
    declared.set(asset.relative_path, expected);
  }
  if (declared.size !== content.size || [...content.keys()].some(path => !declared.has(path))) {
    throw new Error('ZIP includes content outside the original manifest');
  }
}

function expectedOuter(original, payload, desktop) {
  const expected = structuredClone(original);
  const excluded = desktop.policy.isPortableExcludedRelativePath;
  expected.files = expected.files.filter(file => !excluded(file.path));
  if (expected.coverage) for (const domain of Object.values(expected.coverage.domains)) {
    if (domain.encrypted || domain.restore_target === 'databases/audit.db') {
      Object.assign(domain, { status: 'excluded', paths: [], file_count: 0, total_size: 0,
        detail: 'excluded from unencrypted portable archive' });
    }
  }
  if (expected.assets) {
    expected.assets.files = expected.assets.files.filter(asset => !excluded(asset.relative_path) && !excluded(asset.original_path));
    expected.assets.total_files = expected.assets.files.length;
    expected.assets.total_size = expected.assets.files.reduce((sum, asset) => sum + asset.size, 0);
  }
  expected.key_policy = 'excluded_portable';
  desktop.manifest.markPartial(expected);
  expected.key_policy = 'included_encrypted';
  expected.files.push(payload);
  if (expected.coverage) for (const domain of Object.values(expected.coverage.domains)) {
    if (domain.encrypted || domain.restore_target === 'databases/audit.db') {
      domain.detail = 'sealed into password-encrypted portable payload';
    }
  }
  return expected;
}

function checksums(text, files, replacements = {}) {
  const seen = new Set();
  const lines = text.split('\n').map(line => {
    if (line === '') return line;
    const match = /^([0-9a-fA-F]{64})  (.+)$/.exec(line);
    if (!match || seen.has(match[2]) || match[2] === CHECKSUMS) throw new Error('invalid or duplicate checksum entry');
    const actual = files.get(match[2]);
    if (!actual || actual.sha256 !== match[1].toLowerCase()) throw new Error('outer checksum mismatch');
    seen.add(match[2]);
    return (replacements[match[2]] ?? match[1]) + line.slice(64);
  });
  if (seen.size !== files.size - 1) throw new Error('outer checksums do not cover all files');
  return Buffer.from(lines.join('\n'));
}

function replacePayloadDeclaration(document, newSize, newHash) {
  const files = document.tree.members?.get('files')?.items;
  const index = document.value.files.findIndex(file => file.path === PAYLOAD);
  const node = files?.[index];
  const size = node?.members?.get('size'), hash = node?.members?.get('sha256');
  if (!size || !hash) throw new Error('missing payload declaration fields');
  const edits = [{ ...size, replacement: String(newSize) }, { ...hash, replacement: JSON.stringify(newHash) }];
  let result = document.text;
  for (const edit of edits.sort((a, b) => b.start - a.start)) {
    result = result.slice(0, edit.start) + edit.replacement + result.slice(edit.end);
  }
  return Buffer.from(result);
}

async function enoughSpace(path, bytes) {
  const free = await statfs(path, { bigint: true });
  if (free.bavail * free.bsize < BigInt(bytes) + 1048576n) throw new Error('portable conversion scratch disk budget is unavailable');
}

export async function convertPortableV1({ input, output, scratch, password, maxInputBytes, maxPlaintextBytes,
  desktop, desktopCommit, directoryRecords, canonical, importDesktop, convertRaw }) {
  const outer = await inspectZip(input, desktop, { limit: maxInputBytes });
  let inner;
  try {
    const outerDocument = strictJson(await smallEntry(outer, MANIFEST));
    const outerManifest = validateManifest(outerDocument, desktop);
    if (!isDeepStrictEqual(outerDocument.value, outerManifest)) throw new Error('unknown or noncanonical outer manifest metadata');
    if (outerManifest.key_policy !== 'included_encrypted') throw new Error('portable ZIP must declare included_encrypted');
    const payloads = outerManifest.files.filter(file => file.path === PAYLOAD);
    if (payloads.length !== 1 || payloads[0].database_id != null) throw new Error('portable ZIP must declare one sealed payload');
    const payload = outer.files.get(PAYLOAD);
    if (!payload || !SHA256.test(payloads[0].sha256) || payload.size !== payloads[0].size ||
        payload.sha256 !== payloads[0].sha256.toLowerCase()) throw new Error('sealed payload declaration mismatch');
    let checksumText;
    if (outer.files.has(CHECKSUMS)) {
      checksumText = new TextDecoder('utf-8', { fatal: true }).decode(await smallEntry(outer, CHECKSUMS));
      checksums(checksumText, outer.files);
    }
    await enoughSpace(scratch, outer.total * 3 + payload.size * 3);
    const oldPayload = join(scratch, 'portable-v1.dsbk'), newPayload = join(scratch, 'portable-v2.dsbk');
    await extractEntry(outer, PAYLOAD, oldPayload);
    const raw = await convertRaw(oldPayload, newPayload);
    inner = await inspectZip(raw.plaintextPath, desktop, { secret: true, limit: maxPlaintextBytes });
    const originalDocument = strictJson(await smallEntry(inner, MANIFEST));
    const original = validateManifest(originalDocument, desktop);
    if (!isDeepStrictEqual(originalDocument.value, original)) throw new Error('unknown or noncanonical original manifest metadata');
    if (!['included_local', 'not_present'].includes(original.key_policy) || original.snapshot_kind === 'legacy_candidate') {
      throw new Error('invalid original manifest policy');
    }
    if (!isDeepStrictEqual(outerManifest, expectedOuter(original, payloads[0], desktop))) {
      throw new Error('outer manifest differs from authenticated original');
    }
    const content = new Map();
    for (const [name, file] of outer.files) if (![MANIFEST, PAYLOAD, CHECKSUMS].includes(name)) content.set(name, file);
    for (const [name, file] of inner.files) if (name !== MANIFEST) {
      if (content.has(name)) throw new Error('portable layers collide');
      content.set(name, file);
    }
    validateDeclared(original, content);
    await enoughSpace(scratch, (outer.total + inner.total) * 3 + payload.size * 2);
    const beforeDir = join(scratch, 'desktop-original'), afterDir = join(scratch, 'desktop-converted');
    try { await importDesktop(input, beforeDir); }
    catch { throw new Error('frozen desktop ZIP import rejected original'); }
    const before = await directoryRecords(beforeDir);

    const manifestBytes = replacePayloadDeclaration(outerDocument, raw.containerBytes, raw.containerSha256);
    // The whole original outer manifest, apart from the two payload values, remains byte-for-byte intact.
    const updatedManifest = strictJson(manifestBytes);
    const parsedUpdated = validateManifest(updatedManifest, desktop);
    const expected = structuredClone(outerManifest);
    Object.assign(expected.files.find(file => file.path === PAYLOAD), { size: raw.containerBytes, sha256: raw.containerSha256 });
    if (!isDeepStrictEqual(parsedUpdated, expected)) throw new Error('unexpected outer manifest change');
    const checksumBytes = checksumText === undefined ? undefined : checksums(checksumText, outer.files, {
      [MANIFEST]: digest(manifestBytes), [PAYLOAD]: raw.containerSha256,
    });
    const writer = await desktop.zip.ZipFileWriter.create(output);
    try {
      for (const entry of outer.reader.entries) {
        if (entry.isDirectory) { await writer.addDirectory(entry.name); continue; }
        const options = { compression: entry.compression, sizeHint: entry.size };
        if (entry.name === MANIFEST) await writer.addBuffer(entry.name, manifestBytes, options);
        else if (entry.name === PAYLOAD) await writer.addFile(entry.name, newPayload, options);
        else if (entry.name === CHECKSUMS) await writer.addBuffer(entry.name, checksumBytes, options);
        else await writer.addStream(entry.name, await outer.reader.openEntryStream(entry), options);
      }
      await writer.finish();
    } catch (error) { await writer.abort(); throw error; }
    // Re-read the candidate's structure, all outer digests and optional checksum ledger.
    const candidate = await inspectZip(output, desktop, { limit: maxInputBytes + raw.containerBytes });
    try {
      if (candidate.files.size !== outer.files.size || candidate.files.get(MANIFEST)?.sha256 !== digest(manifestBytes) ||
          candidate.files.get(PAYLOAD)?.sha256 !== raw.containerSha256) throw new Error('converted ZIP metadata or payload mismatch');
      if (candidate.files.has(CHECKSUMS)) checksums(new TextDecoder('utf-8', { fatal: true }).decode(await smallEntry(candidate, CHECKSUMS)), candidate.files);
      for (const [name, record] of outer.files) {
        if (![MANIFEST, PAYLOAD, CHECKSUMS].includes(name)) {
          const actual = candidate.files.get(name);
          if (!actual || actual.size !== record.size || actual.sha256 !== record.sha256) throw new Error('outer content changed');
        }
      }
    } finally { await candidate.reader.close(); }
    try { await importDesktop(output, afterDir); }
    catch { throw new Error('frozen desktop ZIP import rejected converted output'); }
    const after = await directoryRecords(afterDir);
    if (!isDeepStrictEqual(after, before)) throw new Error('desktop import content changed after conversion');
    return { format: 'dstu.s2-14.portable-v1-to-v2-conversion.v1', desktopCommit,
      plaintextBytes: raw.plaintextBytes, plaintextSha256: raw.plaintextSha256,
      sealedContainerBytes: raw.containerBytes, sealedContainerSha256: raw.containerSha256,
      importedFileCount: before.length, importedCanonicalSha256: canonical(before),
      desktopImport: 'frozen ZIP import succeeded before and after conversion; no slot restore',
      outerChecksumsVerified: checksumText !== undefined };
  } finally {
    if (inner) await inner.reader.close();
    await outer.reader.close();
  }
}
