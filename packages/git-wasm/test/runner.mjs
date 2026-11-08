// Runs the WebAssembly git build under Node on a directory on disk, the way the IDE's worker does on
// the origin private file system: files mounted lazily, then the changes written back.
import fs from 'node:fs'
import path from 'node:path'
import { GitHost } from '../src/host.mjs'

// The build targets browsers and workers. Node passes for a worker: the compiled module is given.
globalThis.WorkerGlobalScope ??= class {}
globalThis.self ??= globalThis
const OUT = path.join(import.meta.dirname, '..', '..', '..', 'apps', 'ide', 'public', 'runtime', 'git')
const { default: createGit } = await import(path.join(OUT, 'git.mjs'))
const host = new GitHost(createGit, new WebAssembly.Module(fs.readFileSync(path.join(OUT, 'git.wasm'))))

// Runs git with `dir` mounted at /repo, in /repo/<cwd>
export function runGit(dir, args, { env = {}, cwd = '', moduleOptions } = {}) {
  const dirs = []
  const files = []
  const fds = []
  const walk = (at, prefix) => {
    for (const entry of fs.readdirSync(at, { withFileTypes: true })) {
      const rel = prefix ? `${prefix}/${entry.name}` : entry.name
      const full = path.join(at, entry.name)
      if (entry.isDirectory()) {
        dirs.push(rel)
        walk(full, rel)
      } else {
        const stat = fs.statSync(full)
        const fd = fs.openSync(full, 'r')
        fds.push(fd)
        files.push({ path: rel, size: stat.size, mtime: stat.mtimeMs, read: (buffer, position) => fs.readSync(fd, buffer, 0, buffer.length, position) })
      }
    }
  }
  walk(dir, '')
  const out = []
  const err = []
  const root = '/repo'
  const r = host.run({
    args,
    env,
    cwd: cwd ? `${root}/${cwd}` : root,
    mounts: [{ root, dirs, files }],
    stdout: (b) => out.push(b),
    stderr: (b) => err.push(b),
    moduleOptions
  })
  const [{ removed, dirs: created, writes }] = r.changes
  const copies = new Map(writes.filter((w) => w.from).map((w) => [w.from, fs.readFileSync(path.join(dir, w.from))]))
  for (const fd of fds) fs.closeSync(fd)
  for (const p of removed) fs.rmSync(path.join(dir, p), { recursive: true })
  for (const p of created) fs.mkdirSync(path.join(dir, p), { recursive: true })
  for (const w of writes) fs.writeFileSync(path.join(dir, w.path), w.data ?? copies.get(w.from))
  return { status: r.status, stdout: Buffer.from(out), stderr: Buffer.from(err) }
}
