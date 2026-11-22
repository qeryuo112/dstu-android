export async function transferArchive(blob, fileName, expectedSha256, bridge) {
  const selected = await bridge.create({ fileName });
  if (selected.cancelled) return { saved: false, cancelled: true };
  let offset = 0;
  try {
    while (offset < blob.size) {
      const chunk = new Uint8Array(await blob.slice(offset, offset + 65536).arrayBuffer());
      let binary = '';
      for (const byte of chunk) binary += String.fromCharCode(byte);
      await bridge.append({ offset: String(offset), data: btoa(binary) });
      offset += chunk.length;
    }
    const receipt = await bridge.finish({ bytes: String(blob.size), sha256: expectedSha256 });
    if (receipt.bytes !== String(blob.size) || receipt.sha256 !== expectedSha256) throw new Error('原生保存回执的大小或 SHA-256 不一致');
    return { saved: true, uri: receipt.uri, bytes: receipt.bytes, sha256: receipt.sha256 };
  } catch (error) {
    try { await bridge.abort(); } catch (cleanupError) { console.error('legacy-export.native-abort', cleanupError); }
    throw error;
  }
}

export async function readOpfsArchive(path) {
  const parts = path.split('/').filter(Boolean);
  if (parts.some(part => part === '.' || part === '..')) throw new Error('非法 OPFS 逻辑路径');
  const name = parts.pop();
  if (!name) throw new Error('缺少归档文件名');
  let directory = await navigator.storage.getDirectory();
  for (const part of parts) directory = await directory.getDirectoryHandle(part);
  return (await directory.getFileHandle(name)).getFile();
}
