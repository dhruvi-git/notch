// Run with: npm test
import { test } from "node:test";
import assert from "node:assert/strict";
import { crc32, keep, zipParts } from "../src/lib/folder-zip.ts";

test("keep leaves out dependencies, builds, binaries, lockfiles and local env files", () => {
  assert.equal(keep("app/src/index.ts", 100), true);
  assert.equal(keep("app/.env.example", 100), true);
  assert.equal(keep("app/node_modules/react/index.js", 100), false);
  assert.equal(keep("app/.git/HEAD", 10), false);
  assert.equal(keep("app/.next/server/page.js", 10), false);
  assert.equal(keep("app/public/logo.png", 10), false);
  assert.equal(keep("app/package-lock.json", 10), false);
  assert.equal(keep("app/.env.local", 10), false);
  assert.equal(keep("app/.env", 10), false);
  assert.equal(keep("app/src/huge.ts", 400_000), false);
});

test("crc32 matches the standard check value", () => {
  assert.equal(crc32(new TextEncoder().encode("123456789")), 0xcbf43926);
});

test("zipParts writes a zip whose directory lists every file with its data", () => {
  const files = [
    { path: "app/src/a.ts", data: new TextEncoder().encode("export const a = 1;\n") },
    { path: "app/src/ünïcode.ts", data: new TextEncoder().encode("export const b = 2;\n") },
  ];
  const bytes = Buffer.concat(zipParts(files));
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const end = bytes.length - 22;
  assert.equal(view.getUint32(end, true), 0x06054b50);
  assert.equal(view.getUint16(end + 10, true), 2);
  let at = view.getUint32(end + 16, true);
  const names: string[] = [];
  for (let i = 0; i < 2; i++) {
    assert.equal(view.getUint32(at, true), 0x02014b50);
    const nameLen = view.getUint16(at + 28, true);
    const local = view.getUint32(at + 42, true);
    const size = view.getUint32(at + 24, true);
    const name = bytes.subarray(at + 46, at + 46 + nameLen).toString("utf8");
    names.push(name);
    assert.equal(view.getUint32(local, true), 0x04034b50);
    const dataStart = local + 30 + view.getUint16(local + 26, true);
    assert.equal(bytes.subarray(dataStart, dataStart + size).toString("utf8"), new TextDecoder().decode(files[i].data));
    assert.equal(view.getUint32(at + 16, true), crc32(files[i].data));
    at += 46 + nameLen;
  }
  assert.deepEqual(names, ["app/src/a.ts", "app/src/ünïcode.ts"]);
});
