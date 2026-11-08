// Loads the terminal shell under Node over an in-memory workspace. Source files import without
// extensions, so they go through Vite's module loader.
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const wasm = join(root, 'public/runtime/terminal/coreutils.wasm')

const vite = await createServer({ root, configFile: false, logLevel: 'error', appType: 'custom', server: { middlewareMode: true }, optimizeDeps: { noDiscovery: true, include: [] } })
const load = (path) => vite.ssrLoadModule(path)
export const close = () => vite.close()

const { Shell } = await load('/src/frc/terminal/shell.ts')
const { TtySink } = await load('/src/frc/terminal/io.ts')
const { run } = await load('/src/frc/terminal/wasi.ts')
const { relativizeArgs } = await load('/src/frc/terminal/paths.ts')

const dirnameOf = (p) => p.slice(0, p.lastIndexOf('/')) || '/'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// Same surface as Files, in memory. `latency` is the delay of each call in milliseconds.
export class MemFiles {
  constructor(tree = {}, latency = 0) {
    this.nodes = new Map([['/', { dir: true }]])
    this.latency = latency
    this.calls = 0
    for (const [path, content] of Object.entries(tree)) this.put(path, content)
  }
  put(path, content) {
    for (let p = dirnameOf(path); p !== '/'; p = dirnameOf(p)) if (!this.nodes.has(p)) this.nodes.set(p, { dir: true })
    this.nodes.set(path, content === null ? { dir: true } : { dir: false, data: typeof content === 'string' ? new TextEncoder().encode(content) : content })
  }
  paths(under = '/') {
    return [...this.nodes.keys()].filter((p) => p !== '/' && (under === '/' || p === under || p.startsWith(`${under}/`))).sort()
  }
  async tick() {
    this.calls++
    if (this.latency) await sleep(this.latency)
  }
  async stat(path) {
    await this.tick()
    const n = this.nodes.get(path)
    return n ? { dir: n.dir, size: n.data?.length ?? 0, mtime: 0 } : undefined
  }
  async list(path) {
    await this.tick()
    const n = this.nodes.get(path)
    if (!n) throw Object.assign(new Error('not found'), { code: 'FileNotFound' })
    return this.paths(path).filter((p) => p !== path && dirnameOf(p) === path).map((p) => [p.slice(p.lastIndexOf('/') + 1), this.nodes.get(p).dir])
  }
  async read(path) {
    await this.tick()
    const n = this.nodes.get(path)
    if (!n) throw Object.assign(new Error('not found'), { code: 'FileNotFound' })
    if (n.dir) throw Object.assign(new Error('dir'), { code: 'FileIsADirectory' })
    return n.data
  }
  async write(path, data) {
    await this.tick()
    this.put(path, data)
  }
  async mkdir(path) {
    await this.tick()
    this.put(path, null)
  }
  async copy(from, to, overwrite) {
    await this.tick()
    if (this.nodes.has(to) && !overwrite) throw new Error('exists')
    for (const p of this.paths(from)) this.nodes.set(to + p.slice(from.length), this.nodes.get(p))
  }
  async rename(from, to, overwrite) {
    await this.tick()
    if (this.nodes.has(to) && !overwrite) throw new Error('exists')
    for (const p of this.paths(from)) {
      this.nodes.set(to + p.slice(from.length), this.nodes.get(p))
      this.nodes.delete(p)
    }
  }
  async remove(path) {
    await this.tick()
    for (const p of this.paths(path)) this.nodes.delete(p)
  }
}

let module
// What the browser Coreutils does, with the whole tree as the snapshot
class NodeCoreutils {
  constructor(files) {
    this.files = files
  }
  async run(req) {
    module ??= new WebAssembly.Module(readFileSync(wasm))
    const entries = [...this.files.nodes].map(([path, n]) => ({ path, dir: n.dir, size: n.data?.length ?? 0, mtime: 0, data: n.data }))
    const out = run({
      module,
      args: [req.command, ...relativizeArgs(req.command, req.args, req.cwd)],
      env: Object.entries(req.env).map(([k, v]) => `${k}=${v}`),
      cwd: req.cwd,
      stdin: req.stdin,
      stdoutIsTty: req.stdoutIsTty,
      entries,
      onOutput: req.onOutput
    })
    for (const c of out.changes) {
      if (c.op === 'delete') await this.files.remove(c.path, true)
      else if (c.op === 'mkdir') await this.files.mkdir(c.path)
      else await this.files.write(c.path, c.data)
    }
    return out.code
  }
}

export function createHarness({ tree = {}, latency = 0, cwd = '/workspace', gradle, api = {} } = {}) {
  const files = new MemFiles({ '/workspace': null, ...tree }, latency)
  const output = []
  const exits = []
  const git = async (args, io) => (io.out(`git ${args.join(' ')} @${args.length}\n`), 0)
  const services = gradle ?? { build: async ({ write }) => (write('> Task :compileJava\n'), true) }
  const host = {
    api,
    files,
    coreutils: new NodeCoreutils(files),
    services,
    git,
    githubToken: async () => undefined,
    githubLogin: async () => false,
    githubLogout: async () => false,
    home: '/workspace',
    history: [],
    columns: () => 80,
    exit: (code) => exits.push(code)
  }
  const tty = new TtySink((text) => output.push(text))
  const shell = new Shell(host, tty, () => {})
  if (cwd !== '/workspace') {
    shell.cwd = cwd
    shell.vars.set('PWD', cwd)
  }
  const signal = new AbortController()
  return {
    shell,
    files,
    output,
    exits,
    // Runs a line and returns what it printed, with the terminal's \r\n turned back into \n
    async sh(line, controller = signal) {
      output.length = 0
      const status = await shell.runLine(line, controller.signal)
      return { out: output.join('').replace(/\r\n/g, '\n'), status }
    }
  }
}
