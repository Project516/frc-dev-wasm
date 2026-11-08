// Builds git to WebAssembly into apps/ide/public/runtime/git/. Usage: node build.mjs [--jobs N],
// or GIT_JOBS=N.
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { CACHE, ROOT, ensureEmsdk } from '../wpigui-tools/toolchain.mjs'

const GIT_VERSION = '2.56.0'
const GIT_SHA256 = '26c56c296b38c0695b26fa95f475f1d01704d2d38e73465ca30b0b2f5dc789d3'
const HERE = import.meta.dirname
const WORK = path.join(CACHE, 'git-wasm')
const SRC = path.join(WORK, 'src')
const OUT = path.join(ROOT, 'apps', 'ide', 'public', 'runtime', 'git')

const jobsFlag = process.argv.indexOf('--jobs')
const jobs = jobsFlag > 0 ? process.argv[jobsFlag + 1] : (process.env.GIT_JOBS ?? '2')

const run = (cmd, args, options = {}) => execFileSync(cmd, args, { stdio: 'inherit', ...options })
const sha256 = (data) => createHash('sha256').update(data).digest('hex')

async function sources() {
  const archive = path.join(CACHE, 'downloads', `git-${GIT_VERSION}.tar.xz`)
  if (!fs.existsSync(archive)) {
    const url = `https://mirrors.edge.kernel.org/pub/software/scm/git/git-${GIT_VERSION}.tar.xz`
    const res = await fetch(url)
    if (!res.ok) throw new Error(`GET ${url}: ${res.status}`)
    const data = Buffer.from(await res.arrayBuffer())
    if (sha256(data) !== GIT_SHA256) throw new Error(`${url} does not match its checksum`)
    fs.mkdirSync(path.dirname(archive), { recursive: true })
    fs.writeFileSync(archive, data)
  }
  // The sources are unpacked again when the patch changes
  const patch = path.join(HERE, 'patches', 'git-wasm.patch')
  const stamp = path.join(WORK, 'patch.sha256')
  const patchHash = sha256(fs.readFileSync(patch))
  if (fs.existsSync(stamp) && fs.readFileSync(stamp, 'utf8') === patchHash) return
  fs.rmSync(SRC, { recursive: true, force: true })
  fs.mkdirSync(SRC, { recursive: true })
  run('tar', ['-xf', archive, '-C', SRC, '--strip-components=1'])
  run('git', ['apply', '-p1', patch], { cwd: SRC })
  fs.writeFileSync(stamp, patchHash)
}

await sources()
const { env, emcc } = ensureEmsdk()
env.PATH = `${path.dirname(emcc)}${path.delimiter}${env.PATH}`
fs.copyFileSync(path.join(HERE, 'config.mak'), path.join(SRC, 'config.mak'))
const ldflags = [
  '-sUSE_ZLIB=1',
  '-sALLOW_MEMORY_GROWTH=1',
  '-sEXIT_RUNTIME=1',
  '-sSTACK_SIZE=1048576',
  '-sENVIRONMENT=web,worker',
  '-sMODULARIZE=1',
  '-sEXPORT_ES6=1',
  '-sEXPORT_NAME=createGit',
  '-sINVOKE_RUN=0',
  '-sFORCE_FILESYSTEM=1',
  '-sEXPORTED_RUNTIME_METHODS=FS,MEMFS,callMain,ENV',
  // A child command runs in a new instance while its parent waits, so instances start synchronously
  '-sWASM_ASYNC_COMPILATION=0',
  // git.wasm has no function names: git.symbols names the wasm-function[N] frames of a stack
  '--emit-symbol-map',
  `--pre-js ${path.join(HERE, 'src', 'pre.js')}`,
  `--js-library ${path.join(HERE, 'src', 'library.js')}`
].join(' ')
// make does not know the pre-js and library files, so the link always runs
fs.rmSync(path.join(SRC, 'git.mjs'), { force: true })
run('make', [`-j${jobs}`, `LDFLAGS=${ldflags}`, 'git.mjs'], { cwd: SRC, env })

// Lets an instance reuse a compiled Module.wasmModule instead of compiling the bytes again, and keeps
// the error when instantiating fails: createGit rejects only after the host has gone on
const glue = fs.readFileSync(path.join(SRC, 'git.mjs'), 'utf8')
const from = 'function instantiateSync(file,info){var binary=getBinarySync(file);var module=new WebAssembly.Module(binary);var instance=new WebAssembly.Instance(module,info);'
const to = 'function instantiateSync(file,info){var module=Module["wasmModule"]||new WebAssembly.Module(getBinarySync(file));try{var instance=new WebAssembly.Instance(module,info)}catch(e){Module["frcStartError"]=e;throw e}'
if (!glue.includes(from)) throw new Error('instantiateSync not found in the Emscripten glue')
fs.mkdirSync(OUT, { recursive: true })
fs.writeFileSync(path.join(OUT, 'git.mjs'), glue.replace(from, to))
fs.copyFileSync(path.join(SRC, 'git.wasm'), path.join(OUT, 'git.wasm'))
fs.copyFileSync(path.join(SRC, 'git.mjs.symbols'), path.join(OUT, 'git.symbols'))
console.log(`wrote ${OUT}`)
