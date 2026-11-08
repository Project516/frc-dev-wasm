// Builds AdvantageScope Lite from source and serves it from
// apps/ide/public/runtime/tools/advantagescope/. Heavy (npm install, rollup, emscripten),
// so it is separate from fetch-deps. Safe to rerun; finished steps are skipped.
// Set NODE_OPTIONS=--max-old-space-size=<MB> to cap rollup's memory.
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = path.resolve(import.meta.dirname, '..');
const CACHE = path.join(ROOT, '.cache');
const SRC = path.join(CACHE, 'advantagescope');
const PATCHES = path.join(ROOT, 'tools', 'advantagescope', 'patches');
const OUT = path.join(ROOT, 'apps', 'ide', 'public', 'runtime', 'tools', 'advantagescope');

const REPO = 'https://github.com/Mechanical-Advantage/AdvantageScope.git';
const COMMIT = 'd2e915f580ca4ad9444a5211bf89fe71b128de68'; // v26.0.2
const EMSCRIPTEN_VERSION = '4.0.12';
const EMSDK = path.join(CACHE, `emsdk-${EMSCRIPTEN_VERSION}`);
const ROLLUP_CONFIGS = ['configMain', 'configLargeRenderers', 'configSmallRenderers', 'configWorkers'];

function run(cmd, args, options = {}) {
  const result = spawnSync(cmd, args, { stdio: 'inherit', ...options });
  if (result.status !== 0) throw new Error(`${cmd} ${args.join(' ')} failed`);
}

function capture(cmd, args, options = {}) {
  const result = spawnSync(cmd, args, { encoding: 'utf8', ...options });
  return result.status === 0 ? result.stdout : '';
}

const sha = (...parts) => createHash('sha256').update(parts.join('\0')).digest('hex');
const readIfExists = (file) => (fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '');
const inSrc = (...p) => path.join(SRC, ...p);

function fetchSource() {
  if (fs.existsSync(inSrc('.git')) && capture('git', ['rev-parse', 'HEAD'], { cwd: SRC }).trim() === COMMIT) return;
  fs.rmSync(SRC, { recursive: true, force: true });
  fs.mkdirSync(SRC, { recursive: true });
  run('git', ['init', '-q'], { cwd: SRC });
  run('git', ['remote', 'add', 'origin', REPO], { cwd: SRC });
  run('git', ['fetch', '-q', '--depth', '1', 'origin', COMMIT], { cwd: SRC });
  run('git', ['checkout', '-q', 'FETCH_HEAD'], { cwd: SRC });
}

// Returns the patch-set hash and whether the sources were repatched (so the build is stale).
function applyPatches() {
  const files = fs.readdirSync(PATCHES).filter((f) => f.endsWith('.patch')).sort();
  const hash = sha(COMMIT, ...files.map((f) => fs.readFileSync(path.join(PATCHES, f), 'utf8')));
  const stamp = inSrc('.frc-patched');
  if (readIfExists(stamp) === hash) return { hash, changed: false };
  run('git', ['checkout', '-q', '--', '.'], { cwd: SRC });
  for (const file of files) {
    console.log(`patch ${file}`);
    run('git', ['apply', path.join(PATCHES, file)], { cwd: SRC });
  }
  fs.rmSync(inSrc('.frc-built'), { force: true });
  fs.writeFileSync(stamp, hash);
  return { hash, changed: true };
}

function install() {
  const stamp = inSrc('node_modules', '.frc-lock');
  const lockHash = sha(fs.readFileSync(inSrc('package-lock.json')));
  if (readIfExists(stamp) === lockHash) return;
  // --allow-git is needed for the git dependencies in the lockfile; none run install scripts here.
  run('npm', ['ci', '--ignore-scripts', '--no-audit', '--no-fund', '--allow-git=all'], {
    cwd: SRC,
    env: { ...process.env, ELECTRON_SKIP_BINARY_DOWNLOAD: '1' },
  });
  fs.writeFileSync(stamp, lockHash);
}

function prepareAssets() {
  if (!fs.existsSync(inSrc('src', 'licenses.json'))) run('node', ['getLicenses.mjs'], { cwd: SRC });
  run('node', ['bundleLiteAssets.mjs'], { cwd: SRC });
}

function compile() {
  const rollup = inSrc('node_modules', 'rollup', 'dist', 'bin', 'rollup');
  for (const config of ROLLUP_CONFIGS) {
    console.log(`rollup ${config}`);
    run('node', [rollup, '-c', `--${config}`], { cwd: SRC, env: { ...process.env, ASCOPE_DISTRIBUTION: 'LITE' } });
  }
}

function emscriptenEnv() {
  const emccPath = path.join(EMSDK, 'upstream', 'emscripten');
  const env = { ...process.env, PATH: `${emccPath}${path.delimiter}${EMSDK}${path.delimiter}${process.env.PATH}` };
  return capture('emcc', ['--version'], { env }).includes(EMSCRIPTEN_VERSION) ? env : null;
}

function compileWasm() {
  if (fs.existsSync(inSrc('lite', 'static', 'bundles', 'hub$wpilogIndexer.wasm'))) return;
  let env = emscriptenEnv();
  if (!env) {
    if (!fs.existsSync(EMSDK)) run('git', ['clone', '-q', '--depth', '1', 'https://github.com/emscripten-core/emsdk.git', EMSDK]);
    run(path.join(EMSDK, 'emsdk'), ['install', EMSCRIPTEN_VERSION], { cwd: EMSDK });
    run(path.join(EMSDK, 'emsdk'), ['activate', EMSCRIPTEN_VERSION], { cwd: EMSDK });
    env = emscriptenEnv();
    if (!env) throw new Error(`emcc ${EMSCRIPTEN_VERSION} is not available`);
  }
  run('node', ['wasmCompile.mjs'], { cwd: SRC, env });
}

// Same JSON that lite/lite_server.py serves for GET /assets.
function assetIndex(dir) {
  const index = {};
  const walk = (current) => {
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const file = path.join(current, entry.name);
      if (entry.isDirectory()) walk(file);
      else if (!entry.name.startsWith('.')) {
        let contents = null;
        if (entry.name === 'config.json') {
          try {
            contents = JSON.parse(fs.readFileSync(file, 'utf8'));
          } catch {
            // Invalid config stays null, as in the server.
          }
        }
        index[path.relative(dir, file).split(path.sep).join('/')] = contents;
      }
    }
  };
  walk(dir);
  return JSON.stringify(index).replace(/[\u0080-￿]/g, (c) => `\\u${c.charCodeAt(0).toString(16).padStart(4, '0')}`);
}

const DOCS_STUB = `<!doctype html>
<meta charset="utf-8">
<title>AdvantageScope documentation</title>
<style>body{font:16px system-ui,sans-serif;margin:2rem;color:#222}@media(prefers-color-scheme:dark){body{background:#222;color:#ddd}a{color:#8ab4f8}}</style>
<p>The documentation is not bundled with this copy of AdvantageScope.</p>
<p><a href="https://docs.advantagescope.org" target="_blank" rel="noopener">Open docs.advantagescope.org</a></p>
`;

function injectShim(file, shimPath) {
  const html = fs.readFileSync(file, 'utf8');
  if (!html.includes('<head>')) throw new Error(`${file} has no <head>`);
  fs.writeFileSync(file, html.replace('<head>', `<head>\n    <script src="${shimPath}"></script>`));
}

function assemble(hash) {
  fs.rmSync(OUT, { recursive: true, force: true });
  const staticDir = inSrc('lite', 'static');
  fs.cpSync(staticDir, OUT, {
    recursive: true,
    dereference: true,
    filter: (file) => {
      const rel = path.relative(staticDir, file);
      return rel !== 'docs' && rel !== 'bundledAssets' && !rel.endsWith('.map');
    },
  });
  // FTC assets are large and unused by an FRC tool.
  fs.cpSync(path.join(staticDir, 'bundledAssets'), path.join(OUT, 'assets'), {
    recursive: true,
    filter: (file) => !path.basename(file).includes('FTC'),
  });
  fs.writeFileSync(path.join(OUT, 'assets', 'index.json'), assetIndex(path.join(OUT, 'assets')));
  fs.writeFileSync(path.join(OUT, 'logs'), '[]');
  fs.mkdirSync(path.join(OUT, 'docs', 'build'), { recursive: true });
  fs.writeFileSync(path.join(OUT, 'docs', 'build', 'index.html'), DOCS_STUB);
  // The NT4 client runs in the hub frame, so it needs the shim too.
  injectShim(path.join(OUT, 'index.html'), '../../nt4-socket-shim.js');
  injectShim(path.join(OUT, 'www', 'hub.html'), '../../../nt4-socket-shim.js');
  fs.writeFileSync(path.join(OUT, '.frc-built'), hash);
}

function main() {
  fetchSource();
  const { hash } = applyPatches();
  if (readIfExists(path.join(OUT, '.frc-built')) === hash && readIfExists(inSrc('.frc-built')) === hash) {
    console.log('advantagescope is up to date');
    return;
  }
  install();
  prepareAssets();
  if (readIfExists(inSrc('.frc-built')) !== hash) {
    compile();
    compileWasm();
    fs.writeFileSync(inSrc('.frc-built'), hash);
  }
  assemble(hash);
  console.log(`advantagescope built into ${path.relative(ROOT, OUT)}`);
}

main();
