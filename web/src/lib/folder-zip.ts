/**
 * Turns a folder the person picked or dropped into a zip in the browser, so a codebase can be
 * uploaded without zipping it first. Dependency, version-control and build folders are left out
 * (Notch skips them anyway), as are files too big or too binary to be source code. Entries are
 * stored uncompressed: the zip is only a container for the upload.
 */

export type PickedFile = { path: string; file: File };

const SKIPPED_DIRS = new Set([
  "node_modules", ".git", "dist", "build", "target", ".next", "out", "coverage", "venv", ".venv", "env",
  "__pycache__", ".idea", ".vscode", ".gradle", ".turbo", ".cache", "vendor", "bin", "obj",
]);
const SKIPPED_EXT = new Set([
  "png", "jpg", "jpeg", "gif", "webp", "ico", "svg", "pdf", "zip", "gz", "tar", "rar", "7z", "mp4", "mov", "mp3",
  "wav", "woff", "woff2", "ttf", "otf", "eot", "exe", "dll", "so", "dylib", "class", "jar", "pyc", "lock", "map",
  "sqlite", "db", "bin",
]);
const MAX_FILE_BYTES = 300_000;  // the same ceiling the server uses for a source file
export const MAX_FILES = 5000;

/** Whether a file inside the picked folder is worth sending. Paths use "/". */
export function keep(path: string, size: number): boolean {
  const parts = path.split("/");
  for (const dir of parts.slice(0, -1)) if (SKIPPED_DIRS.has(dir)) return false;
  const name = parts[parts.length - 1].toLowerCase();
  if (name === ".ds_store" || name.endsWith(".min.js") || name.endsWith(".min.css")) return false;
  if (name.endsWith("-lock.json") || name === "yarn.lock" || name === "pnpm-lock.yaml") return false;
  // Local settings (usually gitignored, often holding keys) aren't part of the code; examples are.
  if (/^\.env(\..+)?$/.test(name) && !/\.(example|sample|template|dist)$/.test(name)) return false;
  const dot = name.lastIndexOf(".");
  if (dot > 0 && SKIPPED_EXT.has(name.slice(dot + 1))) return false;
  return size <= MAX_FILE_BYTES;
}

/** Files from an <input webkitdirectory> pick. */
export function fromInput(list: FileList): PickedFile[] {
  const out: PickedFile[] = [];
  for (const file of Array.from(list)) {
    const path = (file.webkitRelativePath || file.name).replace(/\\/g, "/");
    if (keep(path, file.size)) out.push({ path, file });
  }
  return out;
}

/** Files from a drag and drop that may hold folders; null when nothing dropped was a folder. */
export async function fromDrop(items: DataTransferItemList): Promise<PickedFile[] | null> {
  const entries: FileSystemEntry[] = [];
  for (const item of Array.from(items)) {
    const entry = item.webkitGetAsEntry?.();
    if (entry) entries.push(entry);
  }
  if (!entries.some((e) => e.isDirectory)) return null;
  const out: PickedFile[] = [];
  for (const entry of entries) await walk(entry, "", out);
  return out;
}

async function walk(entry: FileSystemEntry, prefix: string, out: PickedFile[]): Promise<void> {
  const path = prefix + entry.name;
  if (out.length >= MAX_FILES) return;
  if (entry.isFile) {
    const file = await new Promise<File>((resolve, reject) => (entry as FileSystemFileEntry).file(resolve, reject));
    if (keep(path, file.size)) out.push({ path, file });
    return;
  }
  if (SKIPPED_DIRS.has(entry.name)) return;  // never even read node_modules
  const reader = (entry as FileSystemDirectoryEntry).createReader();
  // readEntries returns a batch at a time; keep reading until it returns nothing.
  for (;;) {
    const batch = await new Promise<FileSystemEntry[]>((resolve, reject) => reader.readEntries(resolve, reject));
    if (batch.length === 0) break;
    for (const child of batch) await walk(child, path + "/", out);
  }
}

// ------------------------------------------------------------------ zip writing

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

export function crc32(data: Uint8Array): number {
  let c = 0xffffffff;
  for (let i = 0; i < data.length; i++) c = CRC_TABLE[(c ^ data[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

/** A zip (stored, UTF-8 names) of the files, as raw bytes in parts. */
export function zipParts(files: { path: string; data: Uint8Array }[]): Uint8Array[] {
  const encoder = new TextEncoder();
  const parts: Uint8Array[] = [];
  const central: Uint8Array[] = [];
  let offset = 0;
  for (const f of files) {
    const name = encoder.encode(f.path);
    const crc = crc32(f.data);
    const local = new DataView(new ArrayBuffer(30));
    local.setUint32(0, 0x04034b50, true);
    local.setUint16(4, 20, true);
    local.setUint16(6, 0x0800, true);  // names are UTF-8
    local.setUint16(8, 0, true);  // stored
    local.setUint32(14, crc, true);
    local.setUint32(18, f.data.length, true);
    local.setUint32(22, f.data.length, true);
    local.setUint16(26, name.length, true);
    parts.push(new Uint8Array(local.buffer), name, f.data);

    const entry = new DataView(new ArrayBuffer(46));
    entry.setUint32(0, 0x02014b50, true);
    entry.setUint16(4, 20, true);
    entry.setUint16(6, 20, true);
    entry.setUint16(8, 0x0800, true);
    entry.setUint32(16, crc, true);
    entry.setUint32(20, f.data.length, true);
    entry.setUint32(24, f.data.length, true);
    entry.setUint16(28, name.length, true);
    entry.setUint32(42, offset, true);
    central.push(new Uint8Array(entry.buffer), name);
    offset += 30 + name.length + f.data.length;
  }
  const centralSize = central.reduce((n, p) => n + p.length, 0);
  const end = new DataView(new ArrayBuffer(22));
  end.setUint32(0, 0x06054b50, true);
  end.setUint16(8, files.length, true);
  end.setUint16(10, files.length, true);
  end.setUint32(12, centralSize, true);
  end.setUint32(16, offset, true);
  return [...parts, ...central, new Uint8Array(end.buffer)];
}

/** Reads the picked files and zips them; the zip is named after the folder. */
export async function zipFolder(picked: PickedFile[]): Promise<File> {
  const files = [];
  for (const p of picked.slice(0, MAX_FILES)) files.push({ path: p.path, data: new Uint8Array(await p.file.arrayBuffer()) });
  const folder = picked[0]?.path.split("/")[0] || "code";
  return new File(zipParts(files) as BlobPart[], `${folder}.zip`, { type: "application/zip" });
}
