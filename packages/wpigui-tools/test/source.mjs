import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import test from 'node:test';

async function fixture(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'wpigui-source-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const write = (file, text) => {
    fs.mkdirSync(path.dirname(path.join(dir, file)), { recursive: true });
    fs.writeFileSync(path.join(dir, file), text);
  };
  for (const file of ['source.mjs', 'toolchain.mjs']) fs.copyFileSync(new URL(`../${file}`, import.meta.url), path.join(dir, file));
  for (const [file, text] of Object.entries({
    'build.mjs': 'build',
    'targets/2026.mjs': 'export const apps = { outlineviewer: {} };',
    'targets/2027.mjs': 'export const apps = { outlineviewer: {} };',
    'patches/2026/test.patch': '2026 patch',
    'patches/2027/test.patch': '2027 patch',
    'src/main.cpp': '2026 source',
    'src/2027/main.cpp': '2027 source',
    'src/shell/shell.html': 'shared shell',
  })) write(file, text);
  return { dir, write, ...await import(pathToFileURL(path.join(dir, 'source.mjs')).href) };
}

test('source hashes separate the seasons and include the shared shell', async (t) => {
  const { write, sourceHash } = await fixture(t);
  const original2026 = sourceHash('2026');
  const original2027 = sourceHash('2027');
  write('src/main.cpp', 'changed 2026 source');
  assert.notEqual(sourceHash('2026'), original2026);
  assert.equal(sourceHash('2027'), original2027);
  const changed2026 = sourceHash('2026');
  write('src/2027/main.cpp', 'changed 2027 source');
  assert.equal(sourceHash('2026'), changed2026);
  assert.notEqual(sourceHash('2027'), original2027);
  const changed2027 = sourceHash('2027');
  write('src/shell/shell.html', 'changed shared shell');
  assert.notEqual(sourceHash('2026'), changed2026);
  assert.notEqual(sourceHash('2027'), changed2027);
});

test('the tool check distinguishes current, stale and unstamped builds', async (t) => {
  const { dir, write, sourceHash, checkTools } = await fixture(t);
  const runtime = path.join(dir, 'runtime');
  const stamp = 'runtime/2026/tools/outlineviewer/source.txt';
  write(stamp, sourceHash('2026'));
  assert.deepEqual(await checkTools(runtime), { stale: [], unstamped: [] });
  write('src/main.cpp', 'changed source after build');
  assert.deepEqual(await checkTools(runtime), { stale: ['2026/outlineviewer'], unstamped: [] });
  fs.unlinkSync(path.join(dir, stamp));
  assert.deepEqual(await checkTools(runtime), { stale: [], unstamped: ['2026/outlineviewer'] });
});
