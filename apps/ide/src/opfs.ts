import {
  FileChangeType,
  FileSystemProviderCapabilities,
  FileSystemProviderError,
  FileSystemProviderErrorCode,
  FileType,
  IndexedDB,
  type IFileChange,
  type IFileDeleteOptions,
  type IFileOverwriteOptions,
  type IFileSystemProviderWithFileReadWriteCapability,
  type IFileWriteOptions,
  type IStat
} from '@codingame/monaco-vscode-files-service-override'
import { EventEmitter, Uri } from 'vscode'
import { OPFS_STORE, exactBytes } from './opfsStore'
const LEGACY_DB = 'frc-dev-wasm-workspace'

const error = (message: string, code: FileSystemProviderErrorCode) => FileSystemProviderError.create(message, code)

function toProviderError(e: unknown, path: string): unknown {
  if (e instanceof FileSystemProviderError || !(e instanceof DOMException)) return e
  switch (e.name) {
    case 'NotFoundError':
      return error(`No such file or directory: ${path}`, FileSystemProviderErrorCode.FileNotFound)
    case 'TypeMismatchError':
      return error(`Not a directory, or is a directory: ${path}`, FileSystemProviderErrorCode.FileNotADirectory)
    case 'QuotaExceededError':
      return error(`Out of storage: ${path}`, FileSystemProviderErrorCode.FileExceedsStorageQuota)
    case 'InvalidModificationError':
      return error(`Directory not empty: ${path}`, FileSystemProviderErrorCode.Unknown)
    default:
      return error(`${e.name}: ${e.message} (${path})`, FileSystemProviderErrorCode.Unknown)
  }
}

const split = (path: string) => path.split('/').filter(Boolean)

async function copyInto(source: FileSystemHandle, parent: FileSystemDirectoryHandle, name: string): Promise<void> {
  if (source instanceof FileSystemFileHandle) {
    const writable = await (await parent.getFileHandle(name, { create: true })).createWritable()
    await writable.write(await source.getFile())
    await writable.close()
    return
  }
  const target = await parent.getDirectoryHandle(name, { create: true })
  for await (const [childName, child] of (source as FileSystemDirectoryHandle).entries()) await copyInto(child, target, childName)
}

// Files under file:/// kept in the origin private file system. Unlike IndexedDB, a worker can read
// these files synchronously, and modification times survive a reload.
export class OpfsFileSystemProvider implements IFileSystemProviderWithFileReadWriteCapability {
  readonly capabilities =
    FileSystemProviderCapabilities.FileReadWrite | FileSystemProviderCapabilities.FileAppend | FileSystemProviderCapabilities.PathCaseSensitive
  readonly onDidChangeCapabilities = new EventEmitter<void>().event
  private readonly changes = new EventEmitter<readonly IFileChange[]>()
  readonly onDidChangeFile = this.changes.event
  private readonly directories = new Map<string, Promise<FileSystemDirectoryHandle>>()
  private readonly writes = new Set<Promise<unknown>>()
  private gate: Promise<void> | undefined

  constructor(private readonly root: FileSystemDirectoryHandle) {}

  // Opens the store, or returns undefined where the browser cannot write to it from the page
  static async open(): Promise<OpfsFileSystemProvider | undefined> {
    if (typeof FileSystemFileHandle === 'undefined' || !('createWritable' in FileSystemFileHandle.prototype)) return undefined
    try {
      const root = await (await navigator.storage.getDirectory()).getDirectoryHandle(OPFS_STORE, { create: true })
      return new OpfsFileSystemProvider(root)
    } catch {
      return undefined
    }
  }

  private fireChanges(changes: IFileChange[]): void {
    this.changes.fire(changes)
  }

  // Reports files changed by a worker, such as git
  notifyChanged(paths: string[]): void {
    if (paths.length) this.fireChanges(paths.map((path) => ({ resource: Uri.file(path), type: FileChangeType.UPDATED })))
  }

  // Runs work that holds files open, such as a git command. Writes wait until it ends, and it waits
  // for writes in progress, because a file open in a worker cannot be written from the page.
  async exclusive<T>(work: () => Promise<T>): Promise<T> {
    while (this.gate) await this.gate
    let open!: () => void
    this.gate = new Promise((resolve) => (open = resolve))
    try {
      await Promise.allSettled(this.writes)
      return await work()
    } finally {
      this.gate = undefined
      open()
    }
  }

  // Set by the git worker, which can keep files open between commands
  releaseFiles?: () => Promise<void>

  private async mutate<T>(path: string, operation: () => Promise<T>): Promise<T> {
    while (this.gate) await this.gate
    const pending = (async () => {
      await this.releaseFiles?.()
      return this.run(path, operation)
    })()
    this.writes.add(pending)
    try {
      return await pending
    } finally {
      this.writes.delete(pending)
    }
  }

  private directory(parts: string[]): Promise<FileSystemDirectoryHandle> {
    if (parts.length === 0) return Promise.resolve(this.root)
    const key = parts.join('/')
    let handle = this.directories.get(key)
    if (!handle) {
      handle = this.directory(parts.slice(0, -1)).then((parent) => parent.getDirectoryHandle(parts[parts.length - 1]))
      handle.catch(() => this.directories.delete(key))
      this.directories.set(key, handle)
    }
    return handle
  }

  private forget(path: string): void {
    const key = split(path).join('/')
    for (const cached of this.directories.keys()) if (cached === key || cached.startsWith(`${key}/`)) this.directories.delete(cached)
  }

  private async entry(path: string): Promise<{ parent: FileSystemDirectoryHandle; name: string; handle: FileSystemHandle }> {
    const parts = split(path)
    const name = parts.pop()
    if (name === undefined) throw error('The root has no parent', FileSystemProviderErrorCode.Unavailable)
    const parent = await this.directory(parts)
    const handle = await parent.getFileHandle(name).catch((e: unknown) => {
      if (e instanceof DOMException && e.name === 'TypeMismatchError') return this.directory([...parts, name])
      throw e
    })
    return { parent, name, handle }
  }

  private async file(path: string): Promise<FileSystemFileHandle> {
    const { handle } = await this.entry(path)
    if (!(handle instanceof FileSystemFileHandle)) throw error(`Is a directory: ${path}`, FileSystemProviderErrorCode.FileIsADirectory)
    return handle
  }

  private async run<T>(path: string, operation: () => Promise<T>): Promise<T> {
    try {
      return await operation()
    } catch (e) {
      throw toProviderError(e, path)
    }
  }

  watch() {
    return { dispose() {} }
  }

  stat(resource: Uri): Promise<IStat> {
    return this.run(resource.path, async () => {
      if (split(resource.path).length === 0) return { type: FileType.Directory, ctime: 0, mtime: 0, size: 0 }
      const { handle } = await this.entry(resource.path)
      if (!(handle instanceof FileSystemFileHandle)) return { type: FileType.Directory, ctime: 0, mtime: 0, size: 0 }
      const file = await handle.getFile()
      return { type: FileType.File, ctime: 0, mtime: file.lastModified, size: file.size }
    })
  }

  readdir(resource: Uri): Promise<[string, FileType][]> {
    return this.run(resource.path, async () => {
      const result: [string, FileType][] = []
      for await (const [name, handle] of (await this.directory(split(resource.path))).entries()) {
        result.push([name, handle.kind === 'file' ? FileType.File : FileType.Directory])
      }
      return result
    })
  }

  readFile(resource: Uri): Promise<Uint8Array> {
    return this.run(resource.path, async () => new Uint8Array(await (await (await this.file(resource.path)).getFile()).arrayBuffer()))
  }

  writeFile(resource: Uri, content: Uint8Array, opts: IFileWriteOptions): Promise<void> {
    return this.mutate(resource.path, async () => {
      const parts = split(resource.path)
      const name = parts.pop()!
      const parent = await this.directory(parts)
      let handle = await parent.getFileHandle(name).catch((e: unknown) => {
        if (e instanceof DOMException && e.name === 'NotFoundError') return undefined
        if (e instanceof DOMException && e.name === 'TypeMismatchError') {
          throw error(`Is a directory: ${resource.path}`, FileSystemProviderErrorCode.FileIsADirectory)
        }
        throw e
      })
      if (handle && !opts.overwrite) throw error(`File exists: ${resource.path}`, FileSystemProviderErrorCode.FileExists)
      if (!handle && !opts.create) throw error(`No such file: ${resource.path}`, FileSystemProviderErrorCode.FileNotFound)
      const created = !handle
      handle ??= await parent.getFileHandle(name, { create: true })
      const writable = await handle.createWritable({ keepExistingData: !!opts.append })
      if (opts.append) await writable.seek((await handle.getFile()).size)
      await writable.write(exactBytes(content))
      await writable.close()
      this.fireChanges([{ resource, type: created ? FileChangeType.ADDED : FileChangeType.UPDATED }])
    })
  }

  mkdir(resource: Uri): Promise<void> {
    return this.mutate(resource.path, async () => {
      const parts = split(resource.path)
      const name = parts.pop()
      if (name === undefined) return
      await (await this.directory(parts)).getDirectoryHandle(name, { create: true })
      this.fireChanges([{ resource, type: FileChangeType.ADDED }])
    })
  }

  delete(resource: Uri, opts: IFileDeleteOptions): Promise<void> {
    return this.mutate(resource.path, () => this.remove(resource, opts.recursive))
  }

  private async remove(resource: Uri, recursive: boolean): Promise<void> {
    const parts = split(resource.path)
    const name = parts.pop()
    if (name === undefined) throw error('Cannot delete the root', FileSystemProviderErrorCode.NoPermissions)
    try {
      await (await this.directory(parts)).removeEntry(name, { recursive })
    } catch (e) {
      if (e instanceof DOMException && e.name === 'NotFoundError') return
      throw e
    }
    this.forget(resource.path)
    this.fireChanges([{ resource, type: FileChangeType.DELETED }])
  }

  rename(from: Uri, to: Uri, opts: IFileOverwriteOptions): Promise<void> {
    return this.mutate(from.path, async () => {
      if (from.path === to.path) return
      const source = await this.entry(from.path)
      const exists = await this.stat(to).then(
        () => true,
        () => false
      )
      if (exists && !opts.overwrite) throw error(`File exists: ${to.path}`, FileSystemProviderErrorCode.FileExists)
      if (exists) await this.remove(to, true)
      const parts = split(to.path)
      const name = parts.pop()!
      const target = await this.directory(parts)
      this.forget(from.path)
      this.forget(to.path)
      const moved = await source.handle.move?.(target, name).then(
        () => true,
        () => false
      )
      if (!moved) {
        await copyInto(source.handle, target, name)
        await source.parent.removeEntry(source.name, { recursive: true })
      }
      this.fireChanges([
        { resource: from, type: FileChangeType.DELETED },
        { resource: to, type: FileChangeType.ADDED }
      ])
    })
  }

  // Copies the projects an earlier version kept in IndexedDB, then deletes that database. Until the
  // copy is complete the database stays, so an interrupted copy starts over on the next load.
  async migrateFromIndexedDB(): Promise<void> {
    if (!(await indexedDB.databases()).some((db) => db.name === LEGACY_DB)) return
    const db = await IndexedDB.create(LEGACY_DB, 1, ['files'])
    const keys = await db.runInTransaction('files', 'readonly', (store) => store.getAllKeys())
    for (const key of keys) {
      const path = String(key)
      const value = await db.runInTransaction('files', 'readonly', (store) => store.get(path))
      const content = value instanceof Uint8Array ? value : typeof value === 'string' ? new TextEncoder().encode(value) : undefined
      if (!content) continue
      const parts = split(path)
      const name = parts.pop()
      if (name === undefined) continue
      let dir = this.root
      for (const part of parts) dir = await dir.getDirectoryHandle(part, { create: true })
      const handle = await dir.getFileHandle(name, { create: true })
      const writable = await handle.createWritable()
      await writable.write(exactBytes(content))
      await writable.close()
      if ((await handle.getFile()).size !== content.byteLength) throw new Error(`${path} was not copied out of IndexedDB`)
    }
    db.close()
    await new Promise<void>((resolve, reject) => {
      const req = indexedDB.deleteDatabase(LEGACY_DB)
      req.onsuccess = () => resolve()
      req.onerror = () => reject(req.error)
    })
  }
}
