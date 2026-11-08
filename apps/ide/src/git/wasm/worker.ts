// Runs git (the WebAssembly build in packages/git-wasm) on a repository in the origin private file
// system or in a folder opened from disk. git reads files as it needs them: through synchronous
// access handles in the origin private file system, and through File snapshots and FileReaderSync
// on disk. What git changed is written back afterwards.
import { GitHost, type Changes, type LazyFile } from '@frc-dev-wasm/git-wasm/host'
import { authFor } from './authFor'
import { OPFS_STORE, exactBytes } from '../../opfsStore'

export const HOME = '/home/frc'

export interface RunRequest {
  type: 'run'
  args: string[]
  env: Record<string, string>
  // Workspace paths, such as /workspace/MyRobot. git looks for the repository from `dir`, the
  // directory after its -C options, up to `limit`.
  cwd: string
  dir: string
  limit: string
  // A folder opened from disk and the workspace path it appears at. Without it, the origin private
  // file system.
  disk?: { handle: FileSystemDirectoryHandle; base: string }
  input?: Uint8Array
  // The folder of git.mjs and git.wasm
  runtime: string
  // CORS proxy for remotes, and Authorization headers by host
  proxy?: string
  auth: Record<string, string>
  // Whether the output is shown in the terminal, and its width
  tty: boolean
  columns: number
}

export type WorkerMessage =
  | { type: 'out'; data: Uint8Array }
  | { type: 'err'; data: Uint8Array }
  | { type: 'done'; status: number; changed: string[] }
  | { type: 'error'; message: string; stack?: string }
  | { type: 'released' }

let host: Promise<GitHost> | undefined
const loadHost = (runtime: string) =>
  (host ??= (async () => {
    const [{ default: createGit }, wasmModule] = await Promise.all([
      import(/* @vite-ignore */ new URL('git.mjs', runtime).href),
      WebAssembly.compileStreaming(fetch(new URL('git.wasm', runtime)))
    ])
    return new GitHost(createGit, wasmModule)
  })().catch((e: unknown) => {
    host = undefined
    throw e
  }))

const split = (path: string) => path.split('/').filter(Boolean)

// Where files come from: the folder at workspace path `base`
interface Source {
  root: FileSystemDirectoryHandle
  base: string
  disk: boolean
}

const opfsStore = async () => (await navigator.storage.getDirectory()).getDirectoryHandle(OPFS_STORE)

async function sourceOf(request: RunRequest): Promise<Source> {
  return request.disk ? { root: request.disk.handle, base: request.disk.base, disk: true } : { root: await opfsStore(), base: '', disk: false }
}

async function directory(source: Source, path: string, create = false): Promise<FileSystemDirectoryHandle> {
  let dir = source.root
  for (const part of split(path.slice(source.base.length))) dir = await dir.getDirectoryHandle(part, { create })
  return dir
}

// The repository that holds `dir`, or `dir` itself outside one
async function findRoot(source: Source, dir: string, limit: string): Promise<{ root: string; inRepository: boolean }> {
  for (let path = dir; path === limit || path.startsWith(`${limit}/`); path = path.slice(0, path.lastIndexOf('/'))) {
    const folder = await directory(source, path).catch(() => undefined)
    const git = await folder?.getDirectoryHandle('.git').catch(() => folder.getFileHandle('.git').catch(() => undefined))
    if (git) return { root: path, inRepository: true }
  }
  return { root: dir, inRepository: false }
}

// A tree mounted for git: `memfs` is where git sees it, `source` and `path` where it comes from.
// Files are keyed by their path in the tree, with the handle git reads them through.
interface Mount {
  memfs: string
  source: Source
  path: string
  dirs: Set<string>
  files: Map<string, { file: LazyFile; sync?: FileSystemSyncAccessHandle }>
}

async function walk(dir: FileSystemDirectoryHandle, prefix: string, dirs: Set<string>, found: Array<[string, FileSystemFileHandle]>, depth = Infinity): Promise<void> {
  for await (const [name, handle] of dir.entries()) {
    const path = prefix ? `${prefix}/${name}` : name
    if (handle instanceof FileSystemFileHandle) found.push([path, handle])
    else {
      dirs.add(path)
      if (depth > 1) await walk(handle as FileSystemDirectoryHandle, path, dirs, found, depth - 1)
    }
  }
}

const reader = new FileReaderSync()

async function openFile(path: string, handle: FileSystemFileHandle, disk: boolean) {
  const file = await handle.getFile()
  // Folders from disk have no synchronous access handles, but a File can be read synchronously
  if (disk) {
    const read = (buffer: Uint8Array, position: number) => {
      const bytes = new Uint8Array(reader.readAsArrayBuffer(file.slice(position, position + buffer.length)))
      buffer.set(bytes)
      return bytes.length
    }
    return { file: { path, size: file.size, mtime: file.lastModified, read } }
  }
  const sync = await handle.createSyncAccessHandle()
  return { file: { path, size: file.size, mtime: file.lastModified, read: (buffer: Uint8Array, position: number) => sync.read(buffer, { at: position }) }, sync }
}

function close(mount: Mount, under?: string): void {
  for (const [path, entry] of mount.files) {
    if (under !== undefined && path !== under && !path.startsWith(`${under}/`)) continue
    entry.sync?.close()
    mount.files.delete(path)
  }
}

async function open(mount: Omit<Mount, 'files'>, found: Array<[string, FileSystemFileHandle]>): Promise<Mount> {
  const opened: Mount = { ...mount, files: new Map() }
  try {
    for (const entry of await Promise.all(found.map(([path, handle]) => openFile(path, handle, mount.source.disk)))) opened.files.set(entry.file.path, entry)
    return opened
  } catch (e) {
    close(opened)
    throw e
  }
}

async function repositoryMount(source: Source, root: string, inRepository: boolean): Promise<Mount> {
  const mount = { memfs: root, source, path: root, dirs: new Set<string>() }
  const found: Array<[string, FileSystemFileHandle]> = []
  if (inRepository) {
    await walk(await directory(source, root), '', mount.dirs, found)
    return open(mount, found)
  }
  // Outside a repository git only needs to see what exists, as clone and init check their target
  await walk(await directory(source, root), '', mount.dirs, found, 2)
  return { ...mount, files: new Map(found.map(([path]) => [path, { file: { path, size: 0, mtime: 0, read: () => 0 } }])) }
}

// The home folder holds only the global configuration, kept at the top of the store (~ in the workbench)
async function homeMount(): Promise<Mount> {
  const source: Source = { root: await opfsStore(), base: '', disk: false }
  const config = await source.root.getFileHandle('.gitconfig').catch(() => undefined)
  return open({ memfs: HOME, source, path: '', dirs: new Set() }, config ? [['.gitconfig', config]] : [])
}

// The mounts of the last repository in the origin private file system stay open for its next
// command, until the page writes a file. Folders from disk can change outside the browser.
let session: { key: string; mounts: Mount[] } | undefined

function closeSession(): void {
  for (const mount of session?.mounts ?? []) close(mount)
  session = undefined
}

async function mountsFor(request: RunRequest): Promise<Mount[]> {
  const source = await sourceOf(request)
  const { root, inRepository } = await findRoot(source, request.dir, request.limit)
  const key = `${root}\n${inRepository}`
  if (!source.disk && session?.key === key) return session.mounts
  closeSession()
  const mounts: Mount[] = []
  try {
    mounts.push(await repositoryMount(source, root, inRepository), await homeMount())
  } catch (e) {
    for (const mount of mounts) close(mount)
    throw e
  }
  if (inRepository && !source.disk) session = { key, mounts }
  return mounts
}

// Writes what git changed, and updates the mount to match when it stays open
async function writeBack(mount: Mount, changes: Changes, keep: boolean): Promise<string[]> {
  const copies = new Map<string, Uint8Array>()
  for (const { from } of changes.writes) {
    const entry = from === undefined ? undefined : mount.files.get(from)
    if (!entry || copies.has(from!)) continue
    const data = new Uint8Array(entry.file.size)
    entry.file.read(data, 0)
    copies.set(from!, data)
  }
  if (!keep) close(mount)
  const full = (path: string) => `${mount.path}/${path}`
  const parentOf = (path: string) => directory(mount.source, full(path).slice(0, full(path).lastIndexOf('/')), true)
  const nameOf = (path: string) => split(path).pop()!
  for (const path of changes.removed) {
    close(mount, path)
    for (const dir of mount.dirs) if (dir === path || dir.startsWith(`${path}/`)) mount.dirs.delete(dir)
    await (await parentOf(path)).removeEntry(nameOf(path), { recursive: true }).catch(() => {})
  }
  for (const path of changes.dirs) {
    await directory(mount.source, full(path), true)
    mount.dirs.add(path)
  }
  for (const write of changes.writes) {
    const data = write.data ?? copies.get(write.from!)!
    close(mount, write.path)
    // Not a sync access handle: writing through one leaves the modification time as it was
    const handle = await (await parentOf(write.path)).getFileHandle(nameOf(write.path), { create: true })
    const writable = await handle.createWritable()
    await writable.write(exactBytes(data))
    await writable.close()
    if (keep) mount.files.set(write.path, await openFile(write.path, handle, mount.source.disk))
  }
  return [...changes.removed, ...changes.dirs, ...changes.writes.map((w) => w.path)].map(full)
}

// Output is passed on as it comes, so progress shows while git runs
function stream(type: 'out' | 'err') {
  let bytes: number[] = []
  const flush = () => {
    if (bytes.length) postMessage({ type, data: new Uint8Array(bytes) } satisfies WorkerMessage)
    bytes = []
  }
  const push = (byte: number) => {
    bytes.push(byte)
    if (byte === 10 || byte === 13 || bytes.length >= 4096) flush()
  }
  return { push, flush }
}

async function run(request: RunRequest): Promise<void> {
  const git = await loadHost(request.runtime)
  const mounts = await mountsFor(request)
  const kept = session?.mounts === mounts
  const out = stream('out')
  const err = stream('err')
  let result: ReturnType<GitHost['run']>
  try {
    result = git.run({
      args: request.args,
      input: request.input,
      env: { ...request.env, HOME, ...(request.tty ? { TERM: 'xterm-256color' } : {}) },
      tty: request.tty,
      columns: request.columns,
      cwd: request.cwd,
      mounts: mounts.map((m) => ({ root: m.memfs, dirs: [...m.dirs], files: [...m.files.values()].map((e) => e.file) })),
      stdout: out.push,
      stderr: err.push,
      moduleOptions: { frcCorsProxy: request.proxy, frcAuth: (url) => authFor(request.auth, url) }
    })
  } catch (e) {
    if (kept) closeSession()
    else for (const mount of mounts) close(mount)
    throw e
  } finally {
    out.flush()
    err.flush()
  }
  const changed: string[] = []
  try {
    for (const [i, mount] of mounts.entries()) changed.push(...(await writeBack(mount, result.changes[i], kept)))
  } catch (e) {
    closeSession()
    throw e
  }
  postMessage({ type: 'done', status: result.status, changed } satisfies WorkerMessage)
}

self.onmessage = (event: MessageEvent<RunRequest | { type: 'release' }>) => {
  const m = event.data
  if (m.type === 'release') {
    closeSession()
    postMessage({ type: 'released' } satisfies WorkerMessage)
    return
  }
  run(m).catch((e: unknown) => postMessage({ type: 'error', message: e instanceof Error ? e.message : String(e), stack: e instanceof Error ? e.stack : undefined } satisfies WorkerMessage))
}
