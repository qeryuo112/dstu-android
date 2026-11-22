import { createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';

// This semantic corpus deliberately excludes generated database IDs and timestamps.
export const CORPUS_ID = 's1-27-notes-v1';
export const NOTE_COUNT = 64;
export function corpusNotes() {
  return Array.from({ length: NOTE_COUNT }, (_, index) => ({
    title: `S1.27 corpus ${String(index).padStart(3, '0')}`,
    content: Array.from({ length: 12 }, (_, line) =>
      `S1.27 固定语料 ${String(index).padStart(3, '0')} / ${String(line).padStart(2, '0')} 中文 😀\n`).join(''),
    tags: ['s1-27-benchmark'],
  }));
}

// A simple length-prefixed UTF-8 stream is shared with the Android seeder.
export function corpusBytes(notes = corpusNotes()) {
  const chunks = [];
  for (const note of notes) {
    for (const text of [note.title, note.content, ...note.tags]) {
      const bytes = Buffer.from(text, 'utf8');
      chunks.push(Buffer.from(`${bytes.length}:`, 'ascii'), bytes);
    }
  }
  return Buffer.concat(chunks);
}
export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
export const corpusSha256 = () => sha256(corpusBytes());

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const command = process.argv[2] ?? 'describe';
  if (command === 'describe') console.log(JSON.stringify({ id: CORPUS_ID, noteCount: NOTE_COUNT, sha256: corpusSha256() }, null, 2));
  else if (command === 'json') console.log(JSON.stringify({ id: CORPUS_ID, sha256: corpusSha256(), notes: corpusNotes() }, null, 2));
  else { console.error('Usage: node tools/s1/benchmark-corpus.mjs [describe|json]'); process.exitCode = 2; }
}
