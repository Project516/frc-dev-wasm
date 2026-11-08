// Builds WPILib's ImGui tools (Glass, OutlineViewer, ...) to WebAssembly with Emscripten.
//
//   node build.mjs [--year Y]... [--jobs N] [app ...]   (WPIGUI_JOBS sets the default for N)
//
// Builds the tools of each season in `--year` (default: the seasons the site serves).
// Output goes to apps/ide/public/runtime/<year>/tools/<app>/. Everything else
// lives in .cache/wpigui-tools (2027: .cache/2027/wpigui-tools): a patched copy of
// the allwpilib sources, generated files and object files. Rerunning only
// recompiles what changed.
import { execFileSync, spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { STAMP, sourceHash } from './source.mjs';
import { CACHE, ROOT, SEASONS, ensureAllwpilib, ensureEmsdk } from './toolchain.mjs';

const PKG = import.meta.dirname;
const SHELL = path.join(PKG, 'src', 'shell');
const SKIP_DIRS = new Set(['java', 'test', 'dev', 'examples']);

const args = process.argv.slice(2);
const jobsIndex = args.indexOf('--jobs');
const jobs = jobsIndex >= 0 ? Number(args.splice(jobsIndex, 2)[1]) : Number(process.env.WPIGUI_JOBS) || Math.max(1, Math.min(2, os.cpus().length));
const years = [];
for (let i = args.indexOf('--year'); i >= 0; i = args.indexOf('--year')) years.push(args.splice(i, 2)[1]);
for (const year of years) if (!SEASONS[year]) throw new Error(`WPILib ${year} is not one of ${Object.keys(SEASONS).join(', ')}`);
if (!years.length) years.push(...(await import('../runtime/src/years.ts')).WPILIB_YEARS);

// The season being built, see useYear.
let YEAR, BUILD, SRC, GEN, OBJ, GLUE, OUT, targets, apps, libs;

async function useYear(year) {
  YEAR = year;
  targets = await import(`./targets/${year}.mjs`);
  ({ apps, libs } = targets);
  BUILD = path.join(CACHE, SEASONS[year].work, 'wpigui-tools');
  SRC = path.join(BUILD, 'src');
  GEN = path.join(BUILD, 'gen');
  OBJ = path.join(BUILD, 'obj');
  GLUE = year === '2026' ? path.join(PKG, 'src') : path.join(PKG, 'src', year);
  OUT = path.join(ROOT, 'apps', 'ide', 'public', 'runtime', year, 'tools');
}

// Patched copy of the allwpilib sources, so the pristine tree stays untouched.
function prepareSource(upstream, year) {
  const patchDir = path.join(PKG, 'patches', year);
  const patches = fs.readdirSync(patchDir).filter((f) => f.endsWith('.patch')).sort();
  const stamp = createHash('sha1');
  stamp.update(upstream);
  for (const p of patches) stamp.update(fs.readFileSync(path.join(patchDir, p)));
  const id = stamp.digest('hex');
  const stampFile = path.join(BUILD, 'src.stamp');
  if (fs.existsSync(stampFile) && fs.readFileSync(stampFile, 'utf8') === id) return;

  fs.rmSync(SRC, { recursive: true, force: true });
  for (const dir of targets.sourceDirs) {
    fs.cpSync(path.join(upstream, dir), path.join(SRC, dir), {
      recursive: true,
      preserveTimestamps: true,
      filter: (file) => !SKIP_DIRS.has(path.basename(file)),
    });
  }
  for (const p of patches) {
    execFileSync('git', ['apply', '-p1', '--unsafe-paths', '--directory', '.', path.join(patchDir, p)], { cwd: SRC, stdio: 'inherit' });
  }
  fs.writeFileSync(stampFile, id);
}

function walk(dir, recursive, ext = '.cpp') {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (recursive) out.push(...walk(full, true, ext));
    } else if (entry.name.endsWith(ext)) {
      out.push(full);
    }
  }
  return out.sort();
}

const resolve = (p) => (p.startsWith('GLUE/') ? path.join(GLUE, p.slice(5)) : p.startsWith('GEN/') ? path.join(GEN, p.slice(4)) : path.join(SRC, p));

function displayName(file) {
  if (file.startsWith(SRC)) return path.relative(SRC, file);
  return file.startsWith(GEN) ? `gen/${path.relative(GEN, file)}` : `glue/${path.relative(GLUE, file)}`;
}

function sourcesOf(target) {
  const files = [];
  for (const entry of target.src ?? []) {
    if (typeof entry === 'string') {
      files.push(resolve(entry));
      continue;
    }
    files.push(...walk(resolve(entry.dir), entry.recursive, entry.ext).filter((f) => !(entry.exclude ?? []).some((re) => re.test(f))));
  }
  return files;
}

// Same output as allwpilib's GenResource.cmake, written as a string literal
// because clang compiles that much faster than a multi-megabyte byte list.
function generateResource(input, output, prefix, namespace) {
  const data = fs.readFileSync(input);
  const func = `GetResource_${path.basename(input).replace(/[^a-zA-Z0-9]/g, '_')}`;
  const lines = [];
  for (let i = 0; i < data.length; i += 64) {
    lines.push(`"${Array.from(data.subarray(i, i + 64), (b) => `\\${b.toString(8).padStart(3, '0')}`).join('')}"`);
  }
  const body = [
    '#include <stddef.h>',
    '#include <string_view>',
    'extern "C" {',
    `static const unsigned char contents[] =`,
    ...(lines.length ? lines : ['""']),
    ';',
    `const unsigned char* ${prefix}${func}(size_t* len) {`,
    `  *len = ${data.length};`,
    '  return contents;',
    '}',
    '}',
    namespace ? `namespace ${namespace} {` : '',
    `std::string_view ${func}() {`,
    `  return std::string_view(reinterpret_cast<const char*>(contents), ${data.length});`,
    '}',
    namespace ? '}' : '',
    '',
  ].join('\n');
  if (!fs.existsSync(output) || fs.readFileSync(output, 'utf8') !== body) {
    fs.mkdirSync(path.dirname(output), { recursive: true });
    fs.writeFileSync(output, body);
  }
}

function generateFiles(target, name) {
  const out = [];
  for (const res of target.resources ?? []) {
    const dir = resolve(res.dir);
    const files = res.recursive ? walk(dir, true, '').map((f) => path.relative(dir, f)) : fs.readdirSync(dir).sort();
    for (const file of files) {
      const base = path.basename(file);
      if (base.startsWith('.') || !fs.statSync(path.join(dir, file)).isFile() || (res.match && !res.match.test(base))) continue;
      const output = path.join(GEN, 'resources', name, `${base}.cpp`);
      generateResource(path.join(dir, file), output, res.prefix, res.namespace);
      out.push(output);
    }
  }
  return out;
}

function generateVersion() {
  const file = path.join(GEN, 'WPILibVersion.cpp');
  fs.mkdirSync(GEN, { recursive: true });
  fs.writeFileSync(file, `const char* GetWPILibVersion() {\n  return "${targets.WPILIB_VERSION}";\n}\n`);
}

function closure(names, all) {
  const seen = [];
  const visit = (name) => {
    if (seen.includes(name)) return;
    seen.push(name);
    for (const dep of all[name].deps ?? []) visit(dep);
  };
  names.forEach(visit);
  return seen;
}

function includeFlags(names) {
  const flags = [];
  for (const name of closure(names, libs)) {
    for (const inc of libs[name].inc ?? []) flags.push(`-I${resolve(inc)}`);
    for (const def of libs[name].defs ?? []) flags.push(`-D${def}`);
  }
  return flags;
}

// Upstream relies on transitive includes that this libc++ no longer provides.
const UPSTREAM = ['-w', '-include', 'cstdlib'];

// Runs every task even after a failure, so one pass shows all compile errors.
async function runPool(tasks, limit) {
  let next = 0;
  const errors = [];
  const worker = async () => {
    while (next < tasks.length) await tasks[next++]().catch((err) => errors.push(err.message));
  };
  await Promise.all(Array.from({ length: limit }, worker));
  if (errors.length) throw new Error(errors.join('\n\n'));
}

function spawnAsync(cmd, cmdArgs, env, label) {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(cmd, cmdArgs, { env, stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '';
    child.stdout.on('data', (d) => (output += d));
    child.stderr.on('data', (d) => (output += d));
    child.on('close', (code) => {
      if (code === 0) resolvePromise(output);
      else reject(new Error(`${label} failed\n${cmd} ${cmdArgs.join(' ')}\n${output}`));
    });
  });
}

function depsUpToDate(objFile, depFile) {
  if (!fs.existsSync(objFile) || !fs.existsSync(depFile)) return false;
  const objTime = fs.statSync(objFile).mtimeMs;
  const text = fs.readFileSync(depFile, 'utf8').replace(/\\\n/g, ' ');
  const deps = text.slice(text.indexOf(':') + 1).trim().split(/(?<!\\)\s+/).filter(Boolean);
  return deps.every((dep) => fs.existsSync(dep) && fs.statSync(dep).mtimeMs <= objTime);
}

// Compiles sources in parallel; returns the object files in input order.
async function compile(toolchain, name, files, flags) {
  const hash = createHash('sha1').update(flags.join(' ')).digest('hex').slice(0, 8);
  const objs = [];
  const tasks = [];
  let done = 0;
  for (const file of files) {
    const rel = displayName(file);
    const obj = path.join(OBJ, name, hash, `${rel.replaceAll(path.sep, '_')}.o`);
    objs.push(obj);
    const depFile = `${obj}.d`;
    if (depsUpToDate(obj, depFile) && fs.statSync(obj).mtimeMs >= fs.statSync(file).mtimeMs) continue;
    tasks.push(async () => {
      fs.mkdirSync(path.dirname(obj), { recursive: true });
      const own = file.startsWith(GLUE) ? [] : UPSTREAM;
      const c = file.endsWith('.c');
      const common = c ? targets.compileFlags.map((f) => (f.startsWith('-std=') ? '-std=gnu17' : f)) : targets.compileFlags;
      await spawnAsync(c ? toolchain.emcc : toolchain.empp, ['-c', file, '-o', obj, '-MD', '-MF', depFile, ...common, ...(c ? ['-w'] : own), ...flags], toolchain.env, rel);
      console.log(`[${name} ${++done}/${tasks.length}] ${rel}`);
    });
  }
  if (tasks.length) console.log(`${name}: compiling ${tasks.length} of ${files.length} files`);
  await runPool(tasks, jobs);
  return objs;
}

async function buildLib(toolchain, name) {
  const lib = libs[name];
  const flags = [...includeFlags([name]), ...(lib.privateInc ?? []).map((i) => `-I${resolve(i)}`)];
  const files = [...sourcesOf(lib), ...generateFiles(lib, name)];
  const objs = await compile(toolchain, name, files, flags);
  const archive = path.join(BUILD, 'lib', `lib${name}.a`);
  const newest = Math.max(...objs.map((o) => fs.statSync(o).mtimeMs));
  if (!fs.existsSync(archive) || fs.statSync(archive).mtimeMs < newest) {
    fs.mkdirSync(path.dirname(archive), { recursive: true });
    fs.rmSync(archive, { force: true });
    await spawnAsync(toolchain.emar, ['rcs', archive, ...objs], toolchain.env, `ar ${name}`);
  }
  return archive;
}

function shell(app) {
  const template = fs.readFileSync(path.join(SHELL, 'shell.html'), 'utf8');
  const file = path.join(GEN, 'shell', `${app.name}.html`);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const args = app.saveDirOption ? [app.saveDirOption, app.settingsDir] : [app.settingsDir];
  fs.writeFileSync(
    file,
    template.replaceAll('@TITLE@', app.title).replaceAll('@ARGUMENTS@', JSON.stringify(args)).replaceAll('@SETTINGS_DIR@', app.settingsDir).replaceAll('@RUNTIME@', '../../../'),
  );
  return file;
}

async function buildApp(toolchain, name) {
  const app = { ...apps[name], name };
  const libNames = closure(app.deps, libs);
  const archives = [];
  for (const lib of libNames) archives.push(await buildLib(toolchain, lib));

  const flags = [...includeFlags(app.deps), ...(app.inc ?? []).map((i) => `-I${resolve(i)}`)];
  const files = [...sourcesOf(app), ...generateFiles(app, name)];
  const objs = await compile(toolchain, name, files, flags);

  const out = path.join(OUT, name);
  fs.rmSync(out, { recursive: true, force: true });
  fs.mkdirSync(out, { recursive: true });
  console.log(`linking ${name}`);
  await spawnAsync(
    toolchain.empp,
    [
      ...objs,
      ...archives.reverse(),
      ...targets.compileFlags,
      ...targets.linkFlags,
      '-sALLOW_MEMORY_GROWTH=1',
      '-sINITIAL_MEMORY=64MB',
      '-sSTACK_SIZE=1MB',
      '-sENVIRONMENT=web',
      '-sFORCE_FILESYSTEM=1',
      '-sEXPORTED_RUNTIME_METHODS=FS,ccall,UTF8ToString',
      '-lidbfs.js',
      '-lwebsocket.js',
      '--pre-js',
      path.join(SHELL, 'persist.js'),
      '--pre-js',
      path.join(SHELL, 'files.js'),
      ...[...targets.prejs, ...(app.prejs ?? [])].flatMap((file) => ['--pre-js', path.join(SHELL, file)]),
      '--shell-file',
      shell(app),
      '-o',
      path.join(out, 'index.html'),
    ],
    toolchain.env,
    `link ${name}`
  );

  for (const lib of libNames) {
    for (const file of libs[lib].files ?? []) fs.cpSync(resolve(file.from), path.join(out, file.to), { recursive: true });
  }
  fs.writeFileSync(path.join(out, STAMP), sourceHash(YEAR));

  const sizes = fs.readdirSync(out).filter((f) => fs.statSync(path.join(out, f)).isFile()).map((f) => `${f} ${(fs.statSync(path.join(out, f)).size / 1e6).toFixed(2)} MB`);
  console.log(`${name}: ${sizes.join(', ')}`);
}

const toolchain = ensureEmsdk();
for (const year of years) {
  await useYear(year);
  const selected = args.length ? args : Object.keys(apps);
  for (const name of selected) if (!apps[name]) throw new Error(`${year}: unknown app ${name}, expected one of ${Object.keys(apps).join(', ')}`);
  console.log(`WPILib ${year}`);
  prepareSource(await ensureAllwpilib(year), year);
  generateVersion();
  for (const name of selected) await buildApp(toolchain, name);
}
