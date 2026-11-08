import type { IWorkspaceProvider } from '@codingame/monaco-vscode-api/vscode/vs/workbench/browser/web.api'
import {
  FileChangeType,
  FileSystemProviderError,
  FileSystemProviderErrorCode,
  IndexedDB,
  IndexedDBFileSystemProvider,
  InMemoryFileSystemProvider,
  OverlayFileSystemProvider,
  createHTMLFileSystemProvider,
  registerCustomProvider,
  registerFileSystemOverlay,
  type HTMLFileSystemProvider,
  type IFileSystemProviderWithFileReadWriteCapability
} from '@codingame/monaco-vscode-files-service-override'
import { Uri } from 'vscode'
import { OpfsFileSystemProvider } from './opfs'

export const workspaceFolder = Uri.file('/workspace')

const FOLDER_KEY = 'frc-dev-wasm.folder'

export const localFoldersSupported = 'showDirectoryPicker' in window

const inWorkspace = (resource: Uri) =>
  resource.path === workspaceFolder.path || resource.path.startsWith(`${workspaceFolder.path}/`)

// Local folder handles live at /<folder name>. The browser workspace is skipped up front so its
// paths never cost a handle lookup.
function skipWorkspace(html: HTMLFileSystemProvider): HTMLFileSystemProvider {
  const guarded = new Set<string | symbol>(['stat', 'readdir', 'readFile', 'writeFile', 'mkdir', 'delete', 'rename'])
  return new Proxy(html, {
    get(target, key) {
      const value = Reflect.get(target, key, target)
      if (typeof value !== 'function') return value
      if (key === 'watch') {
        return (resource: Uri, ...rest: unknown[]) =>
          inWorkspace(resource) ? { dispose() {} } : value.call(target, resource, ...rest)
      }
      if (!guarded.has(key)) return value.bind(target)
      return (resource: Uri, ...rest: unknown[]) =>
        inWorkspace(resource)
          ? Promise.reject(FileSystemProviderError.create('Not found', FileSystemProviderErrorCode.FileNotFound))
          : value.call(target, resource, ...rest)
    }
  })
}

// Deliberately has no `directories`: the dialog and search services would then expect handles for
// every folder. Without it they work through the file service, so they also work in the browser workspace.
class WorkspaceFileSystem extends OverlayFileSystemProvider {
  constructor(readonly html: HTMLFileSystemProvider, store: IFileSystemProviderWithFileReadWriteCapability) {
    super()
    this.register(2, skipWorkspace(html))
    this.register(1, store)
    this.register(0, new InMemoryFileSystemProvider())
  }
  registerDirectoryHandle(handle: FileSystemDirectoryHandle) {
    return this.html.registerDirectoryHandle(handle)
  }
  // Folders from disk report changes only where the browser watches them
  notifyChanged(paths: string[]) {
    if (paths.length) this._onDidChangeFile.fire(paths.map((path) => ({ resource: Uri.file(path), type: FileChangeType.UPDATED })))
  }
}

// Folders from disk the workbench has access to, by their path in it
const diskFolders = new Map<string, FileSystemDirectoryHandle>()

export function diskFolderFor(path: string): { base: string; handle: FileSystemDirectoryHandle } | undefined {
  for (const [base, handle] of diskFolders) if (path === base || path.startsWith(`${base}/`)) return { base, handle }
  return undefined
}

export const notifyDiskChanges = (paths: string[]) => fileSystem?.notifyChanged(paths)

let fileSystem: WorkspaceFileSystem | undefined

async function savedHandle(path: string): Promise<FileSystemDirectoryHandle | undefined> {
  const stores = ['vscode-userdata-store', 'vscode-logs-store', 'vscode-filehandles-store']
  const db = await IndexedDB.create('vscode-web-db', 3, stores)
  const handle = await db.runInTransaction<unknown>(stores[2], 'readonly', (store) => store.get(path))
  db.close()
  return handle instanceof FileSystemDirectoryHandle ? handle : undefined
}

const split = (path: string) => path.split('/').filter(Boolean)

async function hasFolder(handle: FileSystemDirectoryHandle, parts: string[]): Promise<boolean> {
  try {
    for (const part of parts) handle = await handle.getDirectoryHandle(part)
    return true
  } catch {
    return false
  }
}

export interface PendingFolder {
  name: string
  // Needs a user gesture. Reloads the page when access is granted.
  reopen(): Promise<boolean>
}

// A folder that was open before the reload but whose access the browser no longer grants
export let pendingFolder: PendingFolder | undefined

// Where the browser workspace is kept, unless the browser cannot write to it from the page
export let workspaceStore: OpfsFileSystemProvider | undefined

// The origin private file system, or IndexedDB in browsers that cannot write to it from the page
async function openStore(): Promise<IFileSystemProviderWithFileReadWriteCapability> {
  const opfs = await OpfsFileSystemProvider.open()
  // Projects stay in IndexedDB if they cannot all be copied
  const moved = await opfs?.migrateFromIndexedDB().then(
    () => true,
    (error: unknown) => (console.error(error), false)
  )
  if (opfs && moved) {
    workspaceStore = opfs
    return opfs
  }
  const db = await IndexedDB.create('frc-dev-wasm-workspace', 1, ['files'])
  return new IndexedDBFileSystemProvider('file', db, 'files', false)
}

// Whether the real git can run in the open folder: a project in the origin private file system, or
// a folder from disk
export let realGitFolder = false

// Where the browser workspace is kept, whichever store holds it
let workspaceFiles: IFileSystemProviderWithFileReadWriteCapability | undefined

// Persists everything under file:/// in the browser. Local folders opened from disk appear next to it.
// Returns the folder the workbench should open: a project under /workspace, a folder from disk, or /workspace.
export async function mountWorkspace(): Promise<Uri> {
  const store = await openStore()
  workspaceFiles = store
  await store.mkdir(workspaceFolder)
  if (localFoldersSupported) {
    fileSystem = new WorkspaceFileSystem(createHTMLFileSystemProvider(), store)
    registerCustomProvider('file', fileSystem)
  } else {
    registerFileSystemOverlay(1, store)
  }

  const folder = await savedFolder(store)
  realGitFolder = (!!workspaceStore && inWorkspace(folder)) || !!diskFolderFor(folder.path)
  return folder
}

async function savedFolder(store: IFileSystemProviderWithFileReadWriteCapability): Promise<Uri> {
  const path = localStorage.getItem(FOLDER_KEY)
  if (!path) return workspaceFolder
  const saved = Uri.file(path)
  if (inWorkspace(saved)) return (await store.stat(saved).then(() => true, () => false)) ? saved : workspaceFolder
  if (!localFoldersSupported) return workspaceFolder
  // Handles are saved for the folders picked from disk, at /<name>. A folder inside one, such as a
  // project created there, opens through it.
  const [name, ...rest] = split(path)
  if (!name) return workspaceFolder
  const root = `/${name}`
  const handle = await savedHandle(root).catch(() => undefined)
  if (handle && (await handle.queryPermission({ mode: 'readwrite' })) === 'granted') {
    diskFolders.set(root, handle)
    return (await hasFolder(handle, rest)) ? saved : Uri.file(root)
  }
  if (handle) {
    pendingFolder = {
      name: handle.name,
      async reopen() {
        if ((await handle.requestPermission({ mode: 'readwrite' })) !== 'granted') return false
        location.reload()
        return true
      }
    }
  }
  return workspaceFolder
}

// A file of the folder the workbench opens, before the workbench has started. Undefined when it is missing.
export async function readFolderFile(folder: Uri, name: string): Promise<Uint8Array | undefined> {
  try {
    if (inWorkspace(folder)) return await workspaceFiles?.readFile(Uri.joinPath(folder, name))
    const disk = diskFolderFor(folder.path)
    if (!disk) return undefined
    let dir = disk.handle
    for (const part of split(folder.path.slice(disk.base.length))) dir = await dir.getDirectoryHandle(part)
    return new Uint8Array(await (await (await dir.getFileHandle(name)).getFile()).arrayBuffer())
  } catch {
    return undefined
  }
}

// Asks for a folder on disk and registers it. Resolves to its URI, or undefined when cancelled.
export async function pickLocalFolder(): Promise<Uri | undefined> {
  if (!fileSystem) throw new Error('This browser cannot open folders from disk')
  try {
    const handle = await window.showDirectoryPicker({ mode: 'readwrite' })
    const uri = await fileSystem.registerDirectoryHandle(handle)
    diskFolders.set(uri.path, handle)
    return uri
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') return undefined
    throw error
  }
}

export const hasSavedFolder = () => localStorage.getItem(FOLDER_KEY) !== null

export function closeFolder(): void {
  localStorage.removeItem(FOLDER_KEY)
  location.reload()
}

export function createWorkspaceProvider(folder: Uri): IWorkspaceProvider {
  return {
    trusted: true,
    workspace: { folderUri: folder },
    async open(workspace, options) {
      const target = workspace && 'folderUri' in workspace ? workspace.folderUri.path : undefined
      if (!target && !options?.reuse) {
        window.open(window.location.href)
        return true
      }
      if (target === folder.path) return true
      if (target && target !== workspaceFolder.path) {
        localStorage.setItem(FOLDER_KEY, target)
        location.reload()
      } else {
        closeFolder()
      }
      return true
    }
  }
}
