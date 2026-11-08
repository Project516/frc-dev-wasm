// Builds uutils coreutils as one WASI multicall binary for the browser terminal and writes it to
// apps/ide/public/runtime/terminal/coreutils.wasm. The Rust toolchain is installed into
// .cache/rust, so nothing global changes. It needs a C linker on the host (build scripts).
// TERMINAL_JOBS sets how many crates compile in parallel (default 2, which keeps memory low).
// wasm-opt from the binaryen package shrinks the result. Safe to rerun; a finished build is skipped.
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { UTILITIES } from '../apps/ide/src/frc/terminal/utilities.ts';

const ROOT = path.resolve(import.meta.dirname, '..');
const CACHE = path.join(ROOT, '.cache');
const RUST = path.join(CACHE, 'rust');
const BUILD = path.join(CACHE, 'terminal-build');
const OUT = path.join(ROOT, 'apps', 'ide', 'public', 'runtime', 'terminal');

const RUST_VERSION = '1.90.0';
const UUTILS_VERSION = '0.12.0';
const TARGET = 'wasm32-wasip1';


function run(cmd, args, options = {}) {
  const result = spawnSync(cmd, args, { stdio: 'inherit', ...options });
  if (result.status !== 0) throw new Error(`${cmd} ${args.join(' ')} failed`);
}

// The features rustc enables for wasm32-wasip1. --all-features would also turn on experimental encodings that engines reject.
const WASM_FEATURES = ['--enable-bulk-memory', '--enable-sign-ext', '--enable-nontrapping-float-to-int', '--enable-multivalue', '--enable-mutable-globals', '--enable-reference-types', '--enable-simd'];

const readIfExists = (file) => (fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '');
const sha = (...parts) => createHash('sha256').update(parts.join('\0')).digest('hex');

function hostTriple() {
  const key = `${process.platform}-${process.arch}`;
  const triples = {
    'linux-x64': 'x86_64-unknown-linux-gnu',
    'linux-arm64': 'aarch64-unknown-linux-gnu',
    'darwin-x64': 'x86_64-apple-darwin',
    'darwin-arm64': 'aarch64-apple-darwin',
    'win32-x64': 'x86_64-pc-windows-msvc'
  };
  if (!triples[key]) throw new Error(`No rustup build for ${key}`);
  return triples[key];
}

const exe = process.platform === 'win32' ? '.exe' : '';
const env = {
  ...process.env,
  RUSTUP_HOME: path.join(RUST, 'rustup'),
  CARGO_HOME: path.join(RUST, 'cargo'),
  RUSTUP_TOOLCHAIN: RUST_VERSION,
  PATH: `${path.join(RUST, 'cargo', 'bin')}${path.delimiter}${process.env.PATH}`
};

async function installRust() {
  const cargo = path.join(RUST, 'cargo', 'bin', `cargo${exe}`);
  if (!fs.existsSync(cargo)) {
    const base = `https://static.rust-lang.org/rustup/dist/${hostTriple()}/rustup-init${exe}`;
    const installer = path.join(CACHE, 'downloads', `rustup-init${exe}`);
    fs.mkdirSync(path.dirname(installer), { recursive: true });
    const [bin, checksum] = await Promise.all([fetch(base), fetch(`${base}.sha256`)]);
    if (!bin.ok || !checksum.ok) throw new Error(`Could not download rustup-init from ${base}`);
    const buf = Buffer.from(await bin.arrayBuffer());
    const expected = (await checksum.text()).trim().split(/\s+/)[0];
    if (createHash('sha256').update(buf).digest('hex') !== expected) throw new Error('rustup-init checksum mismatch');
    fs.writeFileSync(installer, buf, { mode: 0o755 });
    run(installer, ['-y', '--no-modify-path', '--profile', 'minimal', '--default-toolchain', RUST_VERSION], { env });
  }
  run(path.join(RUST, 'cargo', 'bin', `rustup${exe}`), ['target', 'add', TARGET], { env });
  return cargo;
}

const WASM_OPT = path.join(ROOT, 'node_modules', 'binaryen', 'bin', 'wasm-opt');
const binaryen = fs.existsSync(WASM_OPT)
  ? JSON.parse(fs.readFileSync(path.join(ROOT, 'node_modules', 'binaryen', 'package.json'), 'utf8')).version
  : 'none';

const buildStamp = sha(RUST_VERSION, UUTILS_VERSION, ...UTILITIES);
const stamp = sha(buildStamp, binaryen, WASM_FEATURES.join(' '));
const stampFile = path.join(OUT, '.stamp');
const output = path.join(OUT, 'coreutils.wasm');
if (fs.existsSync(output) && fs.existsSync(stampFile) && fs.readFileSync(stampFile, 'utf8') === stamp) {
  console.log('coreutils.wasm is up to date');
  process.exit(0);
}

const built = path.join(BUILD, 'bin', 'coreutils.wasm');
const builtStamp = path.join(BUILD, '.stamp');
if (!fs.existsSync(built) || readIfExists(builtStamp) !== buildStamp) {
  const cargo = await installRust();
  fs.mkdirSync(BUILD, { recursive: true });
  run(
    cargo,
    [
      'install', 'coreutils',
      '--version', UUTILS_VERSION,
      '--locked', '--force',
      '--target', TARGET,
      '--root', BUILD,
      '--no-default-features',
      '--features', UTILITIES.join(',')
    ],
    {
      env: {
        ...env,
        CARGO_TARGET_DIR: path.join(BUILD, 'target'),
        CARGO_BUILD_JOBS: process.env.TERMINAL_JOBS ?? '2',
        CARGO_PROFILE_RELEASE_OPT_LEVEL: 'z',
        CARGO_PROFILE_RELEASE_LTO: 'true',
        CARGO_PROFILE_RELEASE_CODEGEN_UNITS: '1',
        CARGO_PROFILE_RELEASE_PANIC: 'abort',
        CARGO_PROFILE_RELEASE_STRIP: 'true'
      }
    }
  );
  fs.writeFileSync(builtStamp, buildStamp);
}

fs.mkdirSync(OUT, { recursive: true });
if (binaryen !== 'none') run(process.execPath, [WASM_OPT, '-Oz', ...WASM_FEATURES, '--strip-debug', built, '-o', output]);
else {
  console.log('binaryen is not installed (run pnpm install); skipping the size optimization');
  fs.copyFileSync(built, output);
}
fs.writeFileSync(stampFile, stamp);
console.log(`${output}: ${(fs.statSync(output).size / 1024 / 1024).toFixed(2)} MiB`);
