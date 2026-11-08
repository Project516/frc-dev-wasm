// A minimal WASI preview 1 host over an in-memory file tree. It has no DOM or VS Code
// dependency, so it runs in a worker and under Node. The caller loads the files a command
// needs into `entries` first and writes `changes` back afterwards, which keeps every
// system call synchronous.

export interface Entry {
  path: string
  dir: boolean
  size: number
  mtime: number
  // Unset for a stub: only the name and size are known (metadata-only commands such as ls and rm)
  data?: Uint8Array
}

export type Change =
  | { op: 'write'; path: string; data: Uint8Array }
  | { op: 'mkdir'; path: string }
  | { op: 'delete'; path: string }

export interface RunOptions {
  module: WebAssembly.Module
  args: string[]
  env: string[]
  cwd: string
  stdin?: Uint8Array
  stdoutIsTty: boolean
  // Cuts the command off with exit status 141 after this much output
  maxOutput?: number
  entries: Entry[]
  onOutput(fd: 1 | 2, data: Uint8Array): void
}

export interface RunResult {
  code: number
  changes: Change[]
}

const E = {
  SUCCESS: 0,
  BADF: 8,
  EXIST: 20,
  INVAL: 28,
  IO: 29,
  ISDIR: 31,
  NOENT: 44,
  NOSYS: 52,
  NOTDIR: 54,
  NOTEMPTY: 55,
  NOTSUP: 58,
  SPIPE: 70
} as const

const TYPE = { UNKNOWN: 0, CHAR: 2, DIR: 3, REG: 4 } as const
const RIGHT_READ = 1n << 1n
const RIGHT_WRITE = 1n << 6n
const RIGHT_SEEK_TELL = (1n << 2n) | (1n << 5n)
const ALL_RIGHTS = 0xffffffffffffffffn
const OFLAG = { CREAT: 1, DIRECTORY: 2, EXCL: 4, TRUNC: 8 } as const
const FDFLAG_APPEND = 1
const MAX_OUTPUT = 16 * 1024 * 1024
const FLUSH_AT = 16 * 1024

class ProcExit extends Error {
  readonly code: number
  constructor(code: number) {
    super(`exit ${code}`)
    this.code = code
  }
}

let nextIno = 1

class Node {
  readonly ino = nextIno++
  parent: Dir | undefined
  // Set for nodes loaded from the snapshot, to tell them apart from nodes the command created
  origin: string | undefined
  mtime = Date.now()
}

class File extends Node {
  size = 0
  data: Uint8Array | undefined = new Uint8Array(0)
  dirty = false

  ensure(): Uint8Array {
    if (!this.data) throw new WasiError(E.IO)
    return this.data
  }

  read(offset: number, length: number): Uint8Array {
    return this.ensure().subarray(offset, Math.min(this.size, offset + length))
  }

  write(offset: number, bytes: Uint8Array): void {
    this.resize(Math.max(this.size, offset + bytes.length))
    this.data!.set(bytes, offset)
    this.touch()
  }

  resize(size: number): void {
    if (!this.data) {
      if (size !== 0) throw new WasiError(E.NOTSUP)
      this.data = new Uint8Array(0)
    }
    if (size > this.data.length) {
      const grown = new Uint8Array(Math.max(size, this.data.length * 2))
      grown.set(this.data.subarray(0, this.size))
      this.data = grown
    } else if (size < this.size) {
      this.data.fill(0, size, this.size)
    }
    this.size = size
    this.touch()
  }

  touch(): void {
    this.dirty = true
    this.mtime = Date.now()
  }
}

class Dir extends Node {
  readonly children = new Map<string, File | Dir>()
}

class WasiError extends Error {
  readonly errno: number
  constructor(errno: number) {
    super(`errno ${errno}`)
    this.errno = errno
  }
}

interface Handle {
  node: File | Dir | undefined
  offset: number
  append: boolean
  readable: boolean
  writable: boolean
  preopen?: string
}

const isDir = (n: Node | undefined): n is Dir => n instanceof Dir

function split(path: string): string[] {
  return path.split('/').filter((p) => p && p !== '.')
}

class Tree {
  readonly root = new Dir()
  // Nodes as loaded, for finding what the command removed
  readonly loaded = new Map<string, File | Dir>()

  constructor(entries: Entry[]) {
    for (const entry of [...entries].sort((a, b) => a.path.length - b.path.length)) {
      const parts = split(entry.path)
      if (parts.length === 0) {
        this.root.origin = '/'
        continue
      }
      const parent = this.mkdirp(parts.slice(0, -1))
      const name = parts[parts.length - 1]
      let node = parent.children.get(name)
      if (!node || isDir(node) !== entry.dir) {
        node = entry.dir ? new Dir() : new File()
        node.parent = parent
        parent.children.set(name, node)
      }
      node.mtime = entry.mtime
      node.origin = entry.path
      if (node instanceof File) {
        node.size = entry.size
        node.data = entry.data
      }
      this.loaded.set(entry.path, node)
    }
  }

  private mkdirp(parts: string[]): Dir {
    let dir = this.root
    for (const name of parts) {
      let next = dir.children.get(name)
      if (!isDir(next)) {
        next = new Dir()
        next.parent = dir
        dir.children.set(name, next)
      }
      dir = next
    }
    return dir
  }

  lookup(from: Dir, path: string): File | Dir | undefined {
    let node: File | Dir = path.startsWith('/') ? this.root : from
    for (const name of split(path)) {
      if (!isDir(node)) return undefined
      if (name === '..') node = node.parent ?? this.root
      else {
        const next = node.children.get(name)
        if (!next) return undefined
        node = next
      }
    }
    return node
  }

  // The directory that holds the last component, and that name. The parent has to exist.
  parentOf(from: Dir, path: string): { dir: Dir; name: string } {
    const parts = split(path)
    const name = parts.pop()
    if (!name || name === '..') throw new WasiError(E.INVAL)
    const dir = this.lookup(from, (path.startsWith('/') ? '/' : '') + parts.join('/'))
    if (!dir) throw new WasiError(E.NOENT)
    if (!isDir(dir)) throw new WasiError(E.NOTDIR)
    return { dir, name }
  }

  changes(): Change[] {
    const current = new Map<string, File | Dir>()
    const walk = (dir: Dir, prefix: string) => {
      for (const [name, node] of dir.children) {
        const path = `${prefix}/${name}`
        current.set(path, node)
        if (isDir(node)) walk(node, path)
      }
    }
    walk(this.root, '')

    const deletes: string[] = []
    const replaced = new Set<string>()
    for (const [path, node] of this.loaded) {
      const now = current.get(path)
      if (!now || isDir(now) !== isDir(node)) deletes.push(path)
      if (now && isDir(now) !== isDir(node)) replaced.add(path)
    }
    deletes.sort()
    // Deleting a directory covers what is inside it
    const first: Change[] = []
    const last: Change[] = []
    let covered: string | undefined
    for (const path of deletes) {
      if (covered && path.startsWith(`${covered}/`)) continue
      covered = path
      ;(replaced.has(path) ? first : last).push({ op: 'delete', path })
    }

    const mkdirs: Change[] = []
    const writes: Change[] = []
    for (const [path, node] of [...current].sort((a, b) => a[0].length - b[0].length)) {
      const created = node.origin !== path
      if (isDir(node)) {
        if (created) mkdirs.push({ op: 'mkdir', path })
      } else if (created || node.dirty) {
        writes.push({ op: 'write', path, data: node.data?.slice(0, node.size) ?? new Uint8Array(0) })
      }
    }
    // Writes before deletes, so a failure part way never leaves data deleted but not yet written.
    // A path that changed between file and directory has to go first.
    return [...first, ...mkdirs, ...writes, ...last]
  }
}

export function run(options: RunOptions): RunResult {
  const tree = new Tree(options.entries)
  const encoder = new TextEncoder()
  const decoder = new TextDecoder()
  const cwd = tree.lookup(tree.root, options.cwd)
  if (!isDir(cwd)) throw new Error(`cwd ${options.cwd} is not in the snapshot`)

  let memory: WebAssembly.Memory
  const view = () => new DataView(memory.buffer)
  const bytes = () => new Uint8Array(memory.buffer)

  const handles = new Map<number, Handle>()
  handles.set(0, { node: undefined, offset: 0, append: false, readable: true, writable: false })
  handles.set(1, { node: undefined, offset: 0, append: false, readable: false, writable: true })
  handles.set(2, { node: undefined, offset: 0, append: false, readable: false, writable: true })
  // wasi-libc resolves every path against "/", whether it was relative or absolute, so the one
  // preopen is the working directory and the caller turns absolute paths into relative ones.
  // Its parents are real, so .. leaves it.
  handles.set(3, { node: cwd, offset: 0, append: false, readable: true, writable: false, preopen: '/' })
  let nextFd = 4

  const stdin = options.stdin ?? new Uint8Array(0)
  let pending: Uint8Array[] = []
  let pendingBytes = 0
  let pendingFd: 1 | 2 = 1
  let produced = 0
  const flush = () => {
    if (pendingBytes === 0) return
    const data = new Uint8Array(pendingBytes)
    let at = 0
    for (const chunk of pending) {
      data.set(chunk, at)
      at += chunk.length
    }
    pending = []
    pendingBytes = 0
    options.onOutput(pendingFd, data)
  }
  const emit = (fd: 1 | 2, chunk: Uint8Array) => {
    if (fd !== pendingFd) flush()
    pendingFd = fd
    pending.push(chunk.slice())
    pendingBytes += chunk.length
    produced += chunk.length
    if (pendingBytes >= FLUSH_AT) flush()
    if (produced > (options.maxOutput ?? MAX_OUTPUT)) {
      flush()
      throw new ProcExit(141)
    }
  }

  const handle = (fd: number): Handle => {
    const h = handles.get(fd)
    if (!h) throw new WasiError(E.BADF)
    return h
  }
  const dirOf = (fd: number): Dir => {
    const node = handle(fd).node
    if (!isDir(node)) throw new WasiError(E.NOTDIR)
    return node
  }
  const text = (ptr: number, len: number) => decoder.decode(bytes().subarray(ptr, ptr + len))

  const gather = (iovs: number, count: number): Array<[number, number]> => {
    const v = view()
    const list: Array<[number, number]> = []
    for (let i = 0; i < count; i++) list.push([v.getUint32(iovs + i * 8, true), v.getUint32(iovs + i * 8 + 4, true)])
    return list
  }

  const read = (h: Handle, fd: number, iovs: number, count: number, position?: number): number => {
    if (!h.readable) throw new WasiError(E.BADF)
    let total = 0
    for (const [ptr, len] of gather(iovs, count)) {
      let chunk: Uint8Array
      if (fd === 0) {
        chunk = stdin.subarray(h.offset, h.offset + len)
        h.offset += chunk.length
      } else if (h.node instanceof File) {
        const at = position ?? h.offset
        chunk = h.node.read(at, len)
        if (position === undefined) h.offset += chunk.length
      } else throw new WasiError(E.ISDIR)
      bytes().set(chunk, ptr)
      total += chunk.length
      if (chunk.length < len) break
    }
    return total
  }

  const write = (h: Handle, fd: number, iovs: number, count: number, position?: number): number => {
    if (!h.writable) throw new WasiError(E.BADF)
    let total = 0
    for (const [ptr, len] of gather(iovs, count)) {
      const chunk = bytes().subarray(ptr, ptr + len)
      if (fd === 1 || fd === 2) emit(fd, chunk)
      else if (h.node instanceof File) {
        const at = position ?? (h.append ? h.node.size : h.offset)
        h.node.write(at, chunk)
        if (position === undefined) h.offset = at + len
      } else throw new WasiError(E.ISDIR)
      total += len
    }
    return total
  }

  const filetype = (node: Node | undefined, fd: number) =>
    isDir(node) ? TYPE.DIR : node instanceof File ? TYPE.REG : fd === 1 && options.stdoutIsTty ? TYPE.CHAR : TYPE.UNKNOWN

  const writeStat = (ptr: number, node: File | Dir | undefined, fd = -1) => {
    const v = view()
    const ns = BigInt(Math.floor(node?.mtime ?? Date.now())) * 1_000_000n
    new Uint8Array(memory.buffer, ptr, 64).fill(0)
    v.setBigUint64(ptr + 8, BigInt(node?.ino ?? 0), true)
    v.setUint8(ptr + 16, filetype(node, fd))
    v.setBigUint64(ptr + 24, 1n, true)
    v.setBigUint64(ptr + 32, BigInt(node instanceof File ? node.size : 0), true)
    v.setBigUint64(ptr + 40, ns, true)
    v.setBigUint64(ptr + 48, ns, true)
    v.setBigUint64(ptr + 56, ns, true)
  }

  // Wraps a call so a WasiError becomes its errno
  const call =
    <A extends unknown[]>(fn: (...args: A) => void) =>
    (...args: A): number => {
      try {
        fn(...args)
        return E.SUCCESS
      } catch (error) {
        if (error instanceof WasiError) return error.errno
        throw error
      }
    }

  const wasi: Record<string, (...args: never[]) => unknown> = {
    args_sizes_get: call((argc: number, size: number) => {
      const v = view()
      v.setUint32(argc, options.args.length, true)
      v.setUint32(size, options.args.reduce((n, a) => n + encoder.encode(a).length + 1, 0), true)
    }),
    args_get: call((argv: number, buf: number) => writeStrings(options.args, argv, buf)),
    environ_sizes_get: call((count: number, size: number) => {
      const v = view()
      v.setUint32(count, options.env.length, true)
      v.setUint32(size, options.env.reduce((n, a) => n + encoder.encode(a).length + 1, 0), true)
    }),
    environ_get: call((environ: number, buf: number) => writeStrings(options.env, environ, buf)),
    clock_res_get: call((_id: number, out: number) => view().setBigUint64(out, 1_000_000n, true)),
    clock_time_get: call((id: number, _precision: bigint, out: number) => {
      const ms = id === 0 ? Date.now() : performance.now()
      view().setBigUint64(out, BigInt(Math.floor(ms * 1e6)), true)
    }),
    random_get: call((ptr: number, len: number) => crypto.getRandomValues(bytes().subarray(ptr, ptr + len))),
    sched_yield: () => E.SUCCESS,
    proc_exit: (code: number) => {
      throw new ProcExit(code)
    },
    proc_raise: () => E.NOSYS,

    fd_close: call((fd: number) => {
      handle(fd)
      if (fd > 3) handles.delete(fd)
    }),
    fd_fdstat_get: call((fd: number, out: number) => {
      const h = handle(fd)
      const v = view()
      new Uint8Array(memory.buffer, out, 24).fill(0)
      v.setUint8(out, filetype(h.node, fd))
      const tty = fd === 1 && options.stdoutIsTty
      v.setBigUint64(out + 8, tty ? ALL_RIGHTS & ~RIGHT_SEEK_TELL : ALL_RIGHTS, true)
      v.setBigUint64(out + 16, ALL_RIGHTS, true)
    }),
    fd_fdstat_set_flags: () => E.SUCCESS,
    fd_fdstat_set_rights: () => E.SUCCESS,
    fd_filestat_get: call((fd: number, out: number) => writeStat(out, handle(fd).node, fd)),
    fd_filestat_set_size: call((fd: number, size: bigint) => {
      const node = handle(fd).node
      if (!(node instanceof File)) throw new WasiError(E.INVAL)
      node.resize(Number(size))
    }),
    fd_filestat_set_times: () => E.SUCCESS,
    fd_advise: () => E.SUCCESS,
    fd_allocate: () => E.SUCCESS,
    fd_sync: () => E.SUCCESS,
    fd_datasync: () => E.SUCCESS,
    fd_renumber: () => E.NOSYS,
    fd_prestat_get: call((fd: number, out: number) => {
      const h = handle(fd)
      if (h.preopen === undefined) throw new WasiError(E.BADF)
      const v = view()
      v.setUint8(out, 0)
      v.setUint32(out + 4, encoder.encode(h.preopen).length, true)
    }),
    fd_prestat_dir_name: call((fd: number, ptr: number, len: number) => {
      const h = handle(fd)
      if (h.preopen === undefined) throw new WasiError(E.BADF)
      bytes().set(encoder.encode(h.preopen).subarray(0, len), ptr)
    }),
    fd_read: call((fd: number, iovs: number, count: number, out: number) => {
      view().setUint32(out, read(handle(fd), fd, iovs, count), true)
    }),
    fd_pread: call((fd: number, iovs: number, count: number, offset: bigint, out: number) => {
      view().setUint32(out, read(handle(fd), fd, iovs, count, Number(offset)), true)
    }),
    fd_write: call((fd: number, iovs: number, count: number, out: number) => {
      view().setUint32(out, write(handle(fd), fd, iovs, count), true)
    }),
    fd_pwrite: call((fd: number, iovs: number, count: number, offset: bigint, out: number) => {
      view().setUint32(out, write(handle(fd), fd, iovs, count, Number(offset)), true)
    }),
    fd_seek: call((fd: number, offset: bigint, whence: number, out: number) => {
      const h = handle(fd)
      if (!(h.node instanceof File)) throw new WasiError(E.SPIPE)
      const base = whence === 0 ? 0 : whence === 1 ? h.offset : h.node.size
      const next = base + Number(offset)
      if (next < 0) throw new WasiError(E.INVAL)
      h.offset = next
      view().setBigUint64(out, BigInt(next), true)
    }),
    fd_tell: call((fd: number, out: number) => {
      const h = handle(fd)
      if (!(h.node instanceof File)) throw new WasiError(E.SPIPE)
      view().setBigUint64(out, BigInt(h.offset), true)
    }),
    fd_readdir: call((fd: number, buf: number, len: number, cookie: bigint, used: number) => {
      const dir = dirOf(fd)
      const out: number[] = []
      let index = 0
      for (const [name, node] of dir.children) {
        if (index++ < Number(cookie)) continue
        const nameBytes = encoder.encode(name)
        const entry = new Uint8Array(24 + nameBytes.length)
        const v = new DataView(entry.buffer)
        v.setBigUint64(0, BigInt(index), true)
        v.setBigUint64(8, BigInt(node.ino), true)
        v.setUint32(16, nameBytes.length, true)
        v.setUint8(20, isDir(node) ? TYPE.DIR : TYPE.REG)
        entry.set(nameBytes, 24)
        out.push(...entry)
        if (out.length >= len) break
      }
      const slice = out.slice(0, len)
      bytes().set(slice, buf)
      view().setUint32(used, slice.length, true)
    }),

    path_create_directory: call((fd: number, ptr: number, len: number) => {
      const { dir, name } = tree.parentOf(dirOf(fd), text(ptr, len))
      if (dir.children.has(name)) throw new WasiError(E.EXIST)
      const node = new Dir()
      node.parent = dir
      dir.children.set(name, node)
    }),
    path_filestat_get: call((fd: number, _flags: number, ptr: number, len: number, out: number) => {
      const node = tree.lookup(dirOf(fd), text(ptr, len))
      if (!node) throw new WasiError(E.NOENT)
      writeStat(out, node)
    }),
    path_filestat_set_times: () => E.SUCCESS,
    path_link: () => E.NOTSUP,
    path_symlink: () => E.NOTSUP,
    path_readlink: () => E.INVAL,
    path_open: call(
      (
        fd: number,
        _dirflags: number,
        ptr: number,
        len: number,
        oflags: number,
        rights: bigint,
        _inheriting: bigint,
        fdflags: number,
        out: number
      ) => {
        const base = dirOf(fd)
        const path = text(ptr, len)
        let node = tree.lookup(base, path)
        if (!node) {
          if (!(oflags & OFLAG.CREAT)) throw new WasiError(E.NOENT)
          const { dir, name } = tree.parentOf(base, path)
          const file = new File()
          file.parent = dir
          file.dirty = true
          dir.children.set(name, file)
          node = file
        } else if (oflags & OFLAG.EXCL && oflags & OFLAG.CREAT) throw new WasiError(E.EXIST)
        const writable = (rights & RIGHT_WRITE) !== 0n
        if (isDir(node)) {
          if (writable) throw new WasiError(E.ISDIR)
        } else {
          if (oflags & OFLAG.DIRECTORY) throw new WasiError(E.NOTDIR)
          if (oflags & OFLAG.TRUNC) node.resize(0)
        }
        const newFd = nextFd++
        handles.set(newFd, {
          node,
          offset: 0,
          append: (fdflags & FDFLAG_APPEND) !== 0,
          readable: (rights & RIGHT_READ) !== 0n || isDir(node),
          writable
        })
        view().setUint32(out, newFd, true)
      }
    ),
    path_remove_directory: call((fd: number, ptr: number, len: number) => {
      const { dir, name } = tree.parentOf(dirOf(fd), text(ptr, len))
      const node = dir.children.get(name)
      if (!node) throw new WasiError(E.NOENT)
      if (!isDir(node)) throw new WasiError(E.NOTDIR)
      if (node.children.size > 0) throw new WasiError(E.NOTEMPTY)
      dir.children.delete(name)
    }),
    path_unlink_file: call((fd: number, ptr: number, len: number) => {
      const { dir, name } = tree.parentOf(dirOf(fd), text(ptr, len))
      const node = dir.children.get(name)
      if (!node) throw new WasiError(E.NOENT)
      if (isDir(node)) throw new WasiError(E.ISDIR)
      dir.children.delete(name)
    }),
    path_rename: call((fd: number, ptr: number, len: number, newFd: number, newPtr: number, newLen: number) => {
      const from = tree.parentOf(dirOf(fd), text(ptr, len))
      const to = tree.parentOf(dirOf(newFd), text(newPtr, newLen))
      const node = from.dir.children.get(from.name)
      if (!node) throw new WasiError(E.NOENT)
      const existing = to.dir.children.get(to.name)
      if (existing === node) return
      if (existing) {
        if (isDir(existing) !== isDir(node)) throw new WasiError(isDir(existing) ? E.ISDIR : E.NOTDIR)
        if (isDir(existing) && existing.children.size > 0) throw new WasiError(E.NOTEMPTY)
      }
      for (let up: Dir | undefined = to.dir; up; up = up.parent) if (up === node) throw new WasiError(E.INVAL)
      from.dir.children.delete(from.name)
      to.dir.children.set(to.name, node)
      node.parent = to.dir
    }),

    // Only clock waits are supported. A wait spins, since a worker has no cheaper way to block here.
    poll_oneoff: call((input: number, output: number, count: number, nevents: number) => {
      const v = view()
      let wait = 0
      for (let i = 0; i < count; i++) {
        const sub = input + i * 48
        if (v.getUint8(sub + 8) === 0) {
          const timeout = Number(v.getBigUint64(sub + 24, true) / 1_000_000n)
          const absolute = (v.getUint16(sub + 40, true) & 1) !== 0
          const now = v.getUint32(sub + 16, true) === 0 ? Date.now() : performance.now()
          wait = Math.max(wait, absolute ? timeout - now : timeout)
        }
        v.setBigUint64(output + i * 32, v.getBigUint64(sub, true), true)
        v.setUint16(output + i * 32 + 8, 0, true)
        v.setUint8(output + i * 32 + 10, v.getUint8(sub + 8))
      }
      for (const end = performance.now() + wait; performance.now() < end; );
      v.setUint32(nevents, count, true)
    }),
    sock_accept: () => E.NOSYS,
    sock_recv: () => E.NOSYS,
    sock_send: () => E.NOSYS,
    sock_shutdown: () => E.NOSYS
  }

  function writeStrings(list: string[], pointers: number, buf: number) {
    const v = view()
    let at = buf
    list.forEach((item, i) => {
      v.setUint32(pointers + i * 4, at, true)
      const encoded = encoder.encode(item)
      bytes().set(encoded, at)
      bytes()[at + encoded.length] = 0
      at += encoded.length + 1
    })
  }

  for (const { module: name, name: field } of WebAssembly.Module.imports(options.module)) {
    if (name === 'wasi_snapshot_preview1' && !(field in wasi)) wasi[field] = () => E.NOSYS
  }
  const instance = new WebAssembly.Instance(options.module, { wasi_snapshot_preview1: wasi as WebAssembly.ModuleImports })
  memory = instance.exports.memory as WebAssembly.Memory

  let code = 0
  try {
    ;(instance.exports._start as () => void)()
  } catch (error) {
    if (error instanceof ProcExit) code = error.code
    else {
      flush()
      throw error
    }
  }
  flush()
  return { code, changes: tree.changes() }
}
