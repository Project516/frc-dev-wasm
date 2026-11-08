import type { Change, Entry } from './wasi'
import type { Files } from './files'
import { dirname, join, relativizeArgs, resolvePath } from './paths'
import type { WorkerMessage, WorkerRequest } from './coreutils.worker'

const WASM_URL = new URL(`${import.meta.env.BASE_URL}runtime/terminal/coreutils.wasm`, window.location.href)

// Commands that never read file contents, so files are loaded as name and size only
const METADATA_ONLY = new Set(['ls', 'rm', 'rmdir', 'mkdir', 'touch', 'unlink'])
const RECURSIVE_FLAGS: Record<string, string> = { ls: 'R', rm: 'rR', cp: 'rRa' }
const MAX_FILES = 20_000
const MAX_BYTES = 64 * 1024 * 1024
const CONCURRENCY = 16

export interface RunRequest {
  command: string
  args: string[]
  env: Record<string, string>
  cwd: string
  stdin?: Uint8Array
  stdoutIsTty: boolean
  maxOutput?: number
  onOutput(fd: 1 | 2, data: Uint8Array): void
  onStatus(text: string): void
  signal: AbortSignal
}

interface Root {
  path: string
  // self: the path alone. children: a directory and what it lists. tree: everything below it.
  mode: 'self' | 'children' | 'tree'
}

const isRecursive = (command: string, args: string[]) => {
  const letters = RECURSIVE_FLAGS[command]
  if (!letters) return false
  return args.some((a) => (a.startsWith('--') ? a === '--recursive' : /^-[a-zA-Z]+$/.test(a) && [...letters].some((l) => a.includes(l))))
}

async function nearestDirectory(files: Files, path: string): Promise<string> {
  for (let p = dirname(path); ; p = dirname(p)) {
    if ((await files.stat(p))?.dir || p === '/') return p
  }
}

// The paths a command can touch: its operands, where an operand that does not exist is a file to
// create, and the working directory.
async function planRoots(files: Files, command: string, args: string[], cwd: string): Promise<Root[]> {
  const operands = args.flatMap((a) => (a.startsWith('--') ? a.split('=').slice(1) : a.startsWith('-') ? [] : [a])).filter(Boolean)
  const recursive = isRecursive(command, args)
  const roots: Root[] = [{ path: cwd, mode: 'self' }]
  let existing = 0
  for (const operand of operands) {
    const path = resolvePath(cwd, operand)
    const stat = await files.stat(path)
    if (!stat) roots.push({ path: await nearestDirectory(files, path), mode: 'self' })
    else {
      existing++
      roots.push({ path, mode: stat.dir ? (recursive ? 'tree' : 'children') : 'self' })
    }
  }
  if (command === 'ls' && existing === 0) roots.push({ path: cwd, mode: recursive ? 'tree' : 'children' })
  return roots
}

async function inBatches<T>(items: T[], task: (item: T) => Promise<void>): Promise<void> {
  const queue = [...items]
  await Promise.all(
    Array.from({ length: Math.min(CONCURRENCY, queue.length) }, async () => {
      for (let item = queue.shift(); item !== undefined; item = queue.shift()) await task(item)
    })
  )
}

async function collect(files: Files, roots: Root[], withContent: boolean): Promise<Entry[]> {
  const entries = new Map<string, Entry>()
  let bytes = 0

  const addDir = (path: string, mtime = Date.now()) => {
    if (!entries.has(path)) entries.set(path, { path, dir: true, size: 0, mtime })
  }
  const addFile = async (path: string, size: number, mtime: number, content: boolean) => {
    if (entries.size >= MAX_FILES) throw new Error(`too many files to load (over ${MAX_FILES}); use a narrower path`)
    const data = content ? await files.read(path) : undefined
    bytes += data?.length ?? 0
    if (bytes > MAX_BYTES) throw new Error(`more than ${MAX_BYTES / 1024 / 1024} MiB of files to load; use a narrower path`)
    entries.set(path, { path, dir: false, size: data?.length ?? size, mtime, data })
  }
  const children = async (path: string) => {
    const listed = await files.list(path)
    const found: Array<{ path: string; dir: boolean; size: number; mtime: number }> = []
    await inBatches(listed, async ([name, dir]) => {
      const stat = await files.stat(join(path, name))
      if (stat) found.push({ path: join(path, name), dir, size: stat.size, mtime: stat.mtime })
    })
    return found
  }
  const walk = async (path: string) => {
    addDir(path)
    const found = await children(path)
    await inBatches(found.filter((c) => !c.dir), (c) => addFile(c.path, c.size, c.mtime, withContent))
    for (const c of found.filter((f) => f.dir)) await walk(c.path)
  }

  for (const root of roots) {
    for (let p = dirname(root.path); ; p = dirname(p)) {
      addDir(p)
      if (p === '/') break
    }
    const stat = await files.stat(root.path)
    if (!stat) continue
    if (!stat.dir) {
      await addFile(root.path, stat.size, stat.mtime, withContent)
    } else if (root.mode === 'tree') {
      await walk(root.path)
    } else {
      addDir(root.path, stat.mtime)
      if (root.mode === 'children') {
        for (const c of await children(root.path)) {
          if (c.dir) addDir(c.path, c.mtime)
          else if (!entries.has(c.path)) await addFile(c.path, c.size, c.mtime, false)
        }
      }
    }
  }
  return [...entries.values()]
}

async function apply(files: Files, changes: Change[]): Promise<void> {
  for (const change of changes) {
    if (change.op === 'delete') await files.remove(change.path, true)
    else if (change.op === 'mkdir') await files.mkdir(change.path)
    else await files.write(change.path, change.data)
  }
}

// The bytes of a view, as a buffer that can be handed over without a copy
function ownBuffer(data: Uint8Array): ArrayBuffer | undefined {
  return data.byteOffset === 0 && data.byteLength === data.buffer.byteLength && data.buffer instanceof ArrayBuffer ? data.buffer : undefined
}

function transferable(message: WorkerRequest): Transferable[] {
  const buffers = new Set<ArrayBuffer>()
  const add = (data?: Uint8Array) => {
    const own = data && ownBuffer(data)
    if (own) buffers.add(own)
  }
  add(message.stdin)
  for (const entry of message.entries) add(entry.data)
  return [...buffers]
}

// One download and compile for every terminal
let compiled: Promise<WebAssembly.Module> | undefined

async function download(onStatus: (text: string) => void): Promise<WebAssembly.Module> {
  const response = await fetch(WASM_URL)
  const type = response.headers.get('content-type') ?? ''
  if (!response.ok || type.includes('html')) throw new Error('coreutils is not built. Run pnpm build:terminal and reload.')
  const total = response.headers.has('content-encoding') ? 0 : Number(response.headers.get('content-length')) || 0
  const chunks: Uint8Array[] = []
  let loaded = 0
  const reader = response.body!.getReader()
  for (let chunk = await reader.read(); !chunk.done; chunk = await reader.read()) {
    chunks.push(chunk.value)
    loaded += chunk.value.length
    const mb = (n: number) => (n / 1024 / 1024).toFixed(1)
    onStatus(`Loading coreutils ${mb(loaded)}${total ? ` of ${mb(total)}` : ''} MB`)
  }
  const wasm = new Uint8Array(loaded)
  let at = 0
  for (const chunk of chunks) {
    wasm.set(chunk, at)
    at += chunk.length
  }
  const module = await WebAssembly.compile(wasm)
  onStatus('')
  return module
}

function load(onStatus: (text: string) => void): Promise<WebAssembly.Module> {
  compiled ??= download(onStatus).catch((error) => {
    compiled = undefined
    throw error
  })
  return compiled
}

// Runs one uutils coreutils command in a worker. The files it needs are copied in first and its
// changes are written back afterwards, so the command itself never waits on storage.
export class Coreutils {
  private worker: Worker | undefined

  constructor(private readonly files: Files) {}

  async run(request: RunRequest): Promise<number> {
    const { command, signal } = request
    const module = await load(request.onStatus)
    signal.throwIfAborted()
    const roots = await planRoots(this.files, command, request.args, request.cwd)
    const entries = await collect(this.files, roots, !METADATA_ONLY.has(command))
    signal.throwIfAborted()

    const worker = (this.worker ??= new Worker(new URL('./coreutils.worker.ts', import.meta.url), { type: 'module' }))
    const message: WorkerRequest = {
      module,
      args: [command, ...relativizeArgs(command, request.args, request.cwd)],
      env: Object.entries(request.env).map(([k, v]) => `${k}=${v}`),
      cwd: request.cwd,
      stdin: request.stdin,
      stdoutIsTty: request.stdoutIsTty,
      maxOutput: request.maxOutput,
      entries
    }
    const result = await new Promise<Extract<WorkerMessage, { type: 'done' }>>((resolve, reject) => {
      const stop = () => {
        worker.terminate()
        this.worker = undefined
        reject(signal.reason)
      }
      signal.addEventListener('abort', stop, { once: true })
      worker.onmessage = (event: MessageEvent<WorkerMessage>) => {
        const m = event.data
        if (m.type === 'output') request.onOutput(m.fd, m.data)
        else {
          signal.removeEventListener('abort', stop)
          if (m.type === 'done') resolve(m)
          else reject(new Error(m.message))
        }
      }
      worker.onerror = (event) => {
        signal.removeEventListener('abort', stop)
        worker.terminate()
        this.worker = undefined
        reject(new Error(event.message))
      }
      worker.postMessage(message, transferable(message))
    })
    await apply(this.files, result.changes)
    return result.code
  }

  dispose(): void {
    this.worker?.terminate()
    this.worker = undefined
  }
}
