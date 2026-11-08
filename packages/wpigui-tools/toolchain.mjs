// Fetches the pinned emsdk and the allwpilib sources into .cache/.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

export const ROOT = path.resolve(import.meta.dirname, '..', '..');
export const CACHE = path.join(ROOT, '.cache');

export const EMSDK_VERSION = '6.0.10';

// The allwpilib tag of each season, and where its work files go in .cache. The
// 2026 files stay where they were: WPILib's logging macros put source paths
// into the binaries, and that keeps its build the same as before.
export const SEASONS = {
  2026: { tag: 'v2026.2.1', work: '' },
  2027: { tag: 'v2027.0.0-alpha-8', work: '2027' },
};

const EMSDK_DIR = path.join(CACHE, `emsdk-${EMSDK_VERSION}`);
const EMSCRIPTEN = path.join(EMSDK_DIR, 'upstream', 'emscripten');

function run(cmd, args, options = {}) {
  execFileSync(cmd, args, { stdio: 'inherit', ...options });
}

export function ensureEmsdk() {
  const version = path.join(EMSCRIPTEN, 'emscripten-version.txt');
  if (!(fs.existsSync(version) && fs.readFileSync(version, 'utf8').includes(EMSDK_VERSION))) {
    if (!fs.existsSync(path.join(EMSDK_DIR, 'emsdk'))) {
      run('git', ['clone', '--depth', '1', 'https://github.com/emscripten-core/emsdk.git', EMSDK_DIR]);
    }
    run('./emsdk', ['install', EMSDK_VERSION], { cwd: EMSDK_DIR });
    run('./emsdk', ['activate', EMSDK_VERSION], { cwd: EMSDK_DIR });
  }
  const env = { ...process.env, EM_CONFIG: path.join(EMSDK_DIR, '.emscripten') };
  return { env, emcc: path.join(EMSCRIPTEN, 'emcc'), empp: path.join(EMSCRIPTEN, 'em++'), emar: path.join(EMSCRIPTEN, 'emar') };
}

// Pristine allwpilib tree, never modified. Patches apply to a copy.
export async function ensureAllwpilib(year) {
  const { tag, work } = SEASONS[year];
  const dir = path.join(CACHE, work, 'allwpilib');
  if (fs.existsSync(path.join(dir, 'wpiutil'))) return dir;
  const url = `https://github.com/wpilibsuite/allwpilib/archive/refs/tags/${tag}.tar.gz`;
  const archive = path.join(CACHE, 'downloads', `allwpilib-${tag}.tar.gz`);
  if (!fs.existsSync(archive)) {
    fs.mkdirSync(path.dirname(archive), { recursive: true });
    const res = await fetch(url);
    if (!res.ok) throw new Error(`GET ${url}: ${res.status}`);
    fs.writeFileSync(`${archive}.part`, Buffer.from(await res.arrayBuffer()));
    fs.renameSync(`${archive}.part`, archive);
  }
  fs.mkdirSync(dir, { recursive: true });
  run('tar', ['-xzf', archive, '-C', dir, '--strip-components=1']);
  return dir;
}
